package com.my.food_ad_check.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.my.food_ad_check.entity.Product;
import com.my.food_ad_check.repository.ProductRepository;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

class ProductApiServiceTests {

    private ProductApiService productApiService;
    private ProductRepository productRepository;
    private RestTemplate restTemplate;
    private ProductImportWriter importWriter;

    @BeforeEach
    void setUp() {
        productRepository = mock(ProductRepository.class);
        restTemplate = mock(RestTemplate.class);
        importWriter = mock(ProductImportWriter.class);
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
    void skipsIngredientHeadingsAndRemovesKoreanListMarkers() {
        String source = "비타민D\n(가) 칼슘과 인이 흡수되고 이용되는데 필요\n"
                + "(나) 뼈의 형성과 유지에 필요\n"
                + "-판토텐산\n①지방, 탄수화물, 단백질 대사와 에너지 생성에 필요";

        assertEquals(List.of(
                "칼슘과 인이 흡수되고 이용되는데 필요",
                "뼈의 형성과 유지에 필요",
                "지방, 탄수화물, 단백질 대사와 에너지 생성에 필요"
        ), productApiService.parsePrimaryFnclty(source));
    }

    @Test
    void retainsShortClaimsAndUnclearUnnumberedLines() {
        assertEquals(List.of("수면", "항산화"), productApiService.parsePrimaryFnclty(
                "수면에 도움을 줄 수 있음\n항산화에 도움을 줄 수 있음"));
        assertEquals(List.of("스트레스로 인한 긴장완화"),
                productApiService.parsePrimaryFnclty(
                        "테아닌\n스트레스로 인한 긴장완화에 도움을 줄 수 있음"));
        assertEquals(List.of("근육, 결합조직 등 신체조직의 구성성분", "에너지 생성에 필요"),
                productApiService.parsePrimaryFnclty(
                        "근육, 결합조직 등 신체조직의 구성성분\n①에너지 생성에 필요"));
        assertEquals(List.of("항산화", "정상적인 면역기능에 필요"),
                productApiService.parsePrimaryFnclty("항산화\n①정상적인 면역기능에 필요"));
    }

    @Test
    void legacyParserReproducesSavedValuesForSafeReconciliation() {
        String source = "비타민D\n(가) 칼슘과 인이 흡수되고 이용되는데 필요";

        assertEquals(List.of("비타민D", "(가) 칼슘과 인이 흡수되고 이용되는데 필요"),
                productApiService.parsePrimaryFncltyLegacy(source));
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
        verify(importWriter).savePage(any(), any());
    }

    @Test
    void repeatImportRefreshesFunctionsFromChangedSource() {
        Product existing = new Product();
        existing.setPrdlstReportNo("R-001");
        existing.setPrdlstNm("옛 이름");
        existing.setPrimaryFnclty("옛 원문");
        existing.setApprovedFunctions(List.of("수동 확인한 기능"));
        when(productRepository.findById("R-001")).thenReturn(Optional.of(existing));
        when(restTemplate.getForObject(any(URI.class), eq(String.class))).thenReturn("""
                {"I0030":{"total_count":"1","row":[
                  {"PRDLST_REPORT_NO":"R-001","PRDLST_NM":"새 이름","BSSH_NM":"제조사",
                   "PRIMARY_FNCLTY":"새로운 기능에 도움을 줄 수 있음"}
                ]}}
                """);

        String result = productApiService.loadAllProductData();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Product>> saved = ArgumentCaptor.forClass(List.class);
        verify(importWriter).savePage(saved.capture(), any());
        Product reimported = saved.getValue().iterator().next();
        assertEquals("새 이름", reimported.getPrdlstNm());
        assertEquals("새로운 기능에 도움을 줄 수 있음", reimported.getPrimaryFnclty());
        assertEquals(List.of("새로운 기능"), reimported.getApprovedFunctions());
        assertTrue(result.contains("목록 자동 갱신) 1건"));
        assertTrue(!reimported.isFunctionSourceReviewRequired());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<com.my.food_ad_check.entity.ProductFunctionChange>> changes = ArgumentCaptor.forClass(List.class);
        verify(importWriter).savePage(any(), changes.capture());
        assertEquals(1, changes.getValue().size());
        assertEquals("옛 원문", changes.getValue().get(0).getPreviousSource());
        assertEquals("새로운 기능에 도움을 줄 수 있음", changes.getValue().get(0).getCurrentSource());
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
        return new ProductApiService(productRepository, importWriter, restTemplate, apiKey,
                "https://example.test/api/");
    }

    @Test
    void failedExtractionClearsOldFunctionsInsteadOfUsingStaleClaims() {
        Product existing = new Product();
        existing.setPrdlstReportNo("R1");
        existing.setPrimaryFnclty("옛 원문");
        existing.setApprovedFunctions(List.of("과거 기능"));
        when(productRepository.findById("R1")).thenReturn(Optional.of(existing));
        when(restTemplate.getForObject(any(URI.class), eq(String.class))).thenReturn("""
                {"I0030":{"total_count":"1","row":[{"PRDLST_REPORT_NO":"R1","PRDLST_NM":"제품","PRIMARY_FNCLTY":""}]}}
                """);
        productApiService.loadAllProductData();
        assertTrue(existing.getApprovedFunctions().isEmpty());
        assertTrue(existing.isFunctionSourceReviewRequired());
        verify(importWriter).savePage(any(), any());
    }

    @Test
    void missingSourceFieldDoesNotGenerateAFalseChangeOrAdvanceCheckpoint() {
        when(restTemplate.getForObject(any(URI.class), eq(String.class))).thenReturn("""
                {"I0030":{"total_count":"1","row":[{"PRDLST_REPORT_NO":"R1","PRDLST_NM":"제품"}]}}
                """);
        assertTrue(!productApiService.importSince(java.time.LocalDate.of(2026, 11, 9)).completed());
        verifyNoInteractions(importWriter);
    }

    @Test
    void previouslyBlockedProductCanRecoverAutomaticallyWithoutNewSourceChange() {
        Product existing = new Product();
        existing.setPrdlstReportNo("R1");
        existing.setPrimaryFnclty("피로개선에 도움을 줄 수 있음");
        existing.setFunctionSourceReviewRequired(true);
        when(productRepository.findById("R1")).thenReturn(Optional.of(existing));
        when(restTemplate.getForObject(any(URI.class), eq(String.class))).thenReturn("""
                {"I0030":{"total_count":"1","row":[{"PRDLST_REPORT_NO":"R1","PRDLST_NM":"제품",
                 "PRIMARY_FNCLTY":"피로개선에 도움을 줄 수 있음"}]}}
                """);
        productApiService.loadAllProductData();
        assertEquals(List.of("피로개선"), existing.getApprovedFunctions());
        assertTrue(!existing.isFunctionSourceReviewRequired());
        verify(importWriter).savePage(any(), eq(List.of()));
    }

    @Test
    void incrementalNoDataIsSuccessfulAndUsesDocumentedDatePath() {
        when(restTemplate.getForObject(any(URI.class), eq(String.class))).thenReturn("""
                {"I0030":{"RESULT":{"CODE":"INFO-200","MSG":"해당하는 데이터가 없습니다."}}}
                """);
        var outcome = productApiService.importSince(java.time.LocalDate.of(2026, 11, 9));
        assertTrue(outcome.completed());
        var uri = ArgumentCaptor.forClass(URI.class);
        verify(restTemplate).getForObject(uri.capture(), eq(String.class));
        assertTrue(uri.getValue().getPath().endsWith("/I0030/json/1/1000/CHNG_DT=20261109"));
        verifyNoInteractions(importWriter);
    }

    @Test
    void unchangedProductIsNotWrittenAgain() {
        Product existing = new Product();
        existing.setPrdlstReportNo("R1");
        existing.setPrdlstNm("제품");
        existing.setBsshNm("제조사");
        existing.setProductType("건강기능식품");
        existing.setPrimaryFnclty("기능성");
        when(productRepository.findById("R1")).thenReturn(Optional.of(existing));
        when(restTemplate.getForObject(any(URI.class), eq(String.class))).thenReturn("""
                {"I0030":{"total_count":"1","row":[{"PRDLST_REPORT_NO":"R1","PRDLST_NM":"제품",
                 "BSSH_NM":"제조사","PRIMARY_FNCLTY":"기능성"}]}}
                """);
        assertTrue(productApiService.importSince(java.time.LocalDate.of(2026, 11, 9)).completed());
        verifyNoInteractions(importWriter);
    }

    @Test
    void detailOnlyChangeIsSavedWithoutAFalseFunctionChange() {
        Product existing = new Product();
        existing.setPrdlstReportNo("R1");
        existing.setPrdlstNm("제품");
        existing.setBsshNm("제조사");
        existing.setProductType("건강기능식품");
        existing.setPrimaryFnclty("기능성");
        existing.setCautionsSource("옛 주의사항");
        when(productRepository.findById("R1")).thenReturn(Optional.of(existing));
        when(restTemplate.getForObject(any(URI.class), eq(String.class))).thenReturn("""
                {"I0030":{"total_count":"1","row":[{"PRDLST_REPORT_NO":"R1","PRDLST_NM":"제품",
                 "BSSH_NM":"제조사","PRIMARY_FNCLTY":"기능성","IFTKN_ATNT_MATR_CN":"새 주의사항",
                 "RAWMTRL_NM":"홍삼","NTK_MTHD":"1일 1회","INDIV_RAWMTRL_NM":"전분",
                 "ETC_RAWMTRL_NM":"쌀","CAP_RAWMTRL_NM":"젤라틴","STDR_STND":"규격"}]}}
                """);
        assertTrue(productApiService.importSince(java.time.LocalDate.of(2026, 11, 9)).completed());
        assertEquals("새 주의사항", existing.getCautionsSource());
        assertEquals("홍삼", existing.getIngredientsSource());
        assertEquals("규격", existing.getStandards());
        verify(importWriter).savePage(eq(List.of(existing)), eq(List.of()));
    }

    @Test
    void skippedRowsPreventWatermarkAdvancement() {
        when(restTemplate.getForObject(any(URI.class), eq(String.class))).thenReturn("""
                {"I0030":{"total_count":"1","row":[{"PRDLST_REPORT_NO":"","PRDLST_NM":"누락"}]}}
                """);
        assertTrue(!productApiService.importSince(java.time.LocalDate.of(2026, 11, 9)).completed());
        verifyNoInteractions(importWriter);
    }

    @Test
    void laterPageFailureDoesNotMarkPartialImportCompleted() {
        String row = "{\"PRDLST_REPORT_NO\":\"R1\",\"PRDLST_NM\":\"제품\",\"PRIMARY_FNCLTY\":\"기능\"}";
        String firstPage = "{\"I0030\":{\"total_count\":\"1001\",\"row\":["
                + String.join(",", java.util.Collections.nCopies(1000, row)) + "]}}";
        when(restTemplate.getForObject(any(URI.class), eq(String.class))).thenReturn(firstPage,
                "{\"I0030\":{\"RESULT\":{\"CODE\":\"ERROR-500\",\"MSG\":\"서버 오류\"}}}");
        var outcome = productApiService.importSince(java.time.LocalDate.of(2026, 11, 9));
        assertTrue(!outcome.completed());
        verify(importWriter).savePage(any(), any());
        var uri = ArgumentCaptor.forClass(URI.class);
        verify(restTemplate, org.mockito.Mockito.times(2)).getForObject(uri.capture(), eq(String.class));
        assertTrue(uri.getAllValues().get(1).getPath().endsWith("/1001/2000/CHNG_DT=20261109"));
    }

    @Test
    void unchangedSourceAndNewProductsDoNotProduceChangeEvents() {
        Product existing = new Product();
        existing.setPrdlstReportNo("R-001");
        existing.setPrimaryFnclty("동일 원문");
        when(productRepository.findById("R-001")).thenReturn(Optional.of(existing));
        when(restTemplate.getForObject(any(URI.class), eq(String.class))).thenReturn("""
                {"I0030":{"total_count":"2","row":[
                 {"PRDLST_REPORT_NO":"R-001","PRDLST_NM":"기존 제품","PRIMARY_FNCLTY":"동일 원문"},
                 {"PRDLST_REPORT_NO":"R-002","PRDLST_NM":"새 제품","PRIMARY_FNCLTY":"새 기능"}]}}
                """);
        productApiService.loadAllProductData();
        verify(importWriter).savePage(any(), eq(List.of()));
    }
}
