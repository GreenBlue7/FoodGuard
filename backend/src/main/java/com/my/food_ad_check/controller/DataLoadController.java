package com.my.food_ad_check.controller;

import com.my.food_ad_check.service.ProductApiService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import java.util.List;


@RestController
@RequestMapping("/api/data")
@ConditionalOnProperty(name = "foodsafety.import-enabled", havingValue = "true")
public class DataLoadController {

    private final ProductApiService productApiService;

    public DataLoadController(ProductApiService productApiService) {
        this.productApiService = productApiService;
    }

    // 전체 건기식 데이터 적재 실행
    @PostMapping("/load-all")
    public ResponseEntity<String> loadAllData() {
        String result = productApiService.loadAllProductData();
        if (result.startsWith("데이터 적재 진행 중:")) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(result);
        }
        if (result.startsWith("데이터 적재 실패:")) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(result);
        }
        return ResponseEntity.ok(result);
    }

    // 파싱 로직 테스트용 엔드포인트
    @GetMapping("/test-parse")
    public List<String> testParse(@RequestParam String text) {
        return productApiService.parsePrimaryFnclty(text);
    }
}
