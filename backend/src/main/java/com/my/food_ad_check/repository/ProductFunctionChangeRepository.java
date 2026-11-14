package com.my.food_ad_check.repository;

import com.my.food_ad_check.entity.ProductFunctionChange;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductFunctionChangeRepository extends JpaRepository<ProductFunctionChange, Long> {
    Optional<ProductFunctionChange> findFirstByReportNoAndDetectedAtAfterOrderByDetectedAtDescIdDesc(
            String reportNo, LocalDateTime inspectedAt);
}
