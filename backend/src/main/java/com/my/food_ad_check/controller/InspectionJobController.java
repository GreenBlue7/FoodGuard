package com.my.food_ad_check.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.my.food_ad_check.dto.FastApiRequest;
import com.my.food_ad_check.service.JudgeProductDetails;
import com.my.food_ad_check.dto.InspectionHistoryResponse;
import com.my.food_ad_check.dto.InspectionJobRequest;
import com.my.food_ad_check.entity.InspectionRecord;
import com.my.food_ad_check.entity.Product;
import com.my.food_ad_check.repository.InspectionRecordRepository;
import com.my.food_ad_check.repository.ProductRepository;
import com.my.food_ad_check.service.*;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * Durable asynchronous inspection submission followed by an owner-protected job lookup.
 */
@RestController
@RequestMapping("/api/inspections")
public class InspectionJobController {

    private final ProductRepository productRepository;
    private final InspectionRecordRepository inspectionRecordRepository;
    private final ProductApiService productApiService;
    private final InspectionJobQueue inspectionJobQueue;
    private final RuleCheckService ruleCheckService;
    private final InspectionHistoryService inspectionHistoryService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public InspectionJobController(ProductRepository productRepository,
                                   InspectionRecordRepository inspectionRecordRepository,
                                   ProductApiService productApiService,
                                   InspectionJobQueue inspectionJobQueue,
                                   RuleCheckService ruleCheckService,
                                   InspectionHistoryService inspectionHistoryService) {
        this.productRepository = productRepository;
        this.inspectionRecordRepository = inspectionRecordRepository;
        this.productApiService = productApiService;
        this.inspectionJobQueue = inspectionJobQueue;
        this.ruleCheckService = ruleCheckService;
        this.inspectionHistoryService = inspectionHistoryService;
    }

    // SecurityConfig가 로그인을 요구하므로 jwt는 항상 있음, sub에는 회원 id
    @PostMapping
    public Map<String, String> create(@AuthenticationPrincipal Jwt jwt,
                                      @RequestBody(required = false) InspectionJobRequest request) {
        return createInternal(jwt, request, null);
    }

    public record ReinspectionRequest(String adText, List<SentenceComparison.Edit> sentenceEdits) {
        public ReinspectionRequest(String adText) { this(adText, List.of()); }
    }
    public record CurrentAdRequest(String adText, boolean confirmedCurrentAd, List<SentenceComparison.Edit> sentenceEdits) {
        public CurrentAdRequest(String adText, boolean confirmedCurrentAd) { this(adText, confirmedCurrentAd, List.of()); }
    }

