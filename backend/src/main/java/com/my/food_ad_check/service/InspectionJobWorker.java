package com.my.food_ad_check.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.my.food_ad_check.dto.FastApiRequest;
import com.my.food_ad_check.dto.FastApiResponse;
import com.my.food_ad_check.entity.InspectionRecord;
import com.my.food_ad_check.repository.InspectionRecordRepository;
import com.my.food_ad_check.repository.ProductRepository;
import java.time.OffsetDateTime;
import java.util.Map;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** One claimed job per invocation; HTTP/JPA transactions are not held during the judge call. */
@Service
public class InspectionJobWorker {
    private final InspectionJobQueue queue;
    private final InspectionRecordRepository records;
    private final ProductRepository products;
    private final AiJudgmentService judge;
    private final ObjectMapper json = new ObjectMapper();

    public InspectionJobWorker(InspectionJobQueue queue, InspectionRecordRepository records,
                               ProductRepository products, AiJudgmentService judge) {
        this.queue = queue;
        this.records = records;
        this.products = products;
        this.judge = judge;
    }

    public boolean processOne() {
        queue.recoverExpired();
        var candidate = queue.claim();
        if (candidate.isEmpty()) return false;
        var claim = candidate.get();
        InspectionRecord record = records.findById(claim.jobId()).orElse(null);
        if (record == null) return true; // account deletion cascaded the queue row
        Map<String, Object> result;
        try { result = json.readValue(record.getResultJson(), new TypeReference<>() { }); }
        catch (Exception invalid) { throw new IllegalStateException("Invalid persisted inspection input", invalid); }
        FastApiResponse response = null;
        JsonNode imageResult = null;
        String status = "FAILED";
        try {
            if (!currentProduct(record, result)) {
                failure(result, "PRODUCT_CHANGED", "대기 중 제품 정보가 변경됐습니다. 최신 정보로 다시 검사해 주세요.");
            } else {
                FastApiRequest request = json.readValue(claim.requestJson(), FastApiRequest.class);
                if ("IMAGE".equals(result.get("inspectionKind"))) {
                    var images = queue.images(claim.jobId());
                    if (images.isEmpty()) throw new IllegalStateException("Missing queued images");
                    var outcome = judge.requestImageJudgmentOutcome(request, images);
                    status = outcome.status();
                    response = outcome.response();
                    imageResult = outcome.imageResult();
                    if (outcome.failureCode() != null) {
                        failure(result, outcome.failureCode(), imageFailureMessage(outcome.failureCode()));
                    }
                } else {
                    InspectionInputValidator.validateText(request.adText());
                    var outcome = judge.requestJudgmentOutcome(request);
                    if (outcome != null && java.util.Set.of("DONE", "FAILED", "TIMEOUT").contains(outcome.status())) {
                        status = outcome.status();
                        response = outcome.response();
                        if ("DONE".equals(status) && response == null) status = "FAILED";
                    }
                }
                if (!currentProduct(record, result)) {
                    status = "FAILED";
                    response = null;
                    failure(result, "PRODUCT_CHANGED", "검사 중 제품 정보가 변경됐습니다. 최신 정보로 다시 검사해 주세요.");
                }
            }
        } catch (InspectionInputException invalid) {
            response = null;
            status = "FAILED";
            result.put("layer1", invalid.validation());
            failure(result, invalid.code(), invalid.getReason());
        } catch (Exception failure) {
            response = null;
            status = "FAILED";
            failure(result, "PROCESSING_FAILED", "검사 처리에 실패했습니다. 잠시 후 다시 검사해 주세요.");
            LoggerFactory.getLogger(getClass()).warn("검사 작업 실패: jobId={}, cause={}",
                    claim.jobId(), failure.getClass().getSimpleName());
        }
        String completedAt = OffsetDateTime.now().toString();
        if (!"DONE".equals(status)) imageResult = null;
        if (imageResult != null) {
            ObjectNode original = json.valueToTree(result.get("request"));
            original.put("adText", imageResult.path("adText").asText());
            result.put("request", original);
        }
        result.put("completedAt", completedAt);
        result.put("judgeResponse", response);
        result.put("layer2", response == null ? null : InspectionResultMapper.toLayer2(response, completedAt));
        if (record.getPreviousInspectionId() != null) {
            if (claim.recommendationJson() != null) {
                try {
                    var draft = json.readValue(claim.recommendationJson(), InspectionRecommendations.Draft.class);
                    result.put("recommendationValidation", InspectionRecommendations.evaluate(
                            record.getPreviousInspectionId(), draft, response, status));
                } catch (Exception invalid) {
                    throw new IllegalStateException("Invalid persisted recommendation", invalid);
                }
            }
            var previous = records.findByIdAndUserId(record.getPreviousInspectionId(), record.getUserId()).orElse(null);
            if (previous != null) result.put("comparison", ReinspectionComparison.compare(previous, result, status));
        }
        if (imageResult == null) queue.finish(claim, status, result);
        else queue.finish(claim, status, result, imageResult);
        return true;
    }

    private boolean currentProduct(InspectionRecord record, Map<String, Object> result) {
        if (record.getPrdlstReportNo() == null) return true;
        var version = json.valueToTree(result.get("productSnapshot")).path("version");
        return version.isIntegralNumber() && products.findCurrentVersion(record.getPrdlstReportNo())
                .filter(v -> v == version.longValue()).isPresent();
    }

    private static void failure(Map<String, Object> result, String code, String message) {
        result.put("failureCode", code);
        result.put("failureMessage", message);
    }

    private static String imageFailureMessage(String code) {
        return switch (code) {
            case "IMAGE_NO_AD_TEXT" -> "이미지에서 검사할 광고 문구를 읽지 못했습니다. 글자가 잘 보이는 이미지로 다시 검사해 주세요.";
            case "IMAGE_REJECTED" -> "이미지를 판정 서버에서 읽지 못했습니다. 파일을 확인해 주세요.";
            case "IMAGE_TIMEOUT" -> "이미지 판정 시간이 초과됐습니다. 이미지 수를 줄여 다시 검사해 주세요.";
            default -> "이미지 판정을 완료하지 못했습니다. 잠시 후 다시 검사해 주세요.";
        };
    }
}
