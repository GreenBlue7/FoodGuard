package com.my.food_ad_check.dto;

import java.util.List;

public record ProductSummary(
        String prdlstReportNo,
        String prdlstNm,
        String bsshNm,
        String productType,
        String primaryFnclty,
        List<String> approvedFunctions
) {
}
