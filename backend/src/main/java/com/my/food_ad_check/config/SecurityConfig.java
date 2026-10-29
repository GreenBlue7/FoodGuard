package com.my.food_ad_check.config;

import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // 세션 쿠키를 아예 발급하지 않으므로 CSRF가 악용할 쿠키가 없다
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // 기본은 로그인 필요. 로그인 없이 쓸 수 있는 주소만 명시적으로 연다
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/api/auth/signup", "/api/auth/login").permitAll()
                        // 에러 응답(409, 400 등)을 만드는 내부 경로. 막으면 모든 에러가 401로 바뀐다
                        .requestMatchers("/error").permitAll()
                        // 식약처 데이터 적재 등 관리용 API. USER가 호출하면 403
                        .requestMatchers("/api/data/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                // "Authorization: Bearer <JWT>"를 JwtDecoder 빈으로 검증한다. 토큰이 없거나 잘못되면 401
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt
                        .jwtAuthenticationConverter(roleClaimAuthenticationConverter())));
        return http.build();
    }

    // 토큰의 "role" 클레임(USER/ADMIN)을 ROLE_USER/ROLE_ADMIN 권한으로 바꿔서 hasRole()이 읽을 수 있게 한다.
    // 기본 변환기는 OAuth2의 "scope" 클레임만 읽기 때문에 직접 지정한다
    private static JwtAuthenticationConverter roleClaimAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("role");
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        // 해시를 "{argon2}..." 형식으로 저장한다. 나중에 알고리즘을 추가해도
        // 기존 해시는 접두어로 구분되어 계속 검증할 수 있다
        return new DelegatingPasswordEncoder("argon2",
                Map.of("argon2", Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8()));
    }
}
