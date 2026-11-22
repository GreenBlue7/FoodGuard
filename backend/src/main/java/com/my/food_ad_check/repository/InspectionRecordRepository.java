package com.my.food_ad_check.repository;

import com.my.food_ad_check.entity.InspectionRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface InspectionRecordRepository extends JpaRepository<InspectionRecord, Long> {

    Optional<InspectionRecord> findFirstByUserIdAndPrdlstReportNoOrderByCreatedAtDescIdDesc(Long userId, String reportNo);

    @org.springframework.data.jpa.repository.Query(
            "select distinct r.userId from InspectionRecord r where r.prdlstReportNo = :reportNo")
    java.util.List<Long> findInspectingUserIds(@org.springframework.data.repository.query.Param("reportNo") String reportNo);

    // 소유자 조건을 쿼리에 넣어서, 남의 기록은 처음부터 조회되지 않게
    Optional<InspectionRecord> findByIdAndUserId(Long id, Long userId);

    // 유저의 검수 기록 페이지 하나를 불러오는 데 사용
    // 정렬/페이지 크기는 Pageable로 받음
    // (user_id, created_at DESC) 인덱스 사용
    Page<Summary> findByUserId(Long userId, Pageable pageable);

    interface Summary {
        Long getId();

        String getStatus();

        String getPrdlstNm();

        String getAdText();

        LocalDateTime getCreatedAt();

        String getResultJson();

        Long getPreviousInspectionId();
    }
}
