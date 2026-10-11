package com.my.food_ad_check.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class RuleCheckService {

    private Map<String, List<String>> rules;

    // 서버가 켜질 때 resources/rule.json 파일을 한 번 읽어서 메모리에 올려둡니다.
    @PostConstruct
    public void init() {
        try {
            ObjectMapper mapper = new ObjectMapper();
            InputStream is = new ClassPathResource("rule.json").getInputStream();
            rules = mapper.readValue(is, new TypeReference<Map<String, List<String>>>() {});
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("rule.json 파일을 읽어오는 데 실패했습니다.");
        }
    }

    // 광고 문구와 제품 종류를 받아 누락된 필수 문구를 찾아냅니다.
    public List<String> checkMandatoryPhrases(String productType, String adText) {
        List<String> missingItems = new ArrayList<>();
        List<String> requiredPhrases = rules.getOrDefault(productType, new ArrayList<>());

        for (String phrase : requiredPhrases) {
            if (!adText.contains(phrase)) {
                missingItems.add(phrase + " (안내 문구 누락)");
            }
        }
        return missingItems;
    }
}