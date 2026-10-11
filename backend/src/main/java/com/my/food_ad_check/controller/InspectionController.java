package com.my.food_ad_check.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.my.food_ad_check.dto.FastApiRequest;
import com.my.food_ad_check.dto.FastApiResponse;
import com.my.food_ad_check.dto.InspectionRequest;
import com.my.food_ad_check.entity.InspectionRecord;
import com.my.food_ad_check.entity.Product;
import com.my.food_ad_check.repository.InspectionRecordRepository;
import com.my.food_ad_check.repository.ProductRepository;
import com.my.food_ad_check.service.AiJudgmentService;
import com.my.food_ad_check.service.ProductApiService;
import com.my.food_ad_check.service.RuleCheckService;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/checks")
public class InspectionController {

    private static final String HEALTH_FUNCTIONAL_FOOD = "건강기능식품";
    private static final String GENERAL_FOOD = "일반식품";

    private final ProductRepository productRepository;
    private final InspectionRecordRepository inspectionRecordRepository;
    private final ProductApiService productApiService;
    private final AiJudgmentService aiJudgmentService;
    private final RuleCheckService ruleCheckService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public InspectionController(ProductRepository productRepository,
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

    @PostMapping("/full-process")
    public Map<String, Object> processInspection(@RequestBody InspectionRequest request) {
        String adText = request.adText() == null ? "" : request.adText().trim();
        if (adText.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "검수할 광고 문구를 입력해 주세요.");
        }

        Product product = null;
        if (hasText(request.prdlstReportNo())) {
            product = productRepository.findById(request.prdlstReportNo().trim())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                            "제품 신고번호를 찾을 수 없습니다. 제품을 다시 검색해 주세요."));
        }

        String productName = product != null ? product.getPrdlstNm() : trimToNull(request.prdlstNm());
        String manufacturerName = product != null ? product.getBsshNm() : trimToNull(request.bsshNm());
        String productType = product != null ? product.getProductType() : trimToNull(request.productType());
        String primaryFunctionality = product != null
                ? product.getPrimaryFnclty() : trimToNull(request.primaryFnclty());
        String reportNo = product != null ? product.getPrdlstReportNo() : null;
        boolean productTypeConfirmed = HEALTH_FUNCTIONAL_FOOD.equals(productType)
                || GENERAL_FOOD.equals(productType);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", productTypeConfirmed ? "COMPLETED" : "REVIEW_REQUIRED");
        response.put("productInfo", productInfo(reportNo, productName, manufacturerName, productType,
                primaryFunctionality, productTypeConfirmed));

        List<String> missingItems = productTypeConfirmed
                ? ruleCheckService.checkMandatoryPhrases(productType, adText)
                : List.of();
        Map<String, Object> step1 = new LinkedHashMap<>();
        step1.put("status", productTypeConfirmed ? (missingItems.isEmpty() ? "PASS" : "FAIL") : "REVIEW");
        step1.put("missingItems", missingItems);
        response.put("step1", step1);

        if (productTypeConfirmed) {
            List<String> keywords = List.of();
            if (HEALTH_FUNCTIONAL_FOOD.equals(productType)) {
                keywords = product == null ? List.of() : product.getApprovedFunctions();
                if (keywords.isEmpty()) {
                    keywords = productApiService.parsePrimaryFnclty(primaryFunctionality);
                }
            }
            String judgeProductType = HEALTH_FUNCTIONAL_FOOD.equals(productType)
                    ? "HEALTH_FUNCTIONAL" : "GENERAL_FOOD";
            String judgeRequestId = UUID.randomUUID().toString();
            response.put("judgeRequestId", judgeRequestId);
            FastApiRequest aiRequest = new FastApiRequest(judgeRequestId, "C",
                    new FastApiRequest.Product(judgeProductType,
                            productName == null ? List.of() : List.of(productName), keywords), adText);
            FastApiResponse aiResponse = aiJudgmentService.requestJudgment(aiRequest);
            response.put("step2", aiResponse == null ? judgmentFailure() : aiResponse);
            if (aiResponse == null) {
                response.put("status", "PARTIAL");
            }
        } else {
            response.put("step2", Map.of("status", "SKIPPED",
                    "result", "제품 유형을 확인하지 못해 문구만으로 판정을 진행하지 않았습니다."));
        }

        LocalDateTime createdAt = LocalDateTime.now();
        InspectionRecord saved = inspectionRecordRepository.save(new InspectionRecord(
                reportNo, productName, manufacturerName, productType, primaryFunctionality, adText,
                String.valueOf(response.get("status")), serialize(response), createdAt));
        response.put("inspectionId", saved.getId());
        response.put("createdAt", createdAt);
        return response;
    }

    @GetMapping("/{id}")
    public Map<String, Object> getInspection(@PathVariable Long id) {
        InspectionRecord record = inspectionRecordRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "검수 기록을 찾을 수 없습니다."));
        try {
            Map<String, Object> result = objectMapper.readValue(record.getResultJson(),
                    new TypeReference<>() { });
            result.put("inspectionId", record.getId());
            result.put("createdAt", record.getCreatedAt());
            result.put("status", record.getStatus());
            return result;
        } catch (JsonProcessingException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "저장된 검수 결과를 읽을 수 없습니다.", e);
        }
    }

    private Map<String, Object> productInfo(String reportNo, String name, String manufacturer, String type,
                                             String functionality, boolean confirmed) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("source", confirmed ? (reportNo == null ? "SELLER_INPUT" : "MFDS_DATABASE") : "UNCONFIRMED");
        info.put("prdlstReportNo", reportNo);
        info.put("prdlstNm", name);
        info.put("bsshNm", manufacturer);
        info.put("productType", type);
        info.put("primaryFnclty", functionality);
        return info;
    }

    private Map<String, Object> judgmentFailure() {
        return Map.of("status", "ERROR", "result", "판정 서비스를 사용할 수 없습니다. 잠시 후 다시 시도해 주세요.");
    }

    private String serialize(Map<String, Object> response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "검수 결과를 저장할 수 없습니다.", e);
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static String trimToNull(String value) {
        return hasText(value) ? value.trim() : null;
    }
}
