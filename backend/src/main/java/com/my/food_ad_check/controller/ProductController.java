package com.my.food_ad_check.controller;

import com.my.food_ad_check.dto.ProductSummary;
import com.my.food_ad_check.dto.SearchProduct;
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

    @GetMapping(params = {"keyword", "!query"})
    public List<ProductSummary> search(@RequestParam String keyword) {
        return findProducts(keyword).stream().map(this::toSummary).toList();
    }

    @GetMapping(params = "query")
    public List<SearchProduct> searchForFrontend(@RequestParam String query) {
        return findProducts(query).stream().map(this::toSearchProduct).toList();
    }

    private List<Product> findProducts(String query) {
        String normalizedQuery = query == null ? "" : query.trim();
        if (normalizedQuery.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "제품명 또는 신고번호를 입력해 주세요.");
        }

        return productRepository.findById(normalizedQuery)
                .map(List::of)
                .orElseGet(() -> productRepository
                        .findTop20ByPrdlstNmContainingIgnoreCaseOrderByPrdlstNmAsc(normalizedQuery));
    }

    private SearchProduct toSearchProduct(Product product) {
        ProductSummary summary = toSummary(product);
        return new SearchProduct(summary.prdlstReportNo(), summary.prdlstNm(),
                summary.bsshNm() == null ? "" : summary.bsshNm(),
                summary.prdlstReportNo(),
                "건강기능식품".equals(summary.productType()) ? "HEALTH_FUNCTIONAL" : "UNDETERMINED",
                summary.approvedFunctions(), null);
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
