package com.my.food_ad_check.service;

import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class ProductSyncRunTracker {
    private final JdbcTemplate db;
    public ProductSyncRunTracker(JdbcTemplate db) { this.db = db; }

    public long start(String mode) {
        return db.queryForObject("INSERT INTO product_sync_runs(service_id,mode) VALUES('I0030',?) RETURNING id",
                Long.class, mode);
    }

    public void finish(long id, String status, String errorCode) {
        if (!java.util.Set.of("SUCCEEDED","FAILED").contains(status)) throw new IllegalArgumentException("Invalid sync status");
        int updated = db.update("""
                UPDATE product_sync_runs SET status=?,finished_at=clock_timestamp(),error_code=?
                WHERE id=? AND status='RUNNING'
                """, status, errorCode, id);
        if (updated != 1) throw new IllegalStateException("Sync run was not active");
    }

    public void failWithoutMasking(long id, String errorCode) {
        try { finish(id, "FAILED", errorCode); }
        catch (RuntimeException failure) {
            LoggerFactory.getLogger(getClass()).warn("수집 실행 상태 기록 실패: {}", failure.getClass().getSimpleName());
        }
    }
}
