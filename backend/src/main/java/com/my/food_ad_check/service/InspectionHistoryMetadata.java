package com.my.food_ad_check.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/** Reads historical results only; never reruns a judgment or substitutes today's criteria. */
public record InspectionHistoryMetadata(boolean countsAvailable, Integer violationCount,
                                        Integer reviewCount, String dataAsOf) {
    private static final ObjectMapper JSON = new ObjectMapper();

    public static InspectionHistoryMetadata read(String status, String source) {
        if (!"DONE".equals(status) || source == null || source.isBlank()) return unavailable(null);
        try {
            JsonNode root = JSON.readTree(source);
            if (root == null || !root.isObject()) return unavailable(null);
            String date = date(root.path("layer2").path("dataAsOf"));
            if (date == null) date = date(root.path("judgeResponse").path("kbVersion"));
            JsonNode findings = root.path("layer2").path("findings");
            String verdictField = "verdict";
            if (findings.isMissingNode() || findings.isNull()) {
                findings = root.path("judgeResponse").path("results");
                verdictField = "status";
            }
            if (!findings.isArray()) return unavailable(date);
            int violation = 0, review = 0;
            for (JsonNode finding : findings) {
                if (!finding.isObject() || !finding.path(verdictField).isTextual()) return unavailable(date);
                switch (finding.path(verdictField).textValue()) {
                    case "VIOLATION" -> violation++;
                    case "REVIEW" -> review++;
                    case "HOLD" -> review++;
                    case "OK" -> { }
                    default -> { return unavailable(date); }
                }
            }
            return new InspectionHistoryMetadata(true, violation, review, date);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            return unavailable(null);
        }
    }

    private static InspectionHistoryMetadata unavailable(String date) {
        return new InspectionHistoryMetadata(false, null, null, date);
    }

    private static String date(JsonNode value) {
        if (!value.isTextual()) return null;
        try { return LocalDate.parse(value.textValue()).toString(); }
        catch (DateTimeParseException e) { return null; }
    }
}
