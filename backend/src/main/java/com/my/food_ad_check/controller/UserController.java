package com.my.food_ad_check.controller;

import com.my.food_ad_check.dto.UserResponse;
import com.my.food_ad_check.service.UserService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    // JWT 필터가 이미 토큰을 검증함
    // "sub"에는 로그인 때 넣은 회원 id
    @GetMapping("/me")
    public UserResponse me(@AuthenticationPrincipal Jwt jwt) {
        return userService.getUser(Long.valueOf(jwt.getSubject()));
    }
}
