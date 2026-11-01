package com.my.food_ad_check.service;

import com.my.food_ad_check.dto.FastApiRequest;
import com.my.food_ad_check.dto.FastApiResponse;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.ResourceAccessException;

@Service
public class AiJudgmentService {

    private static final Logger log = LoggerFactory.getLogger(AiJudgmentService.class);
    private final RestTemplate restTemplate;
    private final String judgeUrl;

    @Autowired
    public AiJudgmentService(@Value("${judge.url:http://localhost:8000/judge}") String judgeUrl,
                             @Value("${judge.timeout-ms:30000}") int timeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(timeoutMs));
        factory.setReadTimeout(Duration.ofMillis(timeoutMs));
        this.restTemplate = new RestTemplate(factory);
        this.judgeUrl = judgeUrl;
    }

    AiJudgmentService(RestTemplate restTemplate, String judgeUrl) {
        this.restTemplate = restTemplate;
        this.judgeUrl = judgeUrl;
    }

    public record JudgmentOutcome(FastApiResponse response, String status) {
    }

    public JudgmentOutcome requestJudgmentOutcome(FastApiRequest requestDto) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<FastApiRequest> requestEntity = new HttpEntity<>(requestDto, headers);

        try {
            FastApiResponse response = restTemplate.postForObject(judgeUrl, requestEntity, FastApiResponse.class);
            if (!isComplete(response, requestDto.adText())) {
                log.warn("판정 서비스가 불완전한 응답을 반환했습니다. requestId={}", requestDto.requestId());
                return new JudgmentOutcome(null, "FAILED");
            }
            return new JudgmentOutcome(response, "DONE");
        } catch (ResourceAccessException e) {
            String status = isTimeout(e) ? "TIMEOUT" : "FAILED";
            log.warn("판정 서비스 호출 실패. requestId={}, status={}, cause={}",
                    requestDto.requestId(), status, e.getClass().getSimpleName());
            return new JudgmentOutcome(null, status);
        } catch (RestClientException e) {
            log.warn("판정 서비스 호출 실패. requestId={}, cause={}",
                    requestDto.requestId(), e.getClass().getSimpleName());
            return new JudgmentOutcome(null, "FAILED");
        }
    }

    private static boolean isComplete(FastApiResponse response, String adText) {
        if (response == null || response.sentences() == null || response.results() == null
                || response.model() == null || response.model().isBlank()) {
            return false;
        }
        int textLength = adText.codePointCount(0, adText.length());
        Set<String> sentenceIds = new HashSet<>();
        for (FastApiResponse.Sentence sentence : response.sentences()) {
            if (sentence == null || sentence.id() == null || sentence.text() == null
                    || sentence.start() == null || sentence.end() == null
                    || sentence.start() < 0 || sentence.end() < sentence.start()
                    || sentence.end() > textLength || !sentenceIds.add(sentence.id())) {
                return false;
            }
            int start = adText.offsetByCodePoints(0, sentence.start());
            int end = adText.offsetByCodePoints(0, sentence.end());
            if (!sentence.text().equals(adText.substring(start, end))) {
                return false;
            }
        }
        for (FastApiResponse.Result result : response.results()) {
            if (result == null || result.sentenceIds() == null || result.sentenceIds().isEmpty()
                    || !sentenceIds.containsAll(result.sentenceIds()) || result.status() == null
                    || result.reason() == null || !List.of("VIOLATION", "REVIEW", "OK", "HOLD")
                            .contains(result.status())) {
                return false;
            }
        }
        return true;
    }

    private static boolean isTimeout(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SocketTimeoutException || cause instanceof java.net.http.HttpTimeoutException) {
                return true;
            }
        }
        return false;
    }
}
