package com.my.food_ad_check.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param secret         최소 32바이트 무작위 값의 Base64 (HS256은 256비트 키가 필요)
 * @param accessTokenTtl 발급한 Access Token의 유효 기간
 */
@ConfigurationProperties(prefix = "jwt") // application.yml의 jwt: 아래 값들을 이 record의 필드에 자동으로 채움
public record JwtProperties(String secret, Duration accessTokenTtl) {
}
