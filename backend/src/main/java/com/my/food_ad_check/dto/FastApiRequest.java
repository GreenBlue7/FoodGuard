package com.my.food_ad_check.dto;

import java.util.List;

/** Matches the JSON accepted by judge.app.schemas.JudgeRequest. */
public record FastApiRequest(String requestId, String mode, Product product, String adText) {

    public record Product(String type, List<String> names, List<String> approvedFunctions) {
    }
}
