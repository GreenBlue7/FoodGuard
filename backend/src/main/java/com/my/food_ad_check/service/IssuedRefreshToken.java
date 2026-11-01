package com.my.food_ad_check.service;

import java.time.Duration;

/**
 * @param value 쿠키에 넣을 토큰 원문 ("세션ID.비밀값"). 서버는 이 값을 저장하지 않음
 * @param ttl   쿠키 유효 기간
 */
public record IssuedRefreshToken(String value, Duration ttl) {

    // 토큰 원문이 로그에 찍히지 않게 가림
    @Override
    public String toString() {
        return "IssuedRefreshToken[value=****, ttl=" + ttl + "]";
    }
}
