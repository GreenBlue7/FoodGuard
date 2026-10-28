package com.my.food_ad_check.dto;

import com.my.food_ad_check.entity.User;
import java.time.LocalDateTime;

// 비밀번호 해시는 절대 포함 X
public record UserResponse(Long id, String email, String companyName, LocalDateTime createdAt) {

    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getEmail(), user.getCompanyName(), user.getCreatedAt());
    }
}
