package com.my.food_ad_check.controller;

import com.my.food_ad_check.config.JwtProperties;
import com.my.food_ad_check.dto.LoginRequest;
import com.my.food_ad_check.dto.LoginResponse;
import com.my.food_ad_check.dto.SignupRequest;
import com.my.food_ad_check.dto.UserResponse;
import com.my.food_ad_check.service.AuthService;
import com.my.food_ad_check.service.AuthTokens;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final RefreshTokenCookie refreshTokenCookie;

    public AuthController(AuthService authService, JwtProperties jwtProperties) {
        this.authService = authService;
        this.refreshTokenCookie = new RefreshTokenCookie(jwtProperties);
    }

    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse signUp(
            @Valid @RequestBody SignupRequest request) {
        return authService.signUp(request);
    }

    // Access Token은 응답 본문으로(프론트가 메모리에 보관), Refresh Token은 HttpOnly 쿠키로(JS가 못 읽음) 나눠 보냄
    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(
            @Valid @RequestBody LoginRequest request) {
        return withRefreshCookie(authService.login(request));
    }

    // 쿠키는 브라우저가 자동으로 실어 보내므로 프론트는 본문 없이 호출하면 됨
    @PostMapping("/refresh")
    public ResponseEntity<LoginResponse> refresh(
            @CookieValue(name = RefreshTokenCookie.NAME, required = false) String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다.");
        }
        return withRefreshCookie(authService.refresh(refreshToken));
    }

    // 서버의 로그인 세션을 지우고 쿠키도 지움. 쿠키가 없거나 이미 만료됐어도 성공으로 응답
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @CookieValue(name = RefreshTokenCookie.NAME, required = false) String refreshToken) {
        if (refreshToken != null && !refreshToken.isBlank()) {
            authService.logout(refreshToken);
        }
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, refreshTokenCookie.clear().toString())
                .build();
    }

    private ResponseEntity<LoginResponse> withRefreshCookie(AuthTokens tokens) {
        ResponseEntity.BodyBuilder response = ResponseEntity.ok();
        if (tokens.refresh() != null) {
            response.header(HttpHeaders.SET_COOKIE, refreshTokenCookie.issue(tokens.refresh()).toString());
        }
        return response.body(tokens.access());
    }
}