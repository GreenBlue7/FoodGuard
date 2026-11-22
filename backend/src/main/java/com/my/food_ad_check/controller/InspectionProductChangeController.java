package com.my.food_ad_check.controller;

import com.my.food_ad_check.repository.InspectionRecordRepository;
import com.my.food_ad_check.repository.ProductRepository;
import com.my.food_ad_check.repository.ProductFunctionChangeRepository;
import java.time.LocalDateTime;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/inspections")
public class InspectionProductChangeController {
    private final InspectionRecordRepository inspections;
    private final ProductRepository products;
    private final ProductFunctionChangeRepository changes;
    public InspectionProductChangeController(InspectionRecordRepository inspections, ProductRepository products,
                                              ProductFunctionChangeRepository changes) {
        this.inspections = inspections;
        this.products = products;
        this.changes = changes;
    }
    public record Notice(boolean productAvailable, boolean sourceChanged, LocalDateTime lastDetectedAt,
                         String inspectedSource, String currentSource, String message) { }

    @GetMapping("/{jobId}/product-changes")
    public Notice get(@AuthenticationPrincipal Jwt jwt, @PathVariable Long jobId) {
        var record = inspections.findByIdAndUserId(jobId, Long.valueOf(jwt.getSubject()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "검수 기록을 찾을 수 없습니다."));
        var product = record.getPrdlstReportNo() == null ? null
                : products.findById(record.getPrdlstReportNo()).orElse(null);
        if (product == null) return new Notice(false, false, null, record.getPrimaryFnclty(), null,
                "비교할 제품 등록 정보를 확인하지 못했습니다.");
        var latest = changes.findFirstByReportNoAndDetectedAtAfterOrderByDetectedAtDescIdDesc(
                record.getPrdlstReportNo(), record.getCreatedAt());
        boolean changed = latest.isPresent() || !Objects.equals(record.getPrimaryFnclty(), product.getPrimaryFnclty());
        return new Notice(true, changed, latest.map(c -> c.getDetectedAt()).orElse(null),
                record.getPrimaryFnclty(), product.getPrimaryFnclty(), changed
                ? "검수 이후 제품의 기능성 원문이 변경됐습니다. 광고를 확인하고 재검수해주세요."
                : "저장된 기능성 원문과 현재 정보 사이에 감지된 변경이 없습니다.");
    }
}
