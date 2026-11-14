package com.my.food_ad_check.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "product_change_notifications",
        uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "change_id"}))
public class ProductChangeNotification {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "user_id", nullable = false)
    private Long userId;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "change_id", nullable = false)
    private ProductFunctionChange change;
    @Column(name = "read_at")
    private LocalDateTime readAt;

    protected ProductChangeNotification() { }

    public ProductChangeNotification(Long userId, ProductFunctionChange change) {
        this.userId = userId;
        this.change = change;
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public ProductFunctionChange getChange() { return change; }
    public LocalDateTime getReadAt() { return readAt; }
    public void markRead() { if (readAt == null) readAt = LocalDateTime.now(); }
}
