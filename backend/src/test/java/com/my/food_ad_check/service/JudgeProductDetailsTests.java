package com.my.food_ad_check.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.my.food_ad_check.entity.Product;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JudgeProductDetailsTests {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void serializesRequiredNamesAndTypesAndPreservesSourceText() throws Exception {
        Product product = new Product();
        product.setPrdlstNm("제품");
        product.setPrimaryFnclty("  [기능성]\n① 원문 그대로  ");
        assertTrue(JudgeProductDetails.update(product, json.readTree("""
                {"RAWMTRL_NM":"홍삼, 복합원료(비타민C, 아연)",
                 "IFTKN_ATNT_MATR_CN":"임산부, 수유부는 상담하십시오.\\n이상사례 발생 시 중단하십시오.",
                 "NTK_MTHD":"  1일 1회  ","INDIV_RAWMTRL_NM":"쌀, 전분",
                 "ETC_RAWMTRL_NM":"전분;덱스트린","CAP_RAWMTRL_NM":"젤라틴",
                 "STDR_STND":"지표성분: 10 mg/g 이상"}
                """)));
        var request = JudgeProductDetails.request(product, "HEALTH_FUNCTIONAL", List.of("기존 기능성"));
        var body = json.readTree(json.writeValueAsString(request));
        assertEquals(List.of("홍삼", "복합원료(비타민C, 아연)"), request.ingredients());
        assertEquals(product.getPrimaryFnclty(), body.path("functionSource").textValue());
        assertEquals(List.of("임산부, 수유부는 상담하십시오.", "이상사례 발생 시 중단하십시오."), request.cautions());
        assertEquals("  1일 1회  ", body.path("intake").textValue());
        assertEquals(List.of("쌀", "전분", "덱스트린", "젤라틴"), request.otherIngredients());
        assertTrue(body.path("ingredients").isArray());
        assertTrue(body.path("cautions").isArray());
        assertTrue(body.path("otherIngredients").isArray());
        assertEquals("지표성분: 10 mg/g 이상", body.path("standards").textValue());
        assertEquals(List.of("기존 기능성"), request.approvedFunctions());
    }

    @Test
    void missingAndMalformedFieldsDoNotEraseStoredValuesButExplicitEmptyClears() throws Exception {
        Product product = new Product();
        product.setIntake("섭취 방법");
        assertFalse(JudgeProductDetails.update(product, json.readTree("{}")));
        assertFalse(JudgeProductDetails.update(product, json.readTree("{\"NTK_MTHD\":123}")));
        assertEquals("섭취 방법", product.getIntake());
        assertTrue(JudgeProductDetails.update(product, json.readTree("{\"NTK_MTHD\":\"\"}")));
        assertEquals("", product.getIntake());
        assertTrue(JudgeProductDetails.update(product, json.readTree("{\"NTK_MTHD\":null}")));
        assertNull(product.getIntake());
    }

    @Test
    void noSelectedProductHasEmptyListsAndUnknownStrings() {
        var request = JudgeProductDetails.request(null, "UNKNOWN", List.of());
        assertEquals(List.of(), request.ingredients());
        assertEquals(List.of(), request.cautions());
        assertEquals(List.of(), request.otherIngredients());
        assertEquals("", request.functionSource());
        assertEquals("", request.intake());
        assertEquals("", request.standards());
    }

    @Test
    void preservesSafetyConditionsAndNestedIngredientDelimiters() {
        assertEquals(List.of("가(나[다,라],마)", "바"), JudgeProductDetails.ingredients("가(나[다,라],마), 바"));
        assertEquals(List.of("① 임산부, 수유부는 주의 ② 의약품 복용 시 상담"),
                JudgeProductDetails.cautions("① 임산부, 수유부는 주의 ② 의약품 복용 시 상담"));
        assertEquals(List.of("닫히지 않은 원료(가, 나, 다"), JudgeProductDetails.ingredients("닫히지 않은 원료(가, 나, 다"));
    }
}
