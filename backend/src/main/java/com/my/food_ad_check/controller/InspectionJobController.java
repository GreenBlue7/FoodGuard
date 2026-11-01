package com.my.food_ad_check.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.my.food_ad_check.dto.FastApiRequest;
import com.my.food_ad_check.dto.FastApiResponse;
import com.my.food_ad_check.dto.InspectionJobRequest;
import com.my.food_ad_check.entity.InspectionRecord;
import com.my.food_ad_check.entity.Product;
import com.my.food_ad_check.repository.InspectionRecordRepository;
import com.my.food_ad_check.repository.ProductRepository;
import com.my.food_ad_check.service.AiJudgmentService;
import com.my.food_ad_check.service.InspectionResultMapper;
import com.my.food_ad_check.service.ProductApiService;
import com.my.food_ad_check.service.RuleCheckService;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Synchronous inspection followed by a persisted job lookup. */
@RestController
@RequestMapping("/api/inspections")
public class InspectionJobController {

    private final ProductRepository productRepository;
    private final InspectionRecordRepository inspectionRecordRepository;
    private final ProductApiService productApiService;
    private final AiJudgmentService aiJudgmentService;
    private final RuleCheckService ruleCheckService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public InspectionJobController(ProductRepository productRepository,
                                   InspectionRecordRepository inspectionRecordRepository,
                                   ProductApiService productApiService,
                                   AiJudgmentService aiJudgmentService,
                                   RuleCheckService ruleCheckService) {
        this.productRepository = productRepository;
        this.inspectionRecordRepository = inspectionRecordRepository;
        this.productApiService = productApiService;
        this.aiJudgmentService = aiJudgmentService;
        this.ruleCheckService = ruleCheckService;
    }

    // SecurityConfig가 로그인을 요구하므로 jwt는 항상 있음, sub에는 회원 id
    @PostMapping
    public Map<String, String> create(@AuthenticationPrincipal Jwt jwt,
                                      @RequestBody(required = false) InspectionJobRequest request) {
        if (request == null || request.adText() == null || request.adText().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "검수할 광고 문구를 입력해 주세요.");
        }
        List<String> ids = request.productIds() == null ? List.of() : request.productIds();
        if (ids.size() > 1 || (ids.size() == 1 && (ids.get(0) == null || ids.get(0).isBlank()))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "검사 한 번에 제품 하나만 선택해 주세요.");
        }
        if (request.productTypeAnswer() != null && !List.of(
                "HEALTH_FUNCTIONAL", "FUNCTIONAL_GENERAL", "GENERAL", "UNKNOWN")
                .contains(request.productTypeAnswer())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "알 수 없는 제품 유형 답변입니다.");
        }

        ids = ids.isEmpty() ? List.of() : List.of(ids.get(0).trim());
        Product product = ids.isEmpty() ? null : productRepository.findById(ids.get(0)).orElse(null);
        String productType = product == null
                ? ("GENERAL".equals(request.productTypeAnswer()) ? "GENERAL_FOOD" : "UNKNOWN")
                : switch (product.getProductType()) {
                    case "건강기능식품" -> "HEALTH_FUNCTIONAL";
                    case "일반식품" -> "GENERAL_FOOD";
                    default -> "UNKNOWN";
                };
        String source = product != null ? "MFDS"
                : "GENERAL_FOOD".equals(productType) ? "USER_DECLARED" : "UNVERIFIED";
        String koreanType = switch (productType) {
            case "HEALTH_FUNCTIONAL" -> "건강기능식품";
            case "GENERAL_FOOD" -> "일반식품";
            default -> null;
        };
        String adText = request.adText().trim();
        List<String> approvedFunctions = List.of();
        if (product != null && "HEALTH_FUNCTIONAL".equals(productType)) {
            approvedFunctions = product.getApprovedFunctions();
            if (approvedFunctions.isEmpty()) {
                approvedFunctions = productApiService.parsePrimaryFnclty(product.getPrimaryFnclty());
            }
        }

        List<String> missingItems = koreanType == null ? List.of()
                : ruleCheckService.checkMandatoryPhrases(koreanType, adText);
        Map<String, Object> step1 = new LinkedHashMap<>();
        step1.put("status", koreanType == null ? "REVIEW" : missingItems.isEmpty() ? "PASS" : "FAIL");
        step1.put("missingItems", missingItems);
        step1.put("provisional", true);

        String judgeRequestId = UUID.randomUUID().toString();
        FastApiRequest judgeRequest = new FastApiRequest(judgeRequestId, "C",
                new FastApiRequest.Product(productType,
                        product == null ? List.of() : List.of(product.getPrdlstNm()), approvedFunctions), adText);
        AiJudgmentService.JudgmentOutcome outcome = aiJudgmentService.requestJudgmentOutcome(judgeRequest);
        FastApiResponse judgeResponse = outcome.response();
        String status = outcome.status();
        String judgedAt = OffsetDateTime.now().toString();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("request", new InspectionJobRequest(ids, request.productTypeAnswer(), adText));
        result.put("productInfoSource", source);
        result.put("appliedProductType", switch (productType) {
            case "HEALTH_FUNCTIONAL" -> "HEALTH_FUNCTIONAL";
            case "GENERAL_FOOD" -> "GENERAL";
            default -> "UNDETERMINED";
        });
        result.put("layer1", null);
        result.put("layer2", judgeResponse == null ? null
                : InspectionResultMapper.toLayer2(judgeResponse, judgedAt));
        result.put("provisionalStep1", step1);
        result.put("judgeRequestId", judgeRequestId);
        result.put("judgeResponse", judgeResponse);
        result.put("createdAt", judgedAt);

        InspectionRecord saved = inspectionRecordRepository.save(new InspectionRecord(
                Long.valueOf(jwt.getSubject()),
                product == null ? null : product.getPrdlstReportNo(),
                product == null ? null : product.getPrdlstNm(),
                product == null ? null : product.getBsshNm(),
                koreanType,
                product == null ? null : product.getPrimaryFnclty(),
                adText, status, serialize(result), LocalDateTime.now()));
        return Map.of("jobId", saved.getId().toString());
    }

    @GetMapping("/{jobId}")
    public Map<String, Object> get(@AuthenticationPrincipal Jwt jwt, @PathVariable String jobId) {
        final long id;
        try {
            id = Long.parseLong(jobId);
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "검수 기록을 찾을 수 없습니다.");
        }
        // 남의 기록도 "없음"(404)으로 응답해서, 그 번호의 기록이 존재하는지조차 드러나지 않게 한다
        InspectionRecord record = inspectionRecordRepository.findByIdAndUserId(id, Long.valueOf(jwt.getSubject()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "검수 기록을 찾을 수 없습니다."));
        try {
            Map<String, Object> result = objectMapper.readValue(record.getResultJson(), new TypeReference<>() { });
            if (!result.containsKey("request") || !result.containsKey("productInfoSource")) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "검수 작업을 찾을 수 없습니다.");
            }
            result.put("jobId", jobId);
            result.put("status", record.getStatus());
            return result;
        } catch (JsonProcessingException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "저장된 검수 결과를 읽을 수 없습니다.", e);
        }
    }

    private String serialize(Map<String, Object> result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "검수 결과를 저장할 수 없습니다.", e);
        }
    }
}
