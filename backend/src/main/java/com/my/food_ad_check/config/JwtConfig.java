package com.my.food_ad_check.config;

import java.util.Base64;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

// HS256: 이 서버가 토큰 발급과 검증을 모두 하므로 secret key하나로 충분
@Configuration // @Configuration: "이 클래스 안의 @Bean 메서드들이 Spring이 관리할 객체를 만든다"
@EnableConfigurationProperties(JwtProperties.class) // 위의 JwtProperties를 빈으로 등록하라는 지시
public class JwtConfig {

    private static final int MIN_KEY_BYTES = 32; // RFC 7518 3.2: 키 길이는 해시 출력(256비트) 이상이어야 함

    private final SecretKey secretKey;

    public JwtConfig(JwtProperties properties) {
        this.secretKey = toSecretKey(properties.secret());
    }

    @Bean
    public JwtEncoder jwtEncoder() {
        return NimbusJwtEncoder.withSecretKey(secretKey).algorithm(MacAlgorithm.HS256).build();
    }

    @Bean
    public JwtDecoder jwtDecoder() {
        // 알고리즘을 고정해서 헤더에 다른 알고리즘을 적은 토큰은 거부
        return NimbusJwtDecoder.withSecretKey(secretKey).macAlgorithm(MacAlgorithm.HS256).build();
    }

    // 첫 로그인 때가 아니라 서버 시작 시점에 실패시키고 해결 방법을 메시지에
    private static SecretKey toSecretKey(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "JWT_SECRET is not set. Generate one with: openssl rand -base64 32");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(secret.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("JWT_SECRET must be Base64. Generate one with: openssl rand -base64 32", e);
        }
        if (bytes.length < MIN_KEY_BYTES) {
            throw new IllegalStateException("JWT_SECRET must decode to at least " + MIN_KEY_BYTES
                    + " bytes. Generate one with: openssl rand -base64 32");
        }
        return new SecretKeySpec(bytes, "HmacSHA256");
    }
}
