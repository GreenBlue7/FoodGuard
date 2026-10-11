package com.my.food_ad_check.service;

import com.my.food_ad_check.dto.FastApiRequest;
import com.my.food_ad_check.dto.FastApiResponse;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.RestClientException;

@Service
public class AiJudgmentService {

    private static final Logger log = LoggerFactory.getLogger(AiJudgmentService.class);
    private final RestTemplate restTemplate;
    private final String judgeUrl;

    public AiJudgmentService(@Value("${judge.url:http://localhost:8000/judge}") String judgeUrl,
                             @Value("${judge.timeout-ms:30000}") int timeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(timeoutMs));
        factory.setReadTimeout(Duration.ofMillis(timeoutMs));
        this.restTemplate = new RestTemplate(factory);
        this.judgeUrl = judgeUrl;
    }

    public FastApiResponse requestJudgment(FastApiRequest requestDto) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<FastApiRequest> requestEntity = new HttpEntity<>(requestDto, headers);

        try {
            FastApiResponse response = restTemplate.postForObject(judgeUrl, requestEntity, FastApiResponse.class);
            if (response == null || response.sentences() == null || response.results() == null) {
                log.warn("판정 서비스가 불완전한 응답을 반환했습니다. requestId={}", requestDto.requestId());
                return null;
            }
            return response;
        } catch (RestClientException e) {
            log.warn("판정 서비스 호출 실패. requestId={}, cause={}",
                    requestDto.requestId(), e.getClass().getSimpleName());
            return null;
        }
    }
}
