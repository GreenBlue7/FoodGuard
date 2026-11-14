package com.my.food_ad_check.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.my.food_ad_check.dto.FastApiResponse;
import com.my.food_ad_check.entity.InspectionRecord;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Drafts replace complete source sentences, never the shorter problem-text highlight. */
public final class InspectionRecommendations {
    private static final ObjectMapper JSON = new ObjectMapper();
    private InspectionRecommendations() { }
    public record Draft(String id, String originalText, String suggestedText, int start, int end,
                        String validationStatus) { }
    public record Validation(String sourceJobId, String recommendationId, String status, String message) { }

    public static List<Draft> drafts(InspectionRecord record, Map<String, Object> result) {
        FastApiResponse response = JSON.convertValue(result.get("judgeResponse"), FastApiResponse.class);
        if (response == null || response.results() == null || response.sentences() == null) return List.of();
        List<Draft> drafts = new ArrayList<>();
        for (int index = 0; index < response.results().size(); index++) {
            var item = response.results().get(index);
            if (item == null || !("VIOLATION".equals(item.status()) || "REVIEW".equals(item.status())) || item.revision() == null
                    || item.revision().isBlank() || item.sentenceIds() == null || item.sentenceIds().size() != 1) continue;
            var sentence = response.sentences().stream()
                    .filter(s -> s.id().equals(item.sentenceIds().get(0))).findFirst().orElse(null);
            if (sentence == null || sentence.start() == null || sentence.end() == null || sentence.start() < 0
                    || sentence.end() <= sentence.start()
                    || sentence.end() > record.getAdText().codePointCount(0, record.getAdText().length())) continue;
            int start = record.getAdText().offsetByCodePoints(0, sentence.start());
            int end = record.getAdText().offsetByCodePoints(0, sentence.end());
            String revision = item.revision().trim();
            if (!Objects.equals(sentence.text(), record.getAdText().substring(start, end)) || revision.equals(sentence.text())) continue;
            drafts.add(new Draft("finding-" + index, sentence.text(), revision, sentence.start(), sentence.end(), "UNVERIFIED"));
        }
        return List.copyOf(drafts);
    }

    public static String apply(String adText, Draft draft) {
        return adText.substring(0, adText.offsetByCodePoints(0, draft.start())) + draft.suggestedText()
                + adText.substring(adText.offsetByCodePoints(0, draft.end()));
    }

    public static Validation evaluate(Long sourceId, Draft draft, FastApiResponse response, String jobStatus) {
        boolean passed = false;
        if ("DONE".equals(jobStatus) && response != null && response.sentences() != null && response.results() != null) {
            int replacementEnd = draft.start() + draft.suggestedText().codePointCount(0, draft.suggestedText().length());
            var targets = response.sentences().stream()
                    .filter(s -> s.start() < replacementEnd && s.end() > draft.start()).toList();
            String coveredText = targets.stream().map(FastApiResponse.Sentence::text).reduce("", String::concat);
            passed = !targets.isEmpty() && squash(coveredText).equals(squash(draft.suggestedText()))
                    && targets.stream().allMatch(s -> {
                        var verdicts = response.results().stream()
                                .filter(r -> r.sentenceIds() != null && r.sentenceIds().contains(s.id())).toList();
                        return !verdicts.isEmpty() && verdicts.stream().allMatch(r -> "OK".equals(r.status()));
                    });
        }
        return new Validation(sourceId.toString(), draft.id(), passed ? "PASSED_THIS_CHECK" : "REVIEW_REQUIRED",
                passed ? "수정안을 적용한 광고 전체를 다시 검사했고, 교체한 문장은 이번 검사에서 문제없음으로 나왔습니다. 최종 법적 판단은 아닙니다."
                : "수정안의 문제가 해결됐다고 확인하지 못했습니다. 재검수 결과를 확인해주세요.");
    }

    private static String squash(String text) { return text.replaceAll("\\s+", ""); }
}
