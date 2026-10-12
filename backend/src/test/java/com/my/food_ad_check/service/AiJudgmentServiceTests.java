package com.my.food_ad_check.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.my.food_ad_check.dto.FastApiRequest;
import com.my.food_ad_check.dto.FastApiResponse;
import java.net.SocketTimeoutException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

class AiJudgmentServiceTests {

    private final RestTemplate restTemplate = org.mockito.Mockito.mock(RestTemplate.class);
    private final AiJudgmentService service = new AiJudgmentService(restTemplate, "http://localhost/judge");

    @Test
    void socketTimeoutIsDistinguishedFromOtherConnectionFailures() {
        when(restTemplate.postForObject(anyString(), any(), eq(FastApiResponse.class)))
                .thenThrow(new ResourceAccessException("timed out", new SocketTimeoutException()));

        AiJudgmentService.JudgmentOutcome outcome = service.requestJudgmentOutcome(request("광고"));

        assertEquals("TIMEOUT", outcome.status());
        assertNull(outcome.response());
    }

    @Test
    void nonTimeoutConnectionFailureIsFailed() {
        when(restTemplate.postForObject(anyString(), any(), eq(FastApiResponse.class)))
                .thenThrow(new ResourceAccessException("connection refused"));

        assertEquals("FAILED", service.requestJudgmentOutcome(request("광고")).status());
    }

    @Test
    void missingResponseFieldsOrWrongSentenceOffsetsAreFailed() {
        when(restTemplate.postForObject(anyString(), any(), eq(FastApiResponse.class)))
                .thenReturn(new FastApiResponse(
                        List.of(new FastApiResponse.Sentence("s1", "다른 문구", 0, 2)),
                        List.of(), "mock", "none", 1L));

        assertEquals("FAILED", service.requestJudgmentOutcome(request("광고")).status());
    }

    @Test
    void codePointOffsetsFromJudgeAreAccepted() {
        when(restTemplate.postForObject(anyString(), any(), eq(FastApiResponse.class)))
                .thenReturn(new FastApiResponse(
                        List.of(new FastApiResponse.Sentence("s1", "😊광고", 0, 3)),
                        List.of(new FastApiResponse.Result(List.of("s1"), "😊광고", "OK",
                                null, "문제없음", List.of(), new FastApiResponse.Span(0, 3), null)),
                        "mock", "none", 1L));

        assertEquals("DONE", service.requestJudgmentOutcome(request("😊광고")).status());
    }

    private static FastApiRequest request(String adText) {
        return new FastApiRequest("request-1", "C",
                new FastApiRequest.Product("UNKNOWN", List.of(), List.of()), adText);
    }
}
