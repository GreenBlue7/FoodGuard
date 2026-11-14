package com.my.food_ad_check.service;

import com.my.food_ad_check.entity.*;
import com.my.food_ad_check.repository.*;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProductNotificationServiceTests {
    @Test
    void changedProductNotifiesOnlyItsInspectingUsers() {
        var products = mock(ProductRepository.class);
        var changes = mock(ProductFunctionChangeRepository.class);
        var inspections = mock(InspectionRecordRepository.class);
        var notifications = mock(ProductChangeNotificationRepository.class);
        when(inspections.findInspectingUserIds("R1")).thenReturn(List.of(2L, 5L));
        var change = new ProductFunctionChange("R1", "이전", "변경", LocalDateTime.now());
        new ProductImportWriter(products, changes, inspections, notifications).savePage(List.of(), List.of(change));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ProductChangeNotification>> saved = ArgumentCaptor.forClass(List.class);
        verify(notifications).saveAll(saved.capture());
        assertEquals(List.of(2L, 5L), saved.getValue().stream().map(ProductChangeNotification::getUserId).toList());
        assertSame(change, saved.getValue().get(0).getChange());
    }

    @Test
    void unchangedImportCreatesNoNotifications() {
        var inspections = mock(InspectionRecordRepository.class);
        var notifications = mock(ProductChangeNotificationRepository.class);
        new ProductImportWriter(mock(ProductRepository.class), mock(ProductFunctionChangeRepository.class),
                inspections, notifications).savePage(List.of(), List.of());
        verifyNoInteractions(inspections, notifications);
    }

    @Test
    void otherUsersCannotMarkNotificationRead() {
        var repo = mock(ProductChangeNotificationRepository.class);
        when(repo.findByIdAndUserId(10L, 9L)).thenReturn(Optional.empty());
        var error = assertThrows(ResponseStatusException.class,
                () -> new ProductNotificationService(repo).markRead(9L, 10L));
        assertEquals(404, error.getStatusCode().value());
    }

    @Test
    void repeatedReadPreservesFirstReadTime() {
        var repo = mock(ProductChangeNotificationRepository.class);
        var notification = new ProductChangeNotification(9L,
                new ProductFunctionChange("R1", "old", "new", LocalDateTime.now()));
        when(repo.findByIdAndUserId(10L, 9L)).thenReturn(Optional.of(notification));
        var service = new ProductNotificationService(repo);
        service.markRead(9L, 10L);
        var readAt = notification.getReadAt();
        service.markRead(9L, 10L);
        assertEquals(readAt, notification.getReadAt());
    }

    @Test
    void unreadListIsOwnerScopedAndPageSizeIsBounded() {
        var repo = mock(ProductChangeNotificationRepository.class);
        when(repo.findByUserIdAndReadAtIsNull(eq(9L), any(Pageable.class))).thenAnswer(call ->
                new PageImpl<ProductChangeNotification>(List.of(), call.getArgument(1), 0));
        new ProductNotificationService(repo).list(9L, -1, 1000, true);
        var page = ArgumentCaptor.forClass(Pageable.class);
        verify(repo).findByUserIdAndReadAtIsNull(eq(9L), page.capture());
        assertEquals(0, page.getValue().getPageNumber());
        assertEquals(50, page.getValue().getPageSize());
        verify(repo, never()).findByUserId(any(), any());
    }
}
