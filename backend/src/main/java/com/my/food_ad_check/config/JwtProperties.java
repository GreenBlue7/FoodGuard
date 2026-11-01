package com.my.food_ad_check.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param secret              최소 32바이트 무작위 값의 Base64 (HS256은 256비트 키가 필요)
 * @param accessTokenTtl      발급한 Access Token의 유효 기간 (짧게: 탈취돼도 피해 시간이 짧음)
 * @param refreshTokenTtl     Refresh Token 유효 기간. 갱신할 때마다 다시 계산됨 (이 기간 동안 안 쓰면 다시 로그인)
 * @param refreshCookieSecure true면 HTTPS에서만 쿠키를 보냄. 운영에서는 반드시 true
 */
@ConfigurationProperties(prefix = "jwt") // application.yml의 jwt: 아래 값들을 이 record의 필드에 자동으로 채움
public record JwtProperties(
        String secret, Duration accessTokenTtl, Duration refreshTokenTtl, boolean refreshCookieSecure) {
}