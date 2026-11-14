package com.my.food_ad_check.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.my.food_ad_check.dto.*;
import com.my.food_ad_check.entity.InspectionRecord;
import com.my.food_ad_check.repository.*;
import com.my.food_ad_check.service.*;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReinspectionTests {
    private final InspectionRecordRepository records = mock(InspectionRecordRepository.class);
    private final AiJudgmentService judge = mock(AiJudgmentService.class);
    private final ProductRepository products = mock(ProductRepository.class);
    private final Jwt jwt = Jwt.withTokenValue("test").header("alg", "HS256").subject("9").build();
    private final InspectionJobController controller = new InspectionJobController(products,
            records, mock(ProductApiService.class), judge, mock(RuleCheckService.class), mock(InspectionHistoryService.class));

    private InspectionRecord previous() {
        var record = mock(InspectionRecord.class);
        when(record.getId()).thenReturn(10L);
        when(record.getAdText()).thenReturn("이전 문구");
        when(record.getResultJson()).thenReturn("""
                {"request":{"productIds":[],"productTypeAnswer":"GENERAL","adText":"이전 문구"},
                 "judgeResponse":{"results":[{"status":"VIOLATION"}],"model":"mock","kbVersion":"2026-11-01"}}
                """);
        when(record.getStatus()).thenReturn("DONE");
        when(records.findByIdAndUserId(10L, 9L)).thenReturn(Optional.of(record));
        return record;
    }

    @Test
    void otherUsersRecordCannotBeReinspected() {
        assertEquals(404, assertThrows(ResponseStatusException.class,
                () -> controller.reinspect(jwt, 10L, new InspectionJobController.ReinspectionRequest("수정")))
                .getStatusCode().value());
        verifyNoInteractions(judge);
        verify(records, never()).save(any());
    }

    @Test
    void unchangedOrBlankTextDoesNotCallJudge() {
        previous();
        for (String text : List.of("이전 문구", "  ")) {
            assertEquals(400, assertThrows(ResponseStatusException.class,
                    () -> controller.reinspect(jwt, 10L, new InspectionJobController.ReinspectionRequest(text)))
                    .getStatusCode().value());
        }
        verifyNoInteractions(judge);
    }

    @Test
    void editedTextCreatesLinkedRecordAndUsesOriginalProductType() throws Exception {
        previous();
        when(judge.requestJudgmentOutcome(any())).thenReturn(new AiJudgmentService.JudgmentOutcome(
                new FastApiResponse(List.of(), List.of(), "mock", "2026-11-01", 1L), "DONE"));
        var saved = mock(InspectionRecord.class);
        when(saved.getId()).thenReturn(11L);
        when(records.save(any())).thenReturn(saved);
        var result = controller.reinspect(jwt, 10L, new InspectionJobController.ReinspectionRequest("수정 문구"));
        assertEquals(Map.of("jobId", "11", "previousJobId", "10"), result);
        var request = ArgumentCaptor.forClass(FastApiRequest.class);
        verify(judge).requestJudgmentOutcome(request.capture());
        assertEquals("수정 문구", request.getValue().adText());
        assertEquals("GENERAL_FOOD", request.getValue().product().type());
        var record = ArgumentCaptor.forClass(InspectionRecord.class);
        verify(records).save(record.capture());
        assertEquals(10L, record.getValue().getPreviousInspectionId());
        assertEquals(9L, record.getValue().getUserId());
        var body = new ObjectMapper().readTree(record.getValue().getResultJson());
        assertTrue(body.path("comparison").path("bothCompleted").asBoolean());
        assertFalse(body.path("comparison").path("sameRecordedCriteria").asBoolean());
        assertEquals(1, body.path("comparison").path("previousCounts").path("VIOLATION").asInt());
    }

    @Test
    void failedReinspectionIsNotPresentedAsResolved() {
        previous();
        when(judge.requestJudgmentOutcome(any())).thenReturn(new AiJudgmentService.JudgmentOutcome(null, "TIMEOUT"));
        var saved = mock(InspectionRecord.class);
        when(saved.getId()).thenReturn(11L);
        when(records.save(any())).thenReturn(saved);
        controller.reinspect(jwt, 10L, new InspectionJobController.ReinspectionRequest("수정"));
        var record = ArgumentCaptor.forClass(InspectionRecord.class);
        verify(records).save(record.capture());
        assertEquals("TIMEOUT", record.getValue().getStatus());
        assertTrue(record.getValue().getResultJson().contains("\"bothCompleted\":false"));
        assertTrue(record.getValue().getResultJson().contains("\"currentCounts\":null"));
    }

    @Test
    void sameCurrentAdIsAllowedOnlyAfterConfirmation() {
        previous();
        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> controller.recheck(jwt, 10L, new InspectionJobController.CurrentAdRequest("이전 문구", false)))
                .getStatusCode().value());
        verifyNoInteractions(judge);
        when(judge.requestJudgmentOutcome(any())).thenReturn(new AiJudgmentService.JudgmentOutcome(null, "FAILED"));
        var saved = mock(InspectionRecord.class);
        when(saved.getId()).thenReturn(11L);
        when(records.save(any())).thenReturn(saved);
        assertEquals("11", controller.recheck(jwt, 10L,
                new InspectionJobController.CurrentAdRequest("이전 문구", true)).get("jobId"));
    }

    @Test
    void failedAutomaticExtractionNeverSendsOldFunctionsToJudge() {
        var product = new com.my.food_ad_check.entity.Product();
        product.setFunctionSourceReviewRequired(true);
        product.setApprovedFunctions(List.of("옛 기능"));
        when(products.findById("R1")).thenReturn(Optional.of(product));
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> controller.create(jwt, new InspectionJobRequest(List.of("R1"), null, "현재 광고")))
                .getStatusCode().value());
        verifyNoInteractions(judge);
        verify(records, never()).save(any());
    }

    @Test
    void productChangedDuringJudgeCallCannotBeSavedAsCurrentResult() {
        var product = new com.my.food_ad_check.entity.Product();
        product.setPrdlstReportNo("R1");
        product.setPrdlstNm("제품");
        product.setProductType("건강기능식품");
        product.setApprovedFunctions(List.of("면역력 증진"));
        product.setVersion(1L);
        when(products.findById("R1")).thenReturn(Optional.of(product));
        when(products.findCurrentVersion("R1")).thenReturn(Optional.of(2L));
        when(judge.requestJudgmentOutcome(any())).thenReturn(new AiJudgmentService.JudgmentOutcome(null, "FAILED"));
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> controller.create(jwt, new InspectionJobRequest(List.of("R1"), null, "현재 광고")))
                .getStatusCode().value());
        verify(records, never()).save(any());
    }
}
