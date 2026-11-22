package com.my.food_ad_check.entity;

import jakarta.persistence.*;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Entity
@Getter
@Setter
@Table(name = "products")
public class Product {

    @Version
    @Column(nullable = false)
    private Long version;

    @Id
    @Column(name = "prdlst_report_no")
    private String prdlstReportNo; // 품목제조신고번호 (기본키)

    @Column(name = "prdlst_nm", nullable = false)
    private String prdlstNm; // 제품명

    @Column(name = "bssh_nm")
    private String bsshNm; // 제조사명

    @Column(name = "primary_fnclty", columnDefinition = "TEXT")
    private String primaryFnclty; // 주된기능성 (자유 텍스트 형태이므로 길게 설정)[cite: 8, 10]

    // Keep I0030 source values; list formatting happens only at the judge boundary.
    @Column(name = "ingredients_source", columnDefinition = "TEXT")
    private String ingredientsSource;
    @Column(name = "cautions_source", columnDefinition = "TEXT")
    private String cautionsSource;
    @Column(name = "intake", columnDefinition = "TEXT")
    private String intake;
    @Column(name = "individual_ingredients_source", columnDefinition = "TEXT")
    private String individualIngredientsSource;
    @Column(name = "etc_ingredients_source", columnDefinition = "TEXT")
    private String etcIngredientsSource;
    @Column(name = "capsule_ingredients_source", columnDefinition = "TEXT")
    private String capsuleIngredientsSource;
    @Column(name = "standards", columnDefinition = "TEXT")
    private String standards;

    @Column(name = "product_type", nullable = false)
    private String productType; // 제품 종류 (예: 건강기능식품)

    @Column(name = "function_source_review_required", nullable = false)
    private boolean functionSourceReviewRequired;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "product_approved_functions",
            joinColumns = @JoinColumn(name = "prdlst_report_no"))
    @OrderColumn(name = "position")
    @Column(name = "function_text", nullable = false, columnDefinition = "TEXT")
    private List<String> approvedFunctions = new ArrayList<>();
}
