package com.my.food_ad_check.service;

import com.my.food_ad_check.dto.InspectionHistoryResponse;
import com.my.food_ad_check.repository.InspectionRecordRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

@Service
public class InspectionHistoryService {

    private static final int MAX_PAGE_SIZE = 50;
    private final InspectionRecordRepository inspectionRecordRepository;

    public InspectionHistoryService(InspectionRecordRepository inspectionRecordRepository) {
        this.inspectionRecordRepository = inspectionRecordRepository;
    }

    public InspectionHistoryResponse getMyHistory(Long userId, int page, int pageSize) {
        // 잘못된 page, pageSize 값들을 위한 safeguard
        int safePage = Math.max(page, 0);
        int safePageSize = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);

        PageRequest pageRequest = PageRequest.of(safePage, safePageSize, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        // 더 줄여 쓰는것도 가능하지만 데이터 변환 흐름과 의도를 살리기 위해 일부러 중간변수 사용
        Page<InspectionRecordRepository.Summary> summaries = inspectionRecordRepository.findByUserId(userId, pageRequest);

        return InspectionHistoryResponse.from(summaries);
    }
}
