package com.my.food_ad_check.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InspectionHistoryMetadataTests {
    @Test
    void countsDisplayedFindingsRatherThanSentenceReferences() {
        var summary = InspectionHistoryMetadata.read("DONE", """
                {"layer2":{"dataAsOf":"2026-10-01","findings":[
                  {"verdict":"VIOLATION","sentenceIndexes":[0,1]},
                  {"verdict":"REVIEW"},{"verdict":"OK"}]}}
                """);
        assertTrue(summary.countsAvailable());
        assertEquals(1, summary.violationCount());
        assertEquals(1, summary.reviewCount());
        assertEquals("2026-10-01", summary.dataAsOf());
    }

    @Test
    void oldJudgeResultsCanBeSummarizedAndHoldNeedsReview() {
        var summary = InspectionHistoryMetadata.read("DONE", """
                {"judgeResponse":{"kbVersion":"2026-09-30","results":[
                  {"status":"VIOLATION"},{"status":"REVIEW"},{"status":"HOLD"},{"status":"OK"}]}}
                """);
        assertTrue(summary.countsAvailable());
        assertEquals(1, summary.violationCount());
        assertEquals(2, summary.reviewCount());
        assertEquals("2026-09-30", summary.dataAsOf());
    }

    @Test
    void successfulZeroFindingsIsDifferentFromUnavailableResult() {
        var zero = InspectionHistoryMetadata.read("DONE", "{\"layer2\":{\"findings\":[]}}");
        assertTrue(zero.countsAvailable());
        assertEquals(0, zero.violationCount());
        assertEquals(0, zero.reviewCount());
        for (String text : new String[]{null, "", "broken", "null", "[]", "{}",
                "{\"layer2\":{\"findings\":{}}}",
                "{\"layer2\":{\"findings\":[null]}}",
                "{\"layer2\":{\"findings\":[{\"verdict\":\"UNKNOWN\"}]}}"}) {
            var unavailable = InspectionHistoryMetadata.read("DONE", text);
            assertFalse(unavailable.countsAvailable(), String.valueOf(text));
            assertNull(unavailable.violationCount());
            assertNull(unavailable.reviewCount());
        }
    }

    @Test
    void incompleteJobsNeverLookLikeSuccessfulZeroCounts() {
        for (String status : new String[]{"TIMEOUT", "FAILED", "PENDING", "RUNNING"}) {
            var summary = InspectionHistoryMetadata.read(status, "{\"layer2\":{\"findings\":[]}}");
            assertFalse(summary.countsAvailable());
            assertNull(summary.violationCount());
            assertNull(summary.reviewCount());
        }
    }

    @Test
    void unknownDateIsNotReplacedByToday() {
        var summary = InspectionHistoryMetadata.read("DONE", """
                {"layer2":{"dataAsOf":"2026-99-99","findings":[]},"judgeResponse":{"kbVersion":"none"}}
                """);
        assertTrue(summary.countsAvailable());
        assertNull(summary.dataAsOf());
    }
}
