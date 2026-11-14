package com.my.food_ad_check.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.my.food_ad_check.entity.InspectionRecord;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SentenceComparisonTests {
    private static final ObjectMapper JSON = new ObjectMapper();

    private Map<String, Object> job(List<String> texts, List<String> statuses) {
        List<Map<String, Object>> sentences = new ArrayList<>(), verdicts = new ArrayList<>();
        int offset = 0;
        for (int i = 0; i < texts.size(); i++) {
            String text = texts.get(i), id = "s" + (i + 1);
            int end = offset + text.codePointCount(0, text.length());
            sentences.add(Map.of("id", id, "text", text, "start", offset, "end", end));
            verdicts.add(Map.of("sentenceIds", List.of(id), "text", text, "status", statuses.get(i),
                    "reason", "test reason", "violationType", statuses.get(i).equals("OK") ? "" : "test article"));
            offset = end + 1;
        }
        var job = new LinkedHashMap<String, Object>();
        job.put("request", Map.of("adText", String.join("\n", texts), "productIds", List.of("R1")));
        job.put("productSnapshot", Map.of("reportNo", "R1", "productType", "HEALTH_FUNCTIONAL", "primaryFnclty", "source",
                "approvedFunctions", List.of("function"), "version", 1L));
        job.put("judgeResponse", new LinkedHashMap<>(Map.of("sentences", sentences, "results", verdicts,
                "model", "test-model", "kbVersion", "2026-11-12")));
        return job;
    }
    private String ad(Map<String, Object> job) { return JSON.valueToTree(job).path("request").path("adText").asText(); }
    private JsonNode compare(Map<String, Object> old, Map<String, Object> current) throws Exception {
        var record = mock(InspectionRecord.class);
        when(record.getId()).thenReturn(1L);
        when(record.getStatus()).thenReturn("DONE");
        when(record.getAdText()).thenReturn(ad(old));
        when(record.getResultJson()).thenReturn(JSON.writeValueAsString(old));
        return JSON.valueToTree(ReinspectionComparison.compare(record, current, "DONE"));
    }
    private Map<String, Object> single(String text, String status) { return job(List.of(text), List.of(status)); }
    private void explicitEdit(Map<String, Object> current, String previousId) {
        current.put("sentenceEditMappings", List.of(new SentenceComparison.Edit(previousId, 0,
                ad(current).codePointCount(0, ad(current).length()))));
    }

    @Test
    void explicitEditReportsResolutionOnlyAfterCurrentOkVerdict() throws Exception {
        var old = single("감기를 예방합니다.", "VIOLATION");
        var current = single("원료를 안내합니다.", "OK");
        explicitEdit(current, "s1");
        var result = compare(old, current);
        assertTrue(result.path("sameRecordedCriteria").asBoolean());
        assertEquals("MODIFIED", result.at("/sentenceChanges/0/changeType").asText());
        assertEquals("RESOLVED_THIS_CHECK", result.at("/sentenceChanges/0/issueChange").asText());
        assertEquals(1, result.at("/sentenceSummary/resolvedThisCheck").asInt());
    }

    @Test
    void changedSentenceStillRequiringReviewIsNotResolved() throws Exception {
        var current = single("수정했으나 추가 확인 필요.", "REVIEW");
        explicitEdit(current, "s1");
        var result = compare(single("원래 위험 문장.", "VIOLATION"), current);
        assertEquals("REMAINING_ISSUE", result.at("/sentenceChanges/0/issueChange").asText());
        assertEquals(0, result.at("/sentenceSummary/resolvedThisCheck").asInt());
    }

    @Test
    void deletionIsNotCountedAsResolution() throws Exception {
        var result = compare(job(List.of("위험 문장.", "보관 안내."), List.of("VIOLATION", "OK")), single("보관 안내.", "OK"));
        assertEquals("REMOVED", result.at("/sentenceChanges/0/changeType").asText());
        assertEquals("REMOVED_NOT_ASSESSED", result.at("/sentenceChanges/0/issueChange").asText());
        assertEquals(0, result.at("/sentenceSummary/resolvedThisCheck").asInt());
    }

    @Test
    void addedUnsafeSentenceIsANewIssue() throws Exception {
        var result = compare(single("보관 안내.", "OK"), job(List.of("보관 안내.", "새로운 위험."), List.of("OK", "VIOLATION")));
        assertEquals(1, result.at("/sentenceSummary/newIssues").asInt());
        assertEquals(1, result.at("/sentenceSummary/added").asInt());
    }

    @Test
    void movedSentencesMatchByTextRatherThanIndex() throws Exception {
        var result = compare(job(List.of("첫 안내.", "두번째 안내."), List.of("OK", "OK")),
                job(List.of("두번째 안내.", "첫 안내."), List.of("OK", "OK")));
        assertEquals(2, result.at("/sentenceSummary/unchanged").asInt());
        assertEquals("s2", result.at("/sentenceChanges/0/after/id").asText());
    }

    @Test
    void repeatedSentencesAreNotAssignedFalseCertainty() throws Exception {
        var result = compare(job(List.of("같은 광고.", "같은 광고."), List.of("VIOLATION", "OK")), single("같은 광고.", "OK"));
        assertFalse(result.at("/sentenceChanges/0/mappingCertain").asBoolean());
        assertEquals("MAPPING_REQUIRES_CONFIRMATION", result.at("/sentenceChanges/0/issueChange").asText());
        assertEquals(0, result.at("/sentenceSummary/resolvedThisCheck").asInt());
    }

    @Test
    void unchangedTextWithDifferentVerdictIsFlaggedInsteadOfClaimingFix() throws Exception {
        var result = compare(single("같은 광고.", "VIOLATION"), single("같은 광고.", "OK"));
        assertEquals("UNCHANGED_VERDICT_CHANGED", result.at("/sentenceChanges/0/issueChange").asText());
        assertEquals(1, result.at("/sentenceSummary/unchangedVerdictChanges").asInt());
        assertEquals(0, result.at("/sentenceSummary/resolvedThisCheck").asInt());
    }

    @Test
    void changedCriteriaPreventsResolutionClaims() throws Exception {
        var current = single("새 문구.", "OK");
        explicitEdit(current, "s1");
        @SuppressWarnings("unchecked") var response = (Map<String, Object>) current.get("judgeResponse");
        response.put("model", "different-model");
        var result = compare(single("옛 문구.", "VIOLATION"), current);
        assertEquals("CRITERIA_CHANGED_OR_UNKNOWN", result.at("/sentenceChanges/0/issueChange").asText());
        assertEquals(0, result.at("/sentenceSummary/resolvedThisCheck").asInt());
    }

    @Test
    void missingVerdictDoesNotBecomeAnOkResult() throws Exception {
        var old = single("원래 광고.", "VIOLATION");
        @SuppressWarnings("unchecked") var response = (Map<String, Object>) old.get("judgeResponse");
        response.put("results", List.of());
        var result = compare(old, single("원래 광고.", "OK"));
        assertEquals("UNKNOWN", result.at("/sentenceChanges/0/before/verdict").asText());
        assertEquals("RESULT_UNAVAILABLE", result.at("/sentenceChanges/0/issueChange").asText());
    }

    @Test
    void similarSentencesAreTentativeUntilEditingIntentIsKnown() throws Exception {
        var result = compare(single("면역력 증진에 도움을 줄 수 있습니다.", "VIOLATION"),
                single("면역력 증진에 도움을 줄 수도 있습니다.", "OK"));
        assertEquals("SIMILAR_TEXT", result.at("/sentenceChanges/0/mappingMethod").asText());
        assertFalse(result.at("/sentenceChanges/0/mappingCertain").asBoolean());
        assertEquals(0, result.at("/sentenceSummary/resolvedThisCheck").asInt());
    }

    @Test
    void sourceOffsetsUseUnicodeCodePoints() throws Exception {
        var current = single("😀 새 안내.", "OK");
        explicitEdit(current, "s1");
        var result = compare(single("😀 옛 안내.", "VIOLATION"), current);
        assertTrue(result.path("sentenceComparisonAvailable").asBoolean());
        assertEquals(ad(current).codePointCount(0, ad(current).length()), result.at("/sentenceChanges/0/after/range/end").asInt());
    }

    @Test
    void incompleteSentenceCoverageSuppressesComparison() throws Exception {
        var current = single("앞 문장.", "OK");
        current.put("request", Map.of("adText", "앞 문장. 빠진 문장."));
        var result = compare(single("원래 광고.", "VIOLATION"), current);
        assertFalse(result.path("sentenceComparisonAvailable").asBoolean());
        assertTrue(result.path("sentenceChanges").isEmpty());
    }

    @Test
    void invalidOrOverlappingEditRangesAreRejected() {
        var old = JSON.valueToTree(job(List.of("이전 하나.", "이전 둘."), List.of("VIOLATION", "OK")));
        assertThrows(IllegalArgumentException.class, () -> SentenceComparison.validateEdits(old, "이전 하나.\n이전 둘.", "현재 광고.",
                List.of(new SentenceComparison.Edit("missing", 0, 3))));
        assertThrows(IllegalArgumentException.class, () -> SentenceComparison.validateEdits(old, "이전 하나.\n이전 둘.", "현재 광고.",
                List.of(new SentenceComparison.Edit("s1", 0, 4), new SentenceComparison.Edit("s2", 2, 5))));
    }

    @Test
    void editHintMustCoverAnActualCurrentSentenceToConfirmMapping() throws Exception {
        var current = single("완전히 다른 광고 문장.", "OK");
        current.put("sentenceEditMappings", List.of(new SentenceComparison.Edit("s1", 0, 2)));
        var result = compare(single("과거 위험.", "VIOLATION"), current);
        assertEquals(1, result.path("editHintsNotMatched").asInt());
        assertEquals(0, result.at("/sentenceSummary/resolvedThisCheck").asInt());
    }

    @Test
    void corruptHistoricalResultDoesNotCreateEmptySuccessfulComparison() {
        var record = mock(InspectionRecord.class);
        when(record.getId()).thenReturn(1L);
        when(record.getResultJson()).thenReturn("null");
        var result = ReinspectionComparison.compare(record, single("광고.", "OK"), "DONE");
        assertEquals(false, result.get("bothCompleted"));
        assertNull(result.get("sentenceSummary"));
    }
}
