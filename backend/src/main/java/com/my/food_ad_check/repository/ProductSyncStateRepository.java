package com.my.food_ad_check.repository;

import com.my.food_ad_check.entity.ProductSyncState;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductSyncStateRepository extends JpaRepository<ProductSyncState, String> { }
