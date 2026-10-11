package com.my.food_ad_check.dto;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import java.util.List;

@Getter
@Setter
@Builder
public class FastApiRequest {
    private String prdlstNm; // 제품명
    private String productType; // 제품 종류 (건강기능식품 등)
    private List<String> primaryFncltyKeywords; // 파싱된 기능성 키워드 배열[cite: 5]
    private String adText; // 판매자가 입력한 광고 문구[cite: 5]
}