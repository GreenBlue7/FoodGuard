package com.my.food_ad_check.dto;

import com.my.food_ad_check.repository.InspectionRecordRepository.Summary;
import com.my.food_ad_check.service.InspectionHistoryMetadata;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Page;

// GET /api/inspections 응답 (JWT사용하므로 {userId} 없음?)
// totalElements, totalPages --> 페이지 번호 매기는 데 사용
public record InspectionHistoryResponse(
        List<Item> items,
        int page,           // 0부터 시작
        int size,
        long totalElements,
        int totalPages) {

    private static final int PREVIEW_LENGTH = 40;

    public record Item(
            String jobId,
            String status,
            String productName,    // 제품 없이 검사했으면 null
            String adTextPreview,  // 광고 문구 앞부분
            LocalDateTime createdAt,
            boolean countsAvailable,
            Integer violationCount,
            Integer reviewCount,
            String dataAsOf,
            String previousJobId) {
    }

    // DB 조회 결과(Summary 한 페이지)를 API 응답 형식(DTO)으로 변환
    public static InspectionHistoryResponse from(Page<Summary> page) {
        List<Item> items = page.getContent().stream()
                .map(s -> {
                    var metadata = InspectionHistoryMetadata.read(s.getStatus(), s.getResultJson());
                    return new Item(s.getId().toString(), s.getStatus(), s.getPrdlstNm(),
                            preview(s.getAdText()), s.getCreatedAt(), metadata.countsAvailable(),
                            metadata.violationCount(), metadata.reviewCount(),
                            metadata.dataAsOf(), s.getPreviousInspectionId() == null ? null : s.getPreviousInspectionId().toString());
                })
                .toList();

        return new InspectionHistoryResponse(items, page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }

    // 백엔드쪽에서 미리 프리뷰 문구를 잘라냄
    // 글자 수는 코드 포인트 기준 --> substring은 UTF-16 단위라 이모지를 반으로 자를 수 있음
    private static String preview(String adText) {
        if (adText.codePointCount(0, adText.length()) <= PREVIEW_LENGTH) {
            return adText;
        }
        int end = adText.offsetByCodePoints(0, PREVIEW_LENGTH);
        return adText.substring(0, end) + "…";
    }
}
