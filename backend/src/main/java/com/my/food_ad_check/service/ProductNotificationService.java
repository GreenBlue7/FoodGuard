package com.my.food_ad_check.service;

import com.my.food_ad_check.entity.ProductChangeNotification;
import com.my.food_ad_check.repository.ProductChangeNotificationRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ProductNotificationService {
    private final ProductChangeNotificationRepository notifications;
    public ProductNotificationService(ProductChangeNotificationRepository notifications) {
        this.notifications = notifications;
    }

    public record Item(Long id, String reportNo, String previousSource, String currentSource,
                       LocalDateTime detectedAt, LocalDateTime readAt, String message) { }
    public record NotificationPage(List<Item> items, int page, int size, long totalElements,
                                   int totalPages, long unreadCount) { }

    @Transactional(readOnly = true)
    public NotificationPage list(Long userId, int page, int size, boolean unreadOnly) {
        var pageable = PageRequest.of(Math.max(0, page), Math.min(50, Math.max(1, size)),
                Sort.by(Sort.Direction.DESC, "id"));
        var result = unreadOnly ? notifications.findByUserIdAndReadAtIsNull(userId, pageable)
                : notifications.findByUserId(userId, pageable);
        var items = result.map(n -> new Item(n.getId(), n.getChange().getReportNo(),
                n.getChange().getPreviousSource(), n.getChange().getCurrentSource(),
                n.getChange().getDetectedAt(), n.getReadAt(),
                "이전에 검수한 제품의 식약처 기능성 원문이 변경됐습니다. 광고 문구를 확인하고 재검수해주세요."
                        + " 재검수 시 최신 원문에서 자동 추출한 기능성 목록을 사용합니다.")).getContent();
        return new NotificationPage(items, result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages(), notifications.countByUserIdAndReadAtIsNull(userId));
    }

    @Transactional
    public void markRead(Long userId, Long id) {
        ProductChangeNotification notification = notifications.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "알림을 찾을 수 없습니다."));
        notification.markRead();
    }
}
