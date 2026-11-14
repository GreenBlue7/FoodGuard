package com.my.food_ad_check.service;

import com.my.food_ad_check.dto.FastApiResponse;
import com.my.food_ad_check.entity.InspectionRecord;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class InspectionRecommendationsTests {
    private FastApiResponse.Result result(String id, String text, String status, String revision) {
        return new FastApiResponse.Result(List.of(id), text, status, null, "reason", List.of(), null, revision);
    }

    @Test
    void replacesWholeSentenceUsingCodePointsAndIgnoresNormalSentences() {
        String ad = "😀 안내. 위험 표현.";
        var record = mock(InspectionRecord.class);
        when(record.getAdText()).thenReturn(ad);
        var response = new FastApiResponse(List.of(new FastApiResponse.Sentence("s1", "😀 안내.", 0, 5),
                new FastApiResponse.Sentence("s2", "위험 표현.", 6, 12)),
                List.of(result("s1", "😀 안내.", "OK", "불필요한 제안"),
                        result("s2", "위험 표현.", "VIOLATION", "제품 설명.")), "mock", "date", 1L);
        var drafts = InspectionRecommendations.drafts(record, Map.of("judgeResponse", response));
        assertEquals(1, drafts.size());
        assertEquals("UNVERIFIED", drafts.get(0).validationStatus());
        assertEquals("😀 안내. 제품 설명.", InspectionRecommendations.apply(ad, drafts.get(0)));
    }

    @Test
    void passesOnlyTheReplacedSentencesAndKeepsUnrelatedIssuesInTheFullResult() {
        var draft = new InspectionRecommendations.Draft("finding-0", "위험.", "수정.", 0, 3, "UNVERIFIED");
        var response = new FastApiResponse(List.of(new FastApiResponse.Sentence("s1", "수정.", 0, 3),
                new FastApiResponse.Sentence("s2", "다른 위험.", 4, 10)),
                List.of(result("s1", "수정.", "OK", null), result("s2", "다른 위험.", "VIOLATION", null)), "mock", "date", 1L);
        assertEquals("PASSED_THIS_CHECK", InspectionRecommendations.evaluate(1L, draft, response, "DONE").status());
        assertEquals("REVIEW_REQUIRED", InspectionRecommendations.evaluate(1L, draft, response, "TIMEOUT").status());
    }

    @Test
    void missingVerdictOrUncoveredReplacementNeverPasses() {
        var draft = new InspectionRecommendations.Draft("finding-0", "위험.", "수정.", 0, 3, "UNVERIFIED");
        var missing = new FastApiResponse(List.of(new FastApiResponse.Sentence("s1", "수정.", 0, 3)),
                List.of(), "mock", "date", 1L);
        assertEquals("REVIEW_REQUIRED", InspectionRecommendations.evaluate(1L, draft, missing, "DONE").status());
        var wrong = new FastApiResponse(List.of(new FastApiResponse.Sentence("s1", "누락.", 0, 3)),
                List.of(result("s1", "누락.", "OK", null)), "mock", "date", 1L);
        assertEquals("REVIEW_REQUIRED", InspectionRecommendations.evaluate(1L, draft, wrong, "DONE").status());
    }
}
