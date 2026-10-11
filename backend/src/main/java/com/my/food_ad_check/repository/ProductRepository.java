package com.my.food_ad_check.repository;

import com.my.food_ad_check.entity.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProductRepository extends JpaRepository<Product, String> {
    List<Product> findTop20ByPrdlstNmContainingIgnoreCaseOrderByPrdlstNmAsc(String keyword);

    Optional<Product> findFirstByPrdlstReportNo(String prdlstReportNo);
}
