package com.my.food_ad_check.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.my.food_ad_check.dto.FastApiResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InspectionResultMapperTests {

    @Test
    void mapsCurrentJudgeFieldsWithoutInventingMissingFields() {
        FastApiResponse response = new FastApiResponse(
                List.of(new FastApiResponse.Sentence("s1", "감기 예방", 0, 5)),
                List.of(new FastApiResponse.Result(List.of("s1"), "감기 예방", "VIOLATION",
                        "제8조 제1항 제1호", "질병 예방 표현입니다.",
                        List.of(new FastApiResponse.Evidence("mfds-1",
                                "'감기 예방' 적발 (질병, 식약처 2025-03-01)",
                                "https://example.com/case")),
                        new FastApiResponse.Span(0, 5), null)),
                "gemini-test", "2025-03-01", 10L);

        Map<String, Object> layer2 = InspectionResultMapper.toLayer2(response,
                "2026-10-13T12:00:00+09:00");

        assertEquals("2025-03-01", layer2.get("dataAsOf"));
        Map<?, ?> sentence = (Map<?, ?>) ((List<?>) layer2.get("sentences")).get(0);
        assertEquals(0, sentence.get("index"));
        assertEquals(Map.of("start", 0, "end", 5), sentence.get("range"));
        Map<?, ?> finding = (Map<?, ?>) ((List<?>) layer2.get("findings")).get(0);
        assertEquals("VIOLATION", finding.get("verdict"));
        assertEquals(List.of(0), finding.get("sentenceIndexes"));
        assertNull(finding.get("isTestimonial"));
        assertEquals("", ((Map<?, ?>) ((List<?>) finding.get("lawRefs")).get(0)).get("text"));
        Map<?, ?> caseRef = (Map<?, ?>) ((List<?>) finding.get("cases")).get(0);
        assertEquals(2025, caseRef.get("year"));
        assertEquals("감기 예방", caseRef.get("excerpt"));
    }

    @Test
    void mapsHoldToReviewAndLeavesUnknownCaseDateUnset() {
        FastApiResponse response = new FastApiResponse(
                List.of(new FastApiResponse.Sentence("s1", "면역력", 0, 3)),
                List.of(new FastApiResponse.Result(List.of("s1"), "면역력", "HOLD",
                        null, "제품 종류를 확인해 주세요.", List.of(),
                        new FastApiResponse.Span(0, 3), null)),
                "gemini-test", "none", 10L);

        Map<String, Object> layer2 = InspectionResultMapper.toLayer2(response,
                "2026-10-13T12:00:00+09:00");

        assertNull(layer2.get("dataAsOf"));
        Map<?, ?> finding = (Map<?, ?>) ((List<?>) layer2.get("findings")).get(0);
        assertEquals("REVIEW", finding.get("verdict"));
        assertEquals(List.of(), finding.get("cases"));
    }
}
