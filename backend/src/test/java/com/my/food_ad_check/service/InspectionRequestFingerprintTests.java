package com.my.food_ad_check.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.my.food_ad_check.dto.FastApiRequest;
import com.my.food_ad_check.entity.InspectionRecord;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InspectionRequestFingerprintTests {
    private final FastApiRequest request = new FastApiRequest("generated-one", "C",
            new FastApiRequest.Product("GENERAL_FOOD", List.of(), List.of()), "광고");

    private InspectionRecord record(String json, Long previous) {
        var record = new InspectionRecord(1L, null, null, null, null, null, "광고", "PENDING", json, LocalDateTime.now());
        if (previous != null) record.linkToPreviousInspection(previous);
        return record;
    }

    @Test void generatedTimesIdsAndObjectKeyOrderDoNotChangeIdentity() {
        var first = record("""
                {"createdAt":"old","judgeRequestId":"one","request":{"adText":"광고","productIds":[]},
                 "productSnapshot":{"version":1,"reportNo":"R1"}}
                """, null);
        var second = record("""
                {"productSnapshot":{"reportNo":"R1","version":1},"request":{"productIds":[],"adText":"광고"},
                 "judgeRequestId":"two","createdAt":"new"}
                """, null);
        assertEquals(InspectionRequestFingerprint.of(first, request, null), InspectionRequestFingerprint.of(second,
                new FastApiRequest("generated-two", request.mode(), request.product(), request.adText()), null));
    }

    @Test void previousInspectionAndRecommendationRemainDistinct() {
        var first = record("{}", 10L);
        var second = record("{}", 11L);
        assertNotEquals(InspectionRequestFingerprint.of(first, request, null),
                InspectionRequestFingerprint.of(second, request, null));
        var draft = new InspectionRecommendations.Draft("finding-0", "이전", "광고", 0, 2, "UNVERIFIED");
        assertNotEquals(InspectionRequestFingerprint.of(first, request, null),
                InspectionRequestFingerprint.of(first, request, draft));
    }

    @Test void manualSentenceLinksRemainDistinct() throws Exception {
        var json = new ObjectMapper();
        var first = record(json.writeValueAsString(Map.of("sentenceEditMappings", List.of(
                Map.of("previousSentenceId", "s1", "currentStart", 0, "currentEnd", 2)))), 10L);
        var second = record(json.writeValueAsString(Map.of("sentenceEditMappings", List.of(
                Map.of("previousSentenceId", "s2", "currentStart", 0, "currentEnd", 2)))), 10L);
        assertNotEquals(InspectionRequestFingerprint.of(first, request, null),
                InspectionRequestFingerprint.of(second, request, null));
    }
}
