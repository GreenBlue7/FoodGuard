package com.my.food_ad_check.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** A source-text change observed during an explicit MFDS import, not a legal finding. */
@Entity
@Table(name = "product_function_changes")
public class ProductFunctionChange {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "prdlst_report_no", nullable = false)
    private String reportNo;
    @Column(name = "previous_source", columnDefinition = "TEXT")
    private String previousSource;
    @Column(name = "current_source", columnDefinition = "TEXT")
    private String currentSource;
    @Column(name = "detected_at", nullable = false)
    private LocalDateTime detectedAt;

    protected ProductFunctionChange() { }

    public ProductFunctionChange(String reportNo, String previousSource, String currentSource,
                                 LocalDateTime detectedAt) {
        this.reportNo = reportNo;
        this.previousSource = previousSource;
        this.currentSource = currentSource;
        this.detectedAt = detectedAt;
    }

    public Long getId() { return id; }
    public String getReportNo() { return reportNo; }
    public String getPreviousSource() { return previousSource; }
    public String getCurrentSource() { return currentSource; }
    public LocalDateTime getDetectedAt() { return detectedAt; }
}
