package com.my.food_ad_check.repository;

import com.my.food_ad_check.entity.ProductChangeNotification;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductChangeNotificationRepository extends JpaRepository<ProductChangeNotification, Long> {
    @EntityGraph(attributePaths = "change")
    Page<ProductChangeNotification> findByUserId(Long userId, Pageable pageable);
    @EntityGraph(attributePaths = "change")
    Page<ProductChangeNotification> findByUserIdAndReadAtIsNull(Long userId, Pageable pageable);
    Optional<ProductChangeNotification> findByIdAndUserId(Long id, Long userId);
    long countByUserIdAndReadAtIsNull(Long userId);
}
