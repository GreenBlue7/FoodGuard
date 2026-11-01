package com.my.food_ad_check.dto;

/**
 * @param tokenType 항상 "Bearer". 요청 시 {@code Authorization: Bearer <accessToken>}
 * @param expiresIn Access Token이 만료될 때까지 남은 초
 */
public record LoginResponse(String accessToken, String tokenType, long expiresIn) {
}
