package com.my.food_ad_check.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Getter
@Setter
@Table(name = "products")
public class Product {

    @Id
    @Column(name = "prdlst_report_no")
    private String prdlstReportNo; // 품목제조신고번호 (기본키)

    @Column(name = "prdlst_nm")
    private String prdlstNm; // 제품명

    @Column(name = "bssh_nm")
    private String bsshNm; // 제조사명

    @Column(name = "primary_fnclty", columnDefinition = "TEXT")
    private String primaryFnclty; // 주된기능성 (자유 텍스트 형태이므로 길게 설정)[cite: 8, 10]

    @Column(name = "product_type")
    private String productType; // 제품 종류 (예: 건강기능식품)
}
