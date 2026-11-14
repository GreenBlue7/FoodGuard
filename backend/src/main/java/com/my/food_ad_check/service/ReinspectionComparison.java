package com.my.food_ad_check.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.my.food_ad_check.entity.InspectionRecord;
import java.util.LinkedHashMap;
import java.util.Map;

/** Combines count summaries with conservative, source-grounded sentence matching. */
public final class ReinspectionComparison {
    private static final ObjectMapper JSON = new ObjectMapper();
    private ReinspectionComparison() { }

    public static Map<String, Object> compare(InspectionRecord previous, Map<String, Object> current, String status) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("previousJobId", previous.getId().toString());
        result.put("bothCompleted", false);
        result.put("sameRecordedCriteria", false);
        result.put("previousCounts", null);
        result.put("currentCounts", null);
        result.put("sentenceComparisonAvailable", false);
        result.put("sentenceChanges", java.util.List.of());
        result.put("sentenceSummary", null);
        try {
            JsonNode before = JSON.readTree(previous.getResultJson());
            JsonNode after = JSON.readTree(JSON.writeValueAsString(current));
            if (before == null || !before.isObject()) throw new IllegalArgumentException("Invalid previous result");
            boolean complete = "DONE".equals(previous.getStatus()) && "DONE".equals(status);
            JsonNode snapshot = before.path("productSnapshot");
            boolean knownSnapshot = snapshot.isObject() && snapshot.has("reportNo") && snapshot.has("primaryFnclty")
                    && snapshot.path("approvedFunctions").isArray() && !snapshot.path("productType").asText("").isBlank();
            boolean sameCriteria = knownSnapshot
                    && !before.path("productDataReviewRequired").asBoolean(false)
                    && !after.path("productDataReviewRequired").asBoolean(false)
                    && !before.path("judgeResponse").path("model").asText("").isBlank()
                    && !before.path("judgeResponse").path("kbVersion").asText("").isBlank()
                    && before.path("productSnapshot").equals(after.path("productSnapshot"))
                    && before.path("judgeResponse").path("model").equals(after.path("judgeResponse").path("model"))
                    && before.path("judgeResponse").path("kbVersion").equals(after.path("judgeResponse").path("kbVersion"));
            result.put("bothCompleted", complete);
            result.put("sameRecordedCriteria", sameCriteria);
            result.put("productDataReviewRequired", after.path("productDataReviewRequired").asBoolean(false));
            result.put("previousCounts", complete ? counts(before) : null);
            result.put("currentCounts", complete ? counts(after) : null);
            if (complete) {
                result.putAll(SentenceComparison.compare(before, previous.getAdText(), after, sameCriteria, previous.getId().toString()));
            } else {
                result.put("sentenceComparisonNote", "두 검사가 모두 완료된 뒤 문장별 비교를 제공할 수 있습니다.");
            }
            result.put("notice", "수정 전후 판정과 문장 변화를 비교합니다. 삭제·건수 감소·유사 문장 연결만으로 문제 해결을 확정하지 않습니다."
                    + (sameCriteria ? "" : " 제품 정보·모델·사례 기준이 달라졌거나 이전 기준을 확인할 수 없습니다.")
                    + (after.path("productDataReviewRequired").asBoolean(false)
                    ? " 최신 원문에서 기능성 목록을 추출하지 못해 결과를 확정할 수 없습니다." : ""));
        } catch (JsonProcessingException | IllegalArgumentException e) {
            // A malformed historical record must not fabricate a successful comparison.
            result.put("bothCompleted", false);
            result.put("sameRecordedCriteria", false);
            result.put("notice", "이전 결과를 읽을 수 없어 비교하지 못했습니다.");
        }
        return result;
    }

    private static Map<String, Long> counts(JsonNode job) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String status : new String[]{"VIOLATION", "REVIEW", "HOLD", "OK"}) counts.put(status, 0L);
        for (JsonNode item : job.path("judgeResponse").path("results")) {
            String status = item.path("status").asText();
            if (counts.containsKey(status)) counts.put(status, counts.get(status) + 1);
        }
        return counts;
    }
}
