package com.my.food_ad_check.service;

import com.my.food_ad_check.repository.*;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class NotificationAdContextService {
    private final ProductChangeNotificationRepository notifications;
    private final InspectionRecordRepository inspections;
    private final ProductRepository products;
    public NotificationAdContextService(ProductChangeNotificationRepository notifications,
            InspectionRecordRepository inspections, ProductRepository products) {
        this.notifications = notifications;
        this.inspections = inspections;
        this.products = products;
    }

    public record Context(Long notificationId, String reportNo, String productName, String previousJobId,
                          String savedAdText, String notifiedSource, String currentSource,
                          boolean notificationSuperseded, boolean productReviewRequired,
                          boolean canRecheck, boolean currentAdConfirmationRequired, String message) { }

    @Transactional(readOnly = true)
    public Context get(Long userId, Long notificationId) {
        var notification = notifications.findByIdAndUserId(notificationId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "알림을 찾을 수 없습니다."));
        String reportNo = notification.getChange().getReportNo();
        var product = products.findById(reportNo).orElse(null);
        var previous = inspections.findFirstByUserIdAndPrdlstReportNoOrderByCreatedAtDescIdDesc(userId, reportNo).orElse(null);
        boolean review = product != null && product.isFunctionSourceReviewRequired();
        boolean canRecheck = product != null && previous != null && !review;
        return new Context(notificationId, reportNo, product == null ? null : product.getPrdlstNm(),
                previous == null ? null : previous.getId().toString(), previous == null ? null : previous.getAdText(),
                notification.getChange().getCurrentSource(), product == null ? null : product.getPrimaryFnclty(),
                product != null && !Objects.equals(notification.getChange().getCurrentSource(), product.getPrimaryFnclty()),
                review, canRecheck, true, product == null ? "현재 제품 정보를 확인할 수 없습니다."
                : review ? "최신 원문에서 기능성 목록을 자동 추출하지 못해 재검수를 보류했습니다."
                : previous == null ? "불러올 검사 기록이 없습니다. 광고를 새로 입력해 검사해주세요."
                : "불러온 문구가 현재 게재 중인 광고와 같은지 확인하고, 다르면 수정한 뒤 재검수해주세요.");
    }
}
