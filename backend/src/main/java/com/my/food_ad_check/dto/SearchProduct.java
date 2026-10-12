package com.my.food_ad_check.dto;

import java.util.List;

/** Product fields used by the Vue search client. */
public record SearchProduct(
        String id,
        String name,
        String manufacturer,
        String reportNo,
        String type,
        List<String> approvedFunctions,
        String status
) {
}
