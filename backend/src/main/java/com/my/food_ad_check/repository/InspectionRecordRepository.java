package com.my.food_ad_check.repository;

import com.my.food_ad_check.entity.InspectionRecord;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InspectionRecordRepository extends JpaRepository<InspectionRecord, Long> {
}
