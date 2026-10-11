package com.my.food_ad_check.dto;

public record InspectionRequest(
        String prdlstReportNo,
        String prdlstNm,
        String bsshNm,
        String productType,
        String primaryFnclty,
        String adText
) {
}
