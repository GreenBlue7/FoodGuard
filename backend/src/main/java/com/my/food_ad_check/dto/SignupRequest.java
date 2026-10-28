package com.my.food_ad_check.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// 비밀번호 길이는 NIST SP 800-63B-4의 단일 요소(비밀번호만으로 로그인) 기준으로
// 최소 15자, 최소 64자까지 허용, 문자 조합 규칙 없음
public record SignupRequest(
        @NotBlank(message = "이메일을 입력해 주세요.")
        @Email(message = "이메일 형식이 올바르지 않습니다.")
        @Size(max = 255, message = "이메일은 255자 이하로 입력해 주세요.")
        String email,

        @NotNull(message = "비밀번호를 입력해 주세요.")
        @Size(min = 15, max = 64, message = "비밀번호는 15자 이상 64자 이하로 입력해 주세요.")
        String password,

        // 선택 입력
        @Size(max = 100, message = "업체명은 100자 이하로 입력해 주세요.")
        String companyName) {
}
