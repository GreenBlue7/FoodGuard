package com.my.food_ad_check.dto;

import jakarta.validation.constraints.NotBlank;

// 비밀번호 길이 규칙은 회원가입에서만 검사함
// 로그인은 입력값을 DB에 저장된 해시와 비교만 하므로, 길이와 상관없이 틀린 비밀번호는 똑같이 401
// 예전 길이 규칙으로 가입한 회원도 계속 로그인 가능
public record LoginRequest(
        @NotBlank(message = "이메일을 입력해 주세요.")
        String email,

        @NotBlank(message = "비밀번호를 입력해 주세요.")
        String password) {
    // 로그·에러 메시지에 객체가 통째로 찍혀도 비밀번호가 남지 않게
    @Override
    public String toString() {
        return "LoginRequest[email=" + email + ", password=****]";
    }
}
