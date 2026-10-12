package com.my.food_ad_check.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.my.food_ad_check.dto.FastApiRequest;
import com.my.food_ad_check.dto.FastApiResponse;
import com.my.food_ad_check.dto.InspectionJobRequest;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class InspectionJobControllerTests {

    @Mock private ProductRepository productRepository;
    @Mock private InspectionRecordRepository inspectionRecordRepository;
    @Mock private ProductApiService productApiService;
    @Mock private AiJudgmentService aiJudgmentService;
    @Mock private RuleCheckService ruleCheckService;

    private InspectionJobController controller;

    @BeforeEach
    void setUp() {
        controller = new InspectionJobController(productRepository, inspectionRecordRepository,
                productApiService, aiJudgmentService, ruleCheckService);
    }

    @Test
    void unknownProductStillCallsJudgeAndCanBeReadByJobId() {
        when(aiJudgmentService.requestJudgment(any())).thenReturn(
                new FastApiResponse(List.of(), List.of(), "mock", "2026-10-13", 1L));
        InspectionRecord saved = org.mockito.Mockito.mock(InspectionRecord.class);
        when(saved.getId()).thenReturn(42L);
        when(inspectionRecordRepository.save(any())).thenReturn(saved);

        Map<String, String> created = controller.create(
                new InspectionJobRequest(List.of("missing"), "UNKNOWN", "광고 문구"));

        assertEquals("42", created.get("jobId"));
        ArgumentCaptor<FastApiRequest> judgeRequest = ArgumentCaptor.forClass(FastApiRequest.class);
        verify(aiJudgmentService).requestJudgment(judgeRequest.capture());
        assertEquals("UNKNOWN", judgeRequest.getValue().product().type());
        ArgumentCaptor<InspectionRecord> record = ArgumentCaptor.forClass(InspectionRecord.class);
        verify(inspectionRecordRepository).save(record.capture());
        when(inspectionRecordRepository.findById(42L)).thenReturn(Optional.of(record.getValue()));

        Map<String, Object> loaded = controller.get("42");
        assertEquals("DONE", loaded.get("status"));
        assertEquals("UNVERIFIED", loaded.get("productInfoSource"));
        assertNull(loaded.get("layer2"));
        assertEquals("mock", ((Map<?, ?>) loaded.get("judgeResponse")).get("model"));
    }

    @Test
    void unregisteredGeneralFoodAnswerSelectsGeneralFoodForJudge() {
        InspectionRecord saved = savedRecord(1L);
        when(inspectionRecordRepository.save(any())).thenReturn(saved);

        controller.create(new InspectionJobRequest(List.of(), "GENERAL", "광고 문구"));

        ArgumentCaptor<FastApiRequest> judgeRequest = ArgumentCaptor.forClass(FastApiRequest.class);
        verify(aiJudgmentService).requestJudgment(judgeRequest.capture());
        assertEquals("GENERAL_FOOD", judgeRequest.getValue().product().type());
    }

    @Test
    void moreThanOneProductIsRejectedBeforeAnyProcessing() {
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> controller.create(new InspectionJobRequest(List.of("one", "two"), null, "광고 문구")));

        assertEquals(400, error.getStatusCode().value());
        verify(aiJudgmentService, never()).requestJudgment(any());
        verify(inspectionRecordRepository, never()).save(any());
    }

    private static InspectionRecord savedRecord(long id) {
        InspectionRecord record = org.mockito.Mockito.mock(InspectionRecord.class);
        when(record.getId()).thenReturn(id);
        return record;
    }
}
