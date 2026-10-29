package com.my.food_ad_check.controller;

import com.my.food_ad_check.dto.LoginRequest;
import com.my.food_ad_check.dto.LoginResponse;
import com.my.food_ad_check.dto.SignupRequest;
import com.my.food_ad_check.dto.UserResponse;
import com.my.food_ad_check.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse signUp(
            @Valid @RequestBody SignupRequest request) {
        return authService.signUp(request);
    }

    // JWT방식은 로그아웃 API가 없음
    // Access Token은 서버가 무효화할 수 없으므로 클라이언트가 버린다
    @PostMapping("/login")
    public LoginResponse login(
            @Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }
}
