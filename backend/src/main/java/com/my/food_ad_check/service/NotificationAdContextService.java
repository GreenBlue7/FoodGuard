package com.my.food_ad_check.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private final ObjectMapper json = new ObjectMapper();
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
        boolean busy = previous != null && ("PENDING".equals(previous.getStatus()) || "RUNNING".equals(previous.getStatus()));
        boolean image = previous != null && isImage(previous.getResultJson());
        boolean canRecheck = product != null && previous != null && !review && !busy && !image;
        return new Context(notificationId, reportNo, product == null ? null : product.getPrdlstNm(),
                previous == null ? null : previous.getId().toString(), previous == null ? null : previous.getAdText(),
                notification.getChange().getCurrentSource(), product == null ? null : product.getPrimaryFnclty(),
                product != null && !Objects.equals(notification.getChange().getCurrentSource(), product.getPrimaryFnclty()),
                review, canRecheck, true, product == null ? "현재 제품 정보를 확인할 수 없습니다."
                : review ? "최신 원문에서 기능성 목록을 자동 추출하지 못해 재검수를 보류했습니다."
                : previous == null ? "불러올 검사 기록이 없습니다. 광고를 새로 입력해 검사해주세요."
                : busy ? "이미 접수된 검사가 진행 중입니다. 검사 결과를 확인한 뒤 재검수해주세요."
                : image ? "최근에 검사한 광고는 이미지입니다. 현재 광고 이미지를 다시 올려 검사해 주세요."
                : "불러온 문구가 현재 게재 중인 광고와 같은지 확인하고, 다르면 수정한 뒤 재검수해주세요.");
    }

    private boolean isImage(String resultJson) {
        if (resultJson == null) return false;
        try {
            var result = json.readTree(resultJson);
            return result != null && "IMAGE".equals(result.path("inspectionKind").asText());
        } catch (JsonProcessingException e) {
            return false;
        }
    }
}
