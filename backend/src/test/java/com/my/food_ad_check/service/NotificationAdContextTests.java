package com.my.food_ad_check.service;

import com.my.food_ad_check.entity.*;
import com.my.food_ad_check.repository.*;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NotificationAdContextTests {
    private final ProductChangeNotificationRepository notifications = mock(ProductChangeNotificationRepository.class);
    private final InspectionRecordRepository inspections = mock(InspectionRecordRepository.class);
    private final ProductRepository products = mock(ProductRepository.class);
    private final NotificationAdContextService service = new NotificationAdContextService(notifications, inspections, products);

    @Test
    void foreignNotificationCannotExposeAnyAdText() {
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> service.get(9L, 10L)).getStatusCode().value());
        verifyNoInteractions(inspections, products);
    }

    @Test
    void retrievesLatestOwnAdAndBlocksUnreviewedCurrentData() {
        var notification = new ProductChangeNotification(9L,
                new ProductFunctionChange("R1", "old", "notified", LocalDateTime.now()));
        when(notifications.findByIdAndUserId(10L, 9L)).thenReturn(Optional.of(notification));
        var product = new Product();
        product.setPrimaryFnclty("even newer");
        product.setFunctionSourceReviewRequired(true);
        when(products.findById("R1")).thenReturn(Optional.of(product));
        var previous = mock(InspectionRecord.class);
        when(previous.getId()).thenReturn(30L);
        when(previous.getAdText()).thenReturn("본인의 마지막 광고");
        when(inspections.findFirstByUserIdAndPrdlstReportNoOrderByCreatedAtDescIdDesc(9L, "R1")).thenReturn(Optional.of(previous));
        var result = service.get(9L, 10L);
        assertEquals("본인의 마지막 광고", result.savedAdText());
        assertEquals("30", result.previousJobId());
        assertTrue(result.notificationSuperseded());
        assertTrue(result.productReviewRequired());
        assertFalse(result.canRecheck());
        assertTrue(result.currentAdConfirmationRequired());
        product.setFunctionSourceReviewRequired(false);
        assertTrue(service.get(9L, 10L).canRecheck());
    }
}
