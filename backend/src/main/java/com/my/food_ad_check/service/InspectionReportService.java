package com.my.food_ad_check.service;

import com.my.food_ad_check.repository.InspectionRecordRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class InspectionReportService {
    private final InspectionRecordRepository records;
    public InspectionReportService(InspectionRecordRepository records) { this.records=records; }

    public String report(Long userId, Long jobId) {
        var record=records.findByIdAndUserId(jobId,userId)
                .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"검수 기록을 찾을 수 없습니다."));
        if (!"DONE".equals(record.getStatus()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"완료된 검사만 리포트로 제공할 수 있습니다.");
        return InspectionReportRenderer.render(record);
    }
}
