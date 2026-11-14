package com.my.food_ad_check.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Runs the same guarded importer as the explicit import endpoint. One scheduler instance only. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "foodsafety.scheduled-refresh-enabled", havingValue = "true")
public class ProductRefreshScheduler {
    private static final Logger log = LoggerFactory.getLogger(ProductRefreshScheduler.class);
    private final ProductRefreshService refreshService;
    public ProductRefreshScheduler(ProductRefreshService refreshService) { this.refreshService = refreshService; }

    @Scheduled(cron = "${foodsafety.refresh-cron:0 0 3 * * *}", zone = "Asia/Seoul")
    public void refresh() {
        log.info("식약처 정기 갱신: {}", refreshService.refresh());
    }
}
