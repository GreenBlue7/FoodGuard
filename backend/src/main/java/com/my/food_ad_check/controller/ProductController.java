package com.my.food_ad_check.controller;

import com.my.food_ad_check.dto.ProductSummary;
import com.my.food_ad_check.entity.Product;
import com.my.food_ad_check.repository.ProductRepository;
import com.my.food_ad_check.service.ProductApiService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductRepository productRepository;
    private final ProductApiService productApiService;

    public ProductController(ProductRepository productRepository, ProductApiService productApiService) {
        this.productRepository = productRepository;
        this.productApiService = productApiService;
    }

    @GetMapping
    public List<ProductSummary> search(@RequestParam String keyword) {
        String normalizedKeyword = keyword == null ? "" : keyword.trim();
        if (normalizedKeyword.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "제품명 또는 신고번호를 입력해 주세요.");
        }

        return productRepository.findById(normalizedKeyword)
                .map(List::of)
                .orElseGet(() -> productRepository
                        .findTop20ByPrdlstNmContainingIgnoreCaseOrderByPrdlstNmAsc(normalizedKeyword))
                .stream()
                .map(this::toSummary)
                .toList();
    }

    private ProductSummary toSummary(Product product) {
        List<String> approvedFunctions = product.getApprovedFunctions();
        if (approvedFunctions.isEmpty() && "건강기능식품".equals(product.getProductType())) {
            approvedFunctions = productApiService.parsePrimaryFnclty(product.getPrimaryFnclty());
        }
        return new ProductSummary(product.getPrdlstReportNo(), product.getPrdlstNm(),
                product.getBsshNm(), product.getProductType(), product.getPrimaryFnclty(),
                approvedFunctions);
    }
}
