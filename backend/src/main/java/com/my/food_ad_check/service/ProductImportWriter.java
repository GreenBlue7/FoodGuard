package com.my.food_ad_check.service;

import com.my.food_ad_check.entity.Product;
import com.my.food_ad_check.entity.ProductFunctionChange;
import com.my.food_ad_check.entity.ProductChangeNotification;
import com.my.food_ad_check.repository.InspectionRecordRepository;
import com.my.food_ad_check.repository.ProductChangeNotificationRepository;
import com.my.food_ad_check.repository.ProductRepository;
import com.my.food_ad_check.repository.ProductFunctionChangeRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductImportWriter {
    private final ProductRepository products;
    private final ProductFunctionChangeRepository changes;
    private final InspectionRecordRepository inspections;
    private final ProductChangeNotificationRepository notifications;
    private final NotificationEmailQueue emails;

    public ProductImportWriter(ProductRepository products, ProductFunctionChangeRepository changes,
                               InspectionRecordRepository inspections,
                               ProductChangeNotificationRepository notifications) {
        this(products, changes, inspections, notifications, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ProductImportWriter(ProductRepository products, ProductFunctionChangeRepository changes,
                               InspectionRecordRepository inspections,
                               ProductChangeNotificationRepository notifications, NotificationEmailQueue emails) {
        this.products = products;
        this.changes = changes;
        this.inspections = inspections;
        this.notifications = notifications;
        this.emails = emails;
    }

    /** The imported page and its audit records either both commit or both roll back. */
    @Transactional
    public void savePage(List<Product> page, List<ProductFunctionChange> sourceChanges) {
        products.saveAll(page);
        changes.saveAll(sourceChanges);
        for (ProductFunctionChange change : sourceChanges) {
            var saved = notifications.saveAll(inspections.findInspectingUserIds(change.getReportNo()).stream()
                    .map(userId -> new ProductChangeNotification(userId, change)).toList());
            if (emails != null) saved.forEach(notification -> emails.enqueue(notification.getId()));
        }
    }
}
