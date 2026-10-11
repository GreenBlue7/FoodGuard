package com.my.food_ad_check.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.my.food_ad_check.repository.ProductRepository;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

class ProductApiServiceTests {

    private ProductApiService productApiService;
    private ProductRepository productRepository;
    private RestTemplate restTemplate;

    @BeforeEach
    void setUp() {
        productRepository = mock(ProductRepository.class);
        restTemplate = mock(RestTemplate.class);
        productApiService = service("test-key");
    }

    @Test
    void parsesInterpunctSeparatedClaimsWithoutRemovingClaimWords() {
        String source = "①면역력 증진·피로개선·혈소판 응집억제를 통한 혈액흐름·기억력 개선·항산화·갱년기 여성의 건강에 도움을 줄 수 있음";

        assertEquals(List.of(
                "면역력 증진",
                "피로개선",
                "혈소판 응집억제를 통한 혈액흐름",
                "기억력 개선",
                "항산화",
                "갱년기 여성의 건강"
        ), productApiService.parsePrimaryFnclty(source));
    }

    @Test
    void parsesNumberedClaimsWithRepeatedApprovedWording() {
        String source = "[홍삼제품]①면역력 증진에 도움을 줄 수 있음"
                + "②피로개선에 도움을 줄 수 있음"
                + "③혈소판 응집 억제를 통한 혈액흐름에 도움을 줄 수 있음"
                + "④기억력 개선에 도움을 줄 수 있음"
                + "⑤항산화에 도움을 줄 수 있음";

        assertEquals(List.of(
                "면역력 증진",
                "피로개선",
                "혈소판 응집 억제를 통한 혈액흐름",
                "기억력 개선",
                "항산화"
        ), productApiService.parsePrimaryFnclty(source));
    }

    @Test
    void parsesLabeledMultilineIngredientsAndCommaSeparatedClaims() {
        String source = "- 홍삼 : 면역력 증진·피로개선에 도움을 줄 수 있음\n"
                + "- 나이아신 : 체내 에너지 생성에 필요\n"
                + "- 아연 : 정상적인 면역기능에 필요, 정상적인 세포분열에 필요";

        assertEquals(List.of(
                "면역력 증진",
                "피로개선",
                "체내 에너지 생성에 필요",
                "정상적인 면역기능에 필요",
                "정상적인 세포분열에 필요"
        ), productApiService.parsePrimaryFnclty(source));
    }

    @Test
    void emptyOrMissingSourceReturnsNoClaims() {
        assertEquals(List.of(), productApiService.parsePrimaryFnclty(null));
        assertEquals(List.of(), productApiService.parsePrimaryFnclty("  "));
    }

    @Test
    void dataLoadStopsBeforeApiCallWhenApiKeyIsMissing() {
        productApiService = service("");

        String result = productApiService.loadAllProductData();

        assertTrue(result.contains("FOOD_SAFETY_API_KEY"));
        verify(restTemplate, never()).getForObject(any(URI.class), eq(String.class));
    }

    @Test
    void dataLoadSavesValidRowsAndReportsSkippedRows() {
        when(restTemplate.getForObject(any(URI.class), eq(String.class))).thenReturn("""
                {"I0030":{"total_count":"2","row":[
                  {"PRDLST_REPORT_NO":"R-001","PRDLST_NM":"홍삼","BSSH_NM":"제조사","PRIMARY_FNCLTY":"면역력 증진"},
                  {"PRDLST_REPORT_NO":"","PRDLST_NM":"누락 제품"}
                ]}}
                """);

        String result = productApiService.loadAllProductData();

        assertTrue(result.contains("저장 1건"));
        assertTrue(result.contains("제외 1건"));
        verify(productRepository).saveAll(any());
    }

    @Test
    void dataLoadDoesNotReportApiErrorAsSuccessfulEmptyImport() {
        when(restTemplate.getForObject(any(URI.class), eq(String.class))).thenReturn("""
                {"I0030":{"RESULT":{"CODE":"ERROR-100","MSG":"인증키 오류"}}}
                """);

        String result = productApiService.loadAllProductData();

        assertTrue(result.startsWith("데이터 적재 실패: 식약처 API 오류"));
        verify(productRepository, never()).saveAll(any());
    }

    @Test
    void dataLoadRejectsIncompletePageInsteadOfSilentlySkippingRows() {
        when(restTemplate.getForObject(any(URI.class), eq(String.class))).thenReturn("""
                {"I0030":{"total_count":"1001","row":[
                  {"PRDLST_REPORT_NO":"R-001","PRDLST_NM":"홍삼"}
                ]}}
                """);

        String result = productApiService.loadAllProductData();

        assertTrue(result.contains("페이지에 전체 데이터가 들어오지 않았습니다"));
        verify(productRepository, never()).saveAll(any());
    }

    @Test
    void dataLoadRejectsResponseWithoutTotalCount() {
        when(restTemplate.getForObject(any(URI.class), eq(String.class)))
                .thenReturn("{\"I0030\":{\"row\":[]}}");

        String result = productApiService.loadAllProductData();

        assertTrue(result.contains("유효한 total_count가 없습니다"));
        verify(productRepository, never()).saveAll(any());
    }

    private ProductApiService service(String apiKey) {
        return new ProductApiService(productRepository, restTemplate, apiKey,
                "https://example.test/api/");
    }
}
