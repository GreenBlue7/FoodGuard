package com.my.food_ad_check.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.my.food_ad_check.dto.InspectionRequest;
import com.my.food_ad_check.entity.InspectionRecord;
import com.my.food_ad_check.entity.Product;
import com.my.food_ad_check.repository.InspectionRecordRepository;
import com.my.food_ad_check.repository.ProductRepository;
import com.my.food_ad_check.service.AiJudgmentService;
import com.my.food_ad_check.service.ProductApiService;
import com.my.food_ad_check.service.RuleCheckService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class InspectionFlowControllerTests {

    @Mock private ProductRepository productRepository;
    @Mock private InspectionRecordRepository inspectionRecordRepository;
    @Mock private ProductApiService productApiService;
    @Mock private AiJudgmentService aiJudgmentService;
    @Mock private RuleCheckService ruleCheckService;

    private ProductController productController;
    private InspectionController inspectionController;

    @BeforeEach
    void setUp() {
        productController = new ProductController(productRepository);
        inspectionController = new InspectionController(productRepository, inspectionRecordRepository,
                productApiService, aiJudgmentService, ruleCheckService);
    }

    @Test
    void productSearchReturnsExactReportNumberMatch() {
        Product product = product("R-001", "홍삼정");
        when(productRepository.findById("R-001")).thenReturn(Optional.of(product));

        var results = productController.search(" R-001 ");

        assertEquals(1, results.size());
        assertEquals("R-001", results.get(0).prdlstReportNo());
        verify(productRepository, never()).findTop20ByPrdlstNmContainingIgnoreCaseOrderByPrdlstNmAsc(any());
    }

    @Test
    void productSearchUsesNameWhenReportNumberDoesNotMatch() {
        when(productRepository.findById("홍삼")).thenReturn(Optional.empty());
        when(productRepository.findTop20ByPrdlstNmContainingIgnoreCaseOrderByPrdlstNmAsc("홍삼"))
                .thenReturn(List.of(product("R-002", "홍삼정 골드")));

        var results = productController.search("홍삼");

        assertEquals("홍삼정 골드", results.get(0).prdlstNm());
    }

    @Test
    void unknownProductTypeIsSavedAsReviewAndSkipsAiJudgment() {
        when(inspectionRecordRepository.save(any(InspectionRecord.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        Map<String, Object> result = inspectionController.processInspection(
                new InspectionRequest(null, "미등록 제품", null, null, null, "건강에 좋습니다."));

        assertEquals("REVIEW_REQUIRED", result.get("status"));
        assertEquals("REVIEW", ((Map<?, ?>) result.get("step1")).get("status"));
        assertEquals("SKIPPED", ((Map<?, ?>) result.get("step2")).get("status"));
        assertEquals("UNCONFIRMED", ((Map<?, ?>) result.get("productInfo")).get("source"));
        verify(inspectionRecordRepository).save(any(InspectionRecord.class));
        verify(aiJudgmentService, never()).requestJudgment(any());
    }

    @Test
    void blankAdTextIsRejectedBeforeCallingServices() {
        var exception = assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> inspectionController.processInspection(
                        new InspectionRequest(null, null, null, null, null, "  ")));
        assertEquals(400, exception.getStatusCode().value());
    }

    private static Product product(String reportNo, String name) {
        Product product = new Product();
        product.setPrdlstReportNo(reportNo);
        product.setPrdlstNm(name);
        product.setBsshNm("제조사");
        product.setProductType("건강기능식품");
        product.setPrimaryFnclty("면역력 증진");
        return product;
    }
}
