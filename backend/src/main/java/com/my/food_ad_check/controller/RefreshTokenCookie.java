package com.my.food_ad_check.controller;

import com.my.food_ad_check.config.JwtProperties;
import com.my.food_ad_check.service.IssuedRefreshToken;
import java.time.Duration;
import org.springframework.http.ResponseCookie;

// Refresh Token을 담는 쿠키의 속성을 한곳에서 정한다
final class RefreshTokenCookie {

    static final String NAME = "refresh_token";
    // 인증 API(/refresh, /logout)에만 쿠키가 실려 가고, 검수 등 다른 API 요청에는 붙지 않음
    private static final String PATH = "/api/auth";

    private final boolean secure;

    RefreshTokenCookie(JwtProperties properties) {
        this.secure = properties.refreshCookieSecure();
    }

    ResponseCookie issue(IssuedRefreshToken token) {
        return build(token.value(), token.ttl());
    }

    // 같은 이름·경로로 수명 0인 쿠키를 보내면 브라우저가 지움
    ResponseCookie clear() {
        return build("", Duration.ZERO);
    }

    private ResponseCookie build(String value, Duration maxAge) {
        return ResponseCookie.from(NAME, value)
                .httpOnly(true) // JS(document.cookie)에서 읽을 수 없음 → XSS로 토큰을 훔쳐 갈 수 없음
                .secure(secure) // HTTPS에서만 전송
                .sameSite("Strict") // 다른 사이트에서 시작된 요청에는 실리지 않음 → CSRF 방어
                .path(PATH)
                .maxAge(maxAge)
                .build();
    }
}
