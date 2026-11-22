package com.my.food_ad_check.dto;

import java.util.List;

/** Matches the JSON accepted by judge.app.schemas.JudgeRequest. */
public record FastApiRequest(String requestId, String mode, Product product, String adText) {

    public record Product(String type, List<String> names, List<String> approvedFunctions,
                          List<String> ingredients, String functionSource, List<String> cautions,
                          String intake, List<String> otherIngredients, String standards) {
        public Product(String type, List<String> names, List<String> approvedFunctions) {
            this(type, names, approvedFunctions, List.of(), "", List.of(), "", List.of(), "");
        }
    }
}