    @PostMapping("/{jobId}/recheck")
    public Map<String, String> recheck(@AuthenticationPrincipal Jwt jwt, @PathVariable Long jobId,
            @RequestBody(required = false) CurrentAdRequest request) {
        if (request == null || !request.confirmedCurrentAd()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "현재 게재 중인 광고 문구인지 확인해 주세요.");
        }
        return reinspectInternal(jwt, jobId, request.adText(), true, request.sentenceEdits());
    }

    @PostMapping("/{jobId}/reinspections")
    public Map<String, String> reinspect(@AuthenticationPrincipal Jwt jwt, @PathVariable Long jobId,
            @RequestBody(required = false) ReinspectionRequest request) {
        return reinspectInternal(jwt, jobId, request == null ? null : request.adText(), false,
                request == null ? List.of() : request.sentenceEdits());
    }

    private Map<String, String> reinspectInternal(Jwt jwt, Long jobId, String adText, boolean allowUnchanged,
                                                List<SentenceComparison.Edit> sentenceEdits) {
        InspectionRecord previous = inspectionRecordRepository.findByIdAndUserId(jobId, Long.valueOf(jwt.getSubject()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "검수 기록을 찾을 수 없습니다."));
        if (List.of("PENDING", "RUNNING").contains(previous.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "진행 중인 검사가 끝난 뒤 재검수해 주세요.");
        }
        if (adText == null || adText.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "수정한 광고 문구를 입력해 주세요.");
        }
        if (!allowUnchanged && adText.trim().equals(previous.getAdText())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "기존 문구와 같습니다. 수정한 문구를 입력해 주세요.");
        }
        try {
            var previousResult = objectMapper.readTree(previous.getResultJson());
            if (previousResult == null || !previousResult.isObject()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "이전 검사 입력 정보를 복원할 수 없습니다.");
            }
            var old = previousResult.path("request");
            if (old.isMissingNode() || old.isNull()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "이전 검사 입력 정보를 복원할 수 없습니다.");
            }
            InspectionJobRequest original = objectMapper.treeToValue(old, InspectionJobRequest.class);
            List<SentenceComparison.Edit> edits = sentenceEdits == null ? List.of() : sentenceEdits;
            try {
                SentenceComparison.validateEdits(previousResult, previous.getAdText(), adText.trim(), edits);
            } catch (IllegalArgumentException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
            }
            return createInternal(jwt, new InspectionJobRequest(original.productIds(),
                    original.productTypeAnswer(), adText), previous, null, edits);
        } catch (JsonProcessingException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "이전 검사 정보를 읽을 수 없습니다.", e);
        }
    }

    private Map<String, String> createInternal(Jwt jwt, InspectionJobRequest request, InspectionRecord previous) {
        return createInternal(jwt, request, previous, null);
    }

    private Map<String, String> createInternal(Jwt jwt, InspectionJobRequest request, InspectionRecord previous,
                                              InspectionRecommendations.Draft selectedRecommendation) {
        return createInternal(jwt, request, previous, selectedRecommendation, List.of());
    }

    private Map<String, String> createInternal(Jwt jwt, InspectionJobRequest request, InspectionRecord previous,
            InspectionRecommendations.Draft selectedRecommendation, List<SentenceComparison.Edit> sentenceEdits) {
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
        if (product != null && product.isFunctionSourceReviewRequired()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "최신 기능성 목록을 자동 추출하지 못해 검사를 보류했습니다. 데이터 갱신 후 다시 시도해 주세요.");
        }
        Long productVersion = product == null ? null : product.getVersion();
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
        String sourceSnapshot = product == null ? null : product.getPrimaryFnclty();
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
                JudgeProductDetails.request(product, productType, approvedFunctions), adText);
        String status = "PENDING";
        String submittedAt = OffsetDateTime.now().toString();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("request", new InspectionJobRequest(ids, request.productTypeAnswer(), adText));
        result.put("productInfoSource", source);
        result.put("appliedProductType", switch (productType) {
            case "HEALTH_FUNCTIONAL" -> "HEALTH_FUNCTIONAL";
            case "GENERAL_FOOD" -> "GENERAL";
            default -> "UNDETERMINED";
        });
        result.put("layer1", null);
        result.put("layer2", null);
        result.put("provisionalStep1", step1);
        result.put("judgeRequestId", judgeRequestId);
        result.put("judgeResponse", null);
        result.put("createdAt", submittedAt);
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("reportNo", product == null ? null : product.getPrdlstReportNo());
        snapshot.put("productType", productType);
        snapshot.put("primaryFnclty", sourceSnapshot);
        snapshot.put("approvedFunctions", List.copyOf(approvedFunctions));
        snapshot.put("version", productVersion);
        snapshot.put("judgeProduct", judgeRequest.product());
        snapshot.put("judgeProductSchemaVersion", 1);
        result.put("productSnapshot", snapshot);
        result.put("productDataReviewRequired", product != null && product.isFunctionSourceReviewRequired());
        result.put("previousJobId", previous == null ? null : previous.getId().toString());
        if (selectedRecommendation != null) {
            result.put("recommendationValidation", Map.of("sourceJobId", previous.getId().toString(),
                    "recommendationId", selectedRecommendation.id(), "status", "PENDING", "message", "수정안 검사를 기다리고 있습니다."));
        }
        if (!sentenceEdits.isEmpty()) result.put("sentenceEditMappings", sentenceEdits);
        if (previous != null) {
            result.put("comparison", ReinspectionComparison.compare(previous, result, status));
        }

        InspectionRecord record = new InspectionRecord(
                Long.valueOf(jwt.getSubject()),
                product == null ? null : product.getPrdlstReportNo(),
                product == null ? null : product.getPrdlstNm(),
                product == null ? null : product.getBsshNm(),
                koreanType,
                sourceSnapshot,
                adText, status, serialize(result), LocalDateTime.now());
        if (previous != null) record.linkToPreviousInspection(previous.getId());
        InspectionRecord saved = inspectionJobQueue.enqueue(record, judgeRequest, selectedRecommendation);
        return previous == null ? Map.of("jobId", saved.getId().toString())
                : Map.of("jobId", saved.getId().toString(), "previousJobId", previous.getId().toString());
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
            Map<String, Object> result = objectMapper.readValue(record.getResultJson(), new TypeReference<>() {
            });
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

    @GetMapping("/{jobId}/recommendations")
    public Map<String, Object> recommendations(@AuthenticationPrincipal Jwt jwt, @PathVariable Long jobId) {
        InspectionRecord record = owned(jwt, jobId);
        Map<String, Object> result = freshRecommendationSource(record);
        return Map.of("jobId", jobId.toString(), "items", InspectionRecommendations.drafts(record, result));
    }

    @PostMapping("/{jobId}/recommendations/{findingId}/validate")
    public Map<String, String> validateRecommendation(@AuthenticationPrincipal Jwt jwt, @PathVariable Long jobId,
                                                      @PathVariable String findingId) {
        InspectionRecord previous = owned(jwt, jobId);
        Map<String, Object> result = freshRecommendationSource(previous);
        var draft = InspectionRecommendations.drafts(previous, result).stream().filter(d -> d.id().equals(findingId))
                .findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "수정안을 찾을 수 없습니다."));
        InspectionJobRequest original = objectMapper.convertValue(result.get("request"), InspectionJobRequest.class);
        if (original == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "이전 검사 입력을 복원할 수 없습니다.");
        return createInternal(jwt, new InspectionJobRequest(original.productIds(), original.productTypeAnswer(),
                InspectionRecommendations.apply(previous.getAdText(), draft)), previous, draft);
    }

    private InspectionRecord owned(Jwt jwt, Long jobId) {
        return inspectionRecordRepository.findByIdAndUserId(jobId, Long.valueOf(jwt.getSubject()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "검수 기록을 찾을 수 없습니다."));
    }

    private Map<String, Object> freshRecommendationSource(InspectionRecord record) {
        if (!"DONE".equals(record.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "검사가 완료된 뒤 수정안을 확인할 수 있습니다.");
        }
        Product current = record.getPrdlstReportNo() == null ? null
                : productRepository.findById(record.getPrdlstReportNo()).orElse(null);
        try {
            Map<String, Object> result = objectMapper.readValue(record.getResultJson(), new TypeReference<>() { });
            var snapshot = objectMapper.valueToTree(result.get("productSnapshot"));
            if (current == null || current.isFunctionSourceReviewRequired() || snapshot == null
                    || !snapshot.hasNonNull("version") || current.getVersion() == null
                    || !snapshot.path("version").isIntegralNumber()
                    || snapshot.path("version").longValue() != current.getVersion()
                    || !snapshot.path("primaryFnclty").equals(objectMapper.valueToTree(current.getPrimaryFnclty()))) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "제품 정보가 미확인·변경된 상태입니다. 현재 광고를 먼저 재검수해 주세요.");
            }
            return result;
        } catch (JsonProcessingException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "검수 정보를 읽을 수 없습니다.", e);
        }
    }

    // @AuthenticationPrincipal --> Spring Security 필터가 이미 서명과 만료 검사완료.
    // 이 어노테이션은 그 검증이 끝난 토큰 객체를 받아 오는 것
    @GetMapping
    public InspectionHistoryResponse history(@AuthenticationPrincipal Jwt jwt,
                                             @RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "20") int size) {

        String subject = jwt.getSubject();
        assert subject != null;
        return inspectionHistoryService.getMyHistory(Long.valueOf(subject), page, size);
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
