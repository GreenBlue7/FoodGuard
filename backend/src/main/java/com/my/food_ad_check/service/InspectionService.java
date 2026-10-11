package com.my.food_ad_check.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Service
public class InspectionService {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private JsonNode rules;

    @PostConstruct
    public void init() throws IOException {
        ClassPathResource resource = new ClassPathResource("rule.json");
        rules = objectMapper.readTree(resource.getInputStream());
    }

    public List<String> checkMandatoryText(String adText, String productType) {
        List<String> missingItems = new ArrayList<>();
        JsonNode typeRules = rules.get(productType);

        if (typeRules != null && typeRules.isArray()) {
            for (JsonNode rule : typeRules) {
                String requiredText = rule.get("text").asText();
                if (!adText.contains(requiredText)) {
                    missingItems.add(rule.get("msg").asText());
                }
            }
        }
        return missingItems;
    }
}
