package com.my.food_ad_check.dto;

import java.util.List;

/** Request shape currently sent by the Vue inspection client. */
public record InspectionJobRequest(List<String> productIds, String productTypeAnswer, String adText) {
}
