package com.my.food_ad_check.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import com.my.food_ad_check.service.InspectionJobQueue;
import com.my.food_ad_check.service.InspectionHistoryService;
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
    @Mock private InspectionJobQueue queue;
    @Mock private RuleCheckService ruleCheckService;
    @Mock private InspectionHistoryService inspectionHistoryService;

    private InspectionJobController controller;

    @BeforeEach
    void setUp() {
        controller = new InspectionJobController(productRepository, inspectionRecordRepository,
                productApiService, queue, ruleCheckService, inspectionHistoryService);
    }

    @Test
    void unknownProductQueuesSnapshotAndCanBeReadWhilePending() {
        InspectionRecord saved = org.mockito.Mockito.mock(InspectionRecord.class);
        when(saved.getId()).thenReturn(42L);
        when(queue.enqueue(any(), any(), any())).thenReturn(saved);

        Map<String, String> created = controller.create(jwt(),
                new InspectionJobRequest(List.of("missing"), "UNKNOWN", "광고 문구"));

        assertEquals("42", created.get("jobId"));
        ArgumentCaptor<FastApiRequest> judgeRequest = ArgumentCaptor.forClass(FastApiRequest.class);
        verify(queue).enqueue(any(), judgeRequest.capture(), any());
        assertEquals("UNKNOWN", judgeRequest.getValue().product().type());
        ArgumentCaptor<InspectionRecord> record = ArgumentCaptor.forClass(InspectionRecord.class);
        verify(queue).enqueue(record.capture(), any(), any());
        when(inspectionRecordRepository.findByIdAndUserId(42L, 9L)).thenReturn(Optional.of(record.getValue()));

        Map<String, Object> loaded = controller.get(jwt(), "42");
        assertEquals("PENDING", loaded.get("status"));
        assertEquals("UNVERIFIED", loaded.get("productInfoSource"));
        assertNull(loaded.get("layer2"));
    }

    @Test
    void unregisteredGeneralFoodAnswerQueuesGeneralFoodSnapshot() {
        InspectionRecord saved = savedRecord(1L);
        when(queue.enqueue(any(), any(), any())).thenReturn(saved);

        controller.create(jwt(), new InspectionJobRequest(List.of(), "GENERAL", "광고 문구"));

        ArgumentCaptor<FastApiRequest> judgeRequest = ArgumentCaptor.forClass(FastApiRequest.class);
        verify(queue).enqueue(any(), judgeRequest.capture(), any());
        assertEquals("GENERAL_FOOD", judgeRequest.getValue().product().type());
    }

    @Test
    void moreThanOneProductIsRejectedBeforeAnyProcessing() {
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> controller.create(jwt(), new InspectionJobRequest(List.of("one", "two"), null, "광고 문구")));

        assertEquals(400, error.getStatusCode().value());
        verify(queue, never()).enqueue(any(), any(), any());
        verify(inspectionRecordRepository, never()).save(any());
    }

    @Test
    void submissionIsPendingWithoutLayer2() {
        InspectionRecord saved = savedRecord(7L);
        when(queue.enqueue(any(), any(), any())).thenReturn(saved);

        controller.create(jwt(), new InspectionJobRequest(List.of(), "UNKNOWN", "광고 문구"));

        ArgumentCaptor<InspectionRecord> record = ArgumentCaptor.forClass(InspectionRecord.class);
        verify(queue).enqueue(record.capture(), any(), any());
        assertEquals("PENDING", record.getValue().getStatus());
        when(inspectionRecordRepository.findByIdAndUserId(7L, 9L)).thenReturn(Optional.of(record.getValue()));
        Map<String, Object> loaded = controller.get(jwt(), "7");
        assertEquals("PENDING", loaded.get("status"));
        assertNull(loaded.get("layer2"));
    }

    @Test
    void invalidRequestsAreRejectedBeforeCallingJudge() {
        List<InspectionJobRequest> invalid = List.of(
                new InspectionJobRequest(List.of(), null, " "),
                new InspectionJobRequest(List.of(" "), null, "광고"),
                new InspectionJobRequest(List.of(), "NOT_A_TYPE", "광고"));
        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> controller.create(jwt(), null)).getStatusCode().value());
        for (InspectionJobRequest request : invalid) {
            assertEquals(400, assertThrows(ResponseStatusException.class,
                    () -> controller.create(jwt(), request)).getStatusCode().value());
        }
        verify(queue, never()).enqueue(any(), any(), any());
        verify(inspectionRecordRepository, never()).save(any());
    }

    private static org.springframework.security.oauth2.jwt.Jwt jwt() {
        return org.springframework.security.oauth2.jwt.Jwt.withTokenValue("test").header("alg", "HS256").subject("9").build();
    }

    private static InspectionRecord savedRecord(long id) {
        InspectionRecord record = org.mockito.Mockito.mock(InspectionRecord.class);
        when(record.getId()).thenReturn(id);
        return record;
    }
}
