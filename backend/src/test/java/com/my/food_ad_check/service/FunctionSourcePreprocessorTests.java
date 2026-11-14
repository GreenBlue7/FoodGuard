package com.my.food_ad_check.service;

import com.my.food_ad_check.entity.Product;
import com.my.food_ad_check.repository.ProductRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class FunctionSourcePreprocessorTests {
    private final ProductApiService parser = new ProductApiService(mock(ProductRepository.class),
            mock(ProductImportWriter.class), mock(RestTemplate.class), "", "https://unused.invalid");

    private Product extract(String source) {
        Product product = new Product();
        ReflectionTestUtils.invokeMethod(parser, "refreshFunctions", product, source);
        return product;
    }

    @Test
    void acceptsRealClaimsWithoutTheOldRequiredKeywords() {
        assertEquals(List.of("배변활동 원활"), extract("①배변활동 원활").getApprovedFunctions());
        assertEquals(List.of("식이섬유 보충"), extract("①식이섬유 보충").getApprovedFunctions());
        assertFalse(extract("①배변활동 원활").isFunctionSourceReviewRequired());
    }

    @Test
    void separatesKoreanClaimsFromEnglishTranslationAndResumesAtKoreanText() {
        String source = """
                (국문)
                -피부보습에 도움을 줄 수 있음
                (영문)
                -May help to maintain skin moisturizing
                ①지방, 탄수화물, 단백질 대사와 에너지 생성에 필요
                """;
        var product = extract(source);
        assertEquals(List.of("피부보습", "지방, 탄수화물, 단백질 대사와 에너지 생성에 필요"), product.getApprovedFunctions());
        assertFalse(product.isFunctionSourceReviewRequired());
    }

    @Test
    void englishOnlySourceIsNotMistakenForUsableKoreanClaims() {
        assertTrue(extract("(영문)\nMay help to maintain skin health").isFunctionSourceReviewRequired());
    }

    @Test
    void removesInlineAndWrappedEnglishTranslationWithoutLosingNextKoreanClaim() {
        assertEquals(List.of("노화로 저하된 인지기능 개선", "관절건강", "체내 에너지 생성에 필요"),
                parser.parsePrimaryFnclty("(국문) 노화로 저하된 인지기능 개선에 도움을 줄 수 있음\n"
                        + "관절건강에 도움을 줄 수 있음 (영문) May help to improve cognitive function\n"
                        + "degraded by aging\n①체내 에너지 생성에 필요"));
    }

    @Test
    void handlesNestedIngredientLabelsAndFunctionalSectionTitles() {
        assertEquals(List.of("질내 유익균 증식 및 유해균 억제", "유산균 증식 및 유해균 억제"),
                parser.parsePrimaryFnclty("주된 기능성\n[리스펙타(Respecta®)[프로바이오틱스 등 복합물](제2019-26호)]\n"
                        + "(국문) 질내 유익균 증식 및 유해균 억제에 도움을 줄 수 있음\n"
                        + "(영문) May help to proliferate beneficial flora\n"
                        + "[프로바이오틱스] 유산균 증식 및 유해균 억제에 도움을 줄 수 있음\nnull"));
        assertEquals(List.of("항산화"), parser.parsePrimaryFnclty("12. 기능성 내용\n(1) 토마토추출물: 항산화에 도움을 줄 수 있음"));
    }

    @Test
    void removesVitaminHeadingBeforeColonAndNumberedClaims() {
        assertEquals(List.of("어두운 곳에서 시각 적응을 위해 필요", "상피세포의 성장과 발달에 필요"),
                parser.parsePrimaryFnclty("비타민A\n: ①어두운 곳에서 시각 적응을 위해 필요②상피세포의 성장과 발달에 필요"));
    }

    @Test
    void recognizesBulletedIngredientHeadingsWithoutRemovingClaims() {
        assertEquals(List.of("알코올성 손상으로부터 간을 보호하는데", "결합조직 형성과 기능유지에 필요"),
                parser.parsePrimaryFnclty("·헛개나무과병추출분말(기능성원료인정제2016-10호)\n"
                        + "-알코올성 손상으로부터 간을 보호하는데 도움을 줄 수 있음\n"
                        + "·비타민C\n-결합조직 형성과 기능유지에 필요"));
    }

    @Test
    void doesNotRemoveShortClaimsOrAmbiguousFunctionalTextAsHeadings() {
        assertEquals(List.of("항산화", "정상적인 면역기능에 필요"), parser.parsePrimaryFnclty("항산화\n①정상적인 면역기능에 필요"));
        assertEquals(List.of("근육, 결합조직 등 신체조직의 구성성분", "에너지 생성에 필요"),
                parser.parsePrimaryFnclty("근육, 결합조직 등 신체조직의 구성성분\n①에너지 생성에 필요"));
    }

    @Test
    void manufacturingConditionIsPreservedAndStillRequiresCaution() {
        String condition = "[1) (2) (다)의 제조방법에 한함]";
        var candidates = parser.parsePrimaryFnclty("(가) 면역력 증진에 도움을 줄 수 있음\n(나) 간 건강에 도움을 줄 수 있음" + condition);
        assertEquals(2, candidates.size());
        assertTrue(candidates.get(1).contains(condition));
        assertTrue(extract("간 건강에 도움을 줄 수 있음" + condition).isFunctionSourceReviewRequired());
    }

    @Test
    void placeholdersAndBareIngredientNamesAreNotAcceptedAsFunctions() {
        assertTrue(extract("해당없음").isFunctionSourceReviewRequired());
        assertTrue(extract("비타민A").isFunctionSourceReviewRequired());
        assertTrue(extract("").isFunctionSourceReviewRequired());
    }

    @Test
    void leadingManufacturingConditionIsNotDiscardedAsIngredientLabel() {
        String condition = "[특정 제조방법에 한함]";
        assertTrue(String.join(" ", parser.parsePrimaryFnclty(condition + "\n간 건강에 도움을 줄 수 있음")).contains(condition));
        assertTrue(extract(condition + "\n간 건강에 도움을 줄 수 있음").isFunctionSourceReviewRequired());
    }
}
