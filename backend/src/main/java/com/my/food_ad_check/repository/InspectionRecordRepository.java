package com.my.food_ad_check.repository;

import com.my.food_ad_check.entity.InspectionRecord;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InspectionRecordRepository extends JpaRepository<InspectionRecord, Long> {

    // 소유자 조건을 쿼리에 넣어서, 남의 기록은 처음부터 조회되지 않게
    Optional<InspectionRecord> findByIdAndUserId(Long id, Long userId);
}
