package com.my.food_ad_check.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

@Entity
@Table(name = "inspection_records")
public class InspectionRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "prdlst_report_no")
    private String prdlstReportNo;

    @Column(name = "prdlst_nm")
    private String prdlstNm;

    @Column(name = "bssh_nm")
    private String bsshNm;

    @Column(name = "product_type")
    private String productType;

    @Column(name = "primary_fnclty", columnDefinition = "TEXT")
    private String primaryFnclty;

    @Column(name = "ad_text", nullable = false, columnDefinition = "TEXT")
    private String adText;

    @Column(nullable = false)
    private String status;

    @Column(name = "result_json", nullable = false, columnDefinition = "TEXT")
    private String resultJson;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    // 검수를 요청한 회원. 회원이 삭제되면 DB의 ON DELETE CASCADE로 이 기록도 삭제된다
    @Column(name = "user_id", nullable = false)
    private Long userId;

    protected InspectionRecord() {
    }

    public InspectionRecord(Long userId, String prdlstReportNo, String prdlstNm, String bsshNm,
                            String productType, String primaryFnclty, String adText, String status,
                            String resultJson, LocalDateTime createdAt) {
        this.userId = userId;
        this.prdlstReportNo = prdlstReportNo;
        this.prdlstNm = prdlstNm;
        this.bsshNm = bsshNm;
        this.productType = productType;
        this.primaryFnclty = primaryFnclty;
        this.adText = adText;
        this.status = status;
        this.resultJson = resultJson;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public String getPrdlstReportNo() { return prdlstReportNo; }
    public String getPrdlstNm() { return prdlstNm; }
    public String getBsshNm() { return bsshNm; }
    public String getProductType() { return productType; }
    public String getPrimaryFnclty() { return primaryFnclty; }
    public String getAdText() { return adText; }
    public String getStatus() { return status; }
    public String getResultJson() { return resultJson; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public Long getUserId() { return userId; }
}
