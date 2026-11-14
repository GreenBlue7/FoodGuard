package com.my.food_ad_check.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.my.food_ad_check.dto.FastApiRequest;
import com.my.food_ad_check.entity.InspectionRecord;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/** Match inspection meaning, not generated UUIDs/timestamps or JSON key ordering. */
public final class InspectionRequestFingerprint {
    private static final ObjectMapper JSON = new ObjectMapper();
    private InspectionRequestFingerprint() { }

    public static String of(InspectionRecord record, FastApiRequest request,
                            InspectionRecommendations.Draft recommendation) {
        try {
            JsonNode input = JSON.readTree(record.getResultJson());
            Map<String, Object> identity = new TreeMap<>();
            identity.put("schema", 1);
            identity.put("request", input.path("request"));
            identity.put("productSnapshot", input.path("productSnapshot"));
            identity.put("productInfoSource", input.path("productInfoSource"));
            identity.put("provisionalStep1", input.path("provisionalStep1"));
            identity.put("previousJobId", record.getPreviousInspectionId());
            identity.put("sentenceEdits", input.has("sentenceEditMappings")
                    ? input.get("sentenceEditMappings") : JSON.createArrayNode());
            identity.put("recommendation", recommendation);
            identity.put("judgeMode", request.mode());
            identity.put("judgeProduct", request.product());
            identity.put("adText", request.adText());
            byte[] canonical = JSON.writeValueAsBytes(canonical(JSON.valueToTree(identity)));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (Exception invalid) {
            throw new IllegalArgumentException("Cannot identify inspection input", invalid);
        }
    }

    private static Object canonical(JsonNode node) {
        if (node.isObject()) {
            Map<String, Object> sorted = new TreeMap<>();
            node.properties().forEach(entry -> sorted.put(entry.getKey(), canonical(entry.getValue())));
            return sorted;
        }
        if (node.isArray()) {
            var values = new ArrayList<>();
            node.forEach(value -> values.add(canonical(value)));
            return values;
        }
        if (node.isNull() || node.isMissingNode()) return null;
        if (node.isNumber()) return node.numberValue();
        if (node.isBoolean()) return node.booleanValue();
        return node.textValue();
    }
}
