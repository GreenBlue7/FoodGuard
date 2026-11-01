package com.my.food_ad_check.service;

import com.my.food_ad_check.dto.LoginResponse;

/**
 * 로그인·갱신 결과. 컨트롤러가 access는 응답 본문으로, refresh는 HttpOnly 쿠키로 나눠 보낸다
 *
 * @param refresh 새로 발급한 Refresh Token. 쿠키를 바꿀 필요가 없으면 null (갱신 유예 시간 안의 중복 요청)
 */
public record AuthTokens(LoginResponse access, IssuedRefreshToken refresh) {
}
