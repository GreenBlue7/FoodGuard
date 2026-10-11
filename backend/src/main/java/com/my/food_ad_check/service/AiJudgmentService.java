package com.my.food_ad_check.service;

import com.my.food_ad_check.dto.FastApiRequest;
import com.my.food_ad_check.dto.FastApiResponse;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Service
public class AiJudgmentService {

    private final RestTemplate restTemplate = new RestTemplate();
    private final String FASTAPI_URL = "http://localhost:8000/judge"; // 파이썬 서버 주소[cite: 5]

    public FastApiResponse requestJudgment(FastApiRequest requestDto) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<FastApiRequest> requestEntity = new HttpEntity<>(requestDto, headers);

        try {
            // 파이썬 서버로 데이터 전송 및 응답 수신
            return restTemplate.postForObject(FASTAPI_URL, requestEntity, FastApiResponse.class);
        } catch (Exception e) {
            FastApiResponse errorResponse = new FastApiResponse();
            errorResponse.setStatus("ERROR");
            errorResponse.setResult("FastAPI 서버 통신 실패: " + e.getMessage());
            return errorResponse;
        }
    }
}