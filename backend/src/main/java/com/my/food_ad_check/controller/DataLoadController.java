package com.my.food_ad_check.controller;

import com.my.food_ad_check.service.ProductApiService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import java.util.List;


@RestController
@RequestMapping("/api/data")
public class DataLoadController {

    private final ProductApiService productApiService;

    public DataLoadController(ProductApiService productApiService) {
        this.productApiService = productApiService;
    }

    // 전체 건기식 데이터 적재 실행
    @PostMapping("/load-all")
    public String loadAllData() {
        return productApiService.loadAllProductData();
    }

    // 파싱 로직 테스트용 엔드포인트
    @GetMapping("/test-parse")
    public List<String> testParse(@RequestParam String text) {
        return productApiService.parsePrimaryFnclty(text);
    }
}
