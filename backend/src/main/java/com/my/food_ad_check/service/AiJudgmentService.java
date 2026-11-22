package com.my.food_ad_check.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.my.food_ad_check.dto.FastApiRequest;
import com.my.food_ad_check.dto.FastApiResponse;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.ResourceAccessException;

@Service
public class AiJudgmentService {

    private static final Logger log = LoggerFactory.getLogger(AiJudgmentService.class);
    private final RestTemplate restTemplate;
    private final String judgeUrl;
    private final RestTemplate imageClient;
    private final String imageUrl;
    private final ObjectMapper json = new ObjectMapper();

    @Autowired
    public AiJudgmentService(@Value("${judge.url:http://localhost:8000/judge}") String judgeUrl,
            @Value("${judge.timeout-ms:180000}") int timeoutMs,
            @Value("${judge.image-url:}") String imageUrl,
            @Value("${judge.image-timeout-ms:300000}") int imageTimeoutMs) {
        this(client(timeoutMs), judgeUrl, client(imageTimeoutMs),
                imageUrl.isBlank() ? judgeUrl.replaceFirst("/$", "") + "/image" : imageUrl);
    }

    private static RestTemplate client(int timeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(timeoutMs));
        factory.setReadTimeout(Duration.ofMillis(timeoutMs));
        return new RestTemplate(factory);
    }

    AiJudgmentService(RestTemplate restTemplate, String judgeUrl) {
        this(restTemplate, judgeUrl, restTemplate, judgeUrl + "/image");
    }

    private AiJudgmentService(RestTemplate restTemplate, String judgeUrl, RestTemplate imageClient, String imageUrl) {
        this.restTemplate = restTemplate;
        this.judgeUrl = judgeUrl;
        this.imageClient = imageClient;
        this.imageUrl = imageUrl;
    }

    public record ImageJudgmentOutcome(FastApiResponse response, JsonNode imageResult, String status, String failureCode) { }

    public ImageJudgmentOutcome requestImageJudgmentOutcome(FastApiRequest request, List<InspectionImage> images) {
        try {
            var parts = new LinkedMultiValueMap<String, Object>();
            parts.add("request", json.writeValueAsString(Map.of(
                    "requestId", request.requestId(), "mode", request.mode(), "product", request.product())));
            for (int i = 0; i < images.size(); i++) {
                var image = images.get(i);
                String filename = "image-" + (i + 1) + ("image/png".equals(image.contentType()) ? ".png" : ".jpg");
                var resource = new ByteArrayResource(image.bytes()) {
                    @Override public String getFilename() { return filename; }
                };
                var partHeaders = new HttpHeaders();
                partHeaders.setContentType(MediaType.parseMediaType(image.contentType()));
                parts.add("images", new HttpEntity<>(resource, partHeaders));
            }
            var headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);
            String body = imageClient.postForObject(imageUrl, new HttpEntity<>(parts, headers), String.class);
            JsonNode imageResult = body == null ? null : json.readTree(body);
            if (imageResult != null && imageResult.isObject()) {
                String text = imageResult.path("adText").asText();
                var response = json.readerFor(FastApiResponse.class)
                        .without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).<FastApiResponse>readValue(imageResult);
                if (!text.isBlank() && validPages(imageResult.path("pages")) && isComplete(response, text)
                        && imageResult.path("excludedLines").isArray() && imageResult.path("unreadRegions").isArray()) {
                    return new ImageJudgmentOutcome(response, imageResult, "DONE", null);
                }
            }
        } catch (HttpStatusCodeException e) {
            if (e.getStatusCode().value() == 422) return imageFailure("FAILED", "IMAGE_NO_AD_TEXT");
            if (e.getStatusCode().value() == 400) return imageFailure("FAILED", "IMAGE_REJECTED");
            log.warn("이미지 판정 요청 실패. requestId={}, status={}", request.requestId(), e.getStatusCode().value());
        } catch (ResourceAccessException e) {
            if (isTimeout(e)) return imageFailure("TIMEOUT", "IMAGE_TIMEOUT");
            log.warn("이미지 판정 요청 실패. requestId={}, cause={}", request.requestId(), e.getClass().getSimpleName());
        } catch (IOException | RestClientException e) {
            log.warn("이미지 판정 요청 실패. requestId={}, cause={}", request.requestId(), e.getClass().getSimpleName());
        }
        return imageFailure("FAILED", "IMAGE_JUDGE_FAILED");
    }

    private static boolean validPages(JsonNode pages) {
        if (!pages.isArray() || pages.isEmpty()) return false;
        for (int i = 0; i < pages.size(); i++) {
            var page = pages.get(i);
            if (page.path("index").asInt(-1) != i || page.path("height").asInt() <= 0
                    || page.path("width").asInt() <= 0
                    || !page.path("image").asText().startsWith("data:image/jpeg;base64,")) return false;
        }
        return true;
    }

    private static ImageJudgmentOutcome imageFailure(String status, String code) {
        return new ImageJudgmentOutcome(null, null, status, code);
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
