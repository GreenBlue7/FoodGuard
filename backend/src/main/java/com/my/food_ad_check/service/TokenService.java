package com.my.food_ad_check.service;

import com.my.food_ad_check.config.JwtProperties;
import com.my.food_ad_check.dto.LoginResponse;
import com.my.food_ad_check.entity.User;
import java.time.Instant;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

@Service
public class TokenService {

    private final JwtEncoder jwtEncoder;
    private final JwtProperties properties;

    public TokenService(JwtEncoder jwtEncoder, JwtProperties properties) {
        this.jwtEncoder = jwtEncoder;
        this.properties = properties;
    }

    // payload는 토큰을 가진 누구나 읽을 수 있으므로 회원 id와 권한만 넣음
    // role은 발급 시점 값이라 DB에서 바꿔도 이미 발급된 토큰은 만료 전까지 이전 권한을 유지
    public LoginResponse issueAccessToken(User user) {
        Instant now = Instant.now(); // 절대시각
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(user.getId().toString())
                .issuedAt(now)
                .expiresAt(now.plus(properties.accessTokenTtl()))
                .claim("role", user.getRole().name())
                .build();
        // 인코더의 기본 헤더가 RS256이므로 HS256을 명시
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).type("JWT").build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new LoginResponse(token, "Bearer", properties.accessTokenTtl().toSeconds());
    }
}
