package com.my.food_ad_check.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class FastApiResponse {
    private String status;
    private Object result; // 추후 2층 AI 판독 결과(위반 여부, 사유 등)를 담을 객체[cite: 5]
}