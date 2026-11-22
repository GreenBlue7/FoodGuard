package com.my.food_ad_check.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Called after duplicate lookup while enqueue holds this user's DB row lock. */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class InspectionAdmissionPolicy {
    private final JdbcTemplate db;
    private final int maxActive;
    private final int maxNewPerWindow;
    private final int windowSeconds;

    public InspectionAdmissionPolicy(JdbcTemplate db,
            @Value("${inspection.limits.max-active:3}") int maxActive,
            @Value("${inspection.limits.max-new-per-window:5}") int maxNewPerWindow,
            @Value("${inspection.limits.window-seconds:60}") int windowSeconds) {
        if (maxActive < 1 || maxNewPerWindow < 1 || windowSeconds < 1) {
            throw new IllegalArgumentException("Inspection limits must be positive");
        }
        this.db = db;
        this.maxActive = maxActive;
        this.maxNewPerWindow = maxNewPerWindow;
        this.windowSeconds = windowSeconds;
    }

    public void check(Long userId) {
        Long active = db.queryForObject("""
                SELECT count(*) FROM inspection_records
                WHERE user_id=? AND status IN ('PENDING','RUNNING')
                """, Long.class, userId);
        if (active != null && active >= maxActive) {
            throw new InspectionAdmissionException("INSPECTION_ACTIVE_LIMIT",
                    "대기·진행 중인 검사는 최대 " + maxActive + "건입니다. 기존 검사 결과를 확인한 뒤 다시 접수해 주세요.", 5);
        }
        var window = db.queryForMap("""
                SELECT count(*) AS accepted,
                    greatest(1,ceil(extract(epoch FROM
                        (min(accepted_at)+?*interval '1 second'-statement_timestamp())))) AS retry_after
                FROM inspection_submission_log
                WHERE user_id=? AND accepted_at>statement_timestamp()-?*interval '1 second'
                """, windowSeconds, userId, windowSeconds);
        if (((Number) window.get("accepted")).longValue() >= maxNewPerWindow) {
            int wait = Math.max(1, Math.min(windowSeconds, ((Number) window.get("retry_after")).intValue()));
            throw new InspectionAdmissionException("INSPECTION_RATE_LIMIT",
                    "최근 " + windowSeconds + "초 동안 새 검사는 최대 " + maxNewPerWindow
                            + "건까지 접수할 수 있습니다. 잠시 후 다시 시도해 주세요.", wait);
        }
    }

    public void recordAccepted(Long userId) {
        // Commit/rollback with the record and queue row. No in-memory counters to reset on restart.
        db.update("""
                DELETE FROM inspection_submission_log WHERE user_id=?
                    AND accepted_at<=statement_timestamp()-?*interval '1 second'
                """, userId, windowSeconds);
        db.update("INSERT INTO inspection_submission_log(user_id) VALUES(?)", userId);
    }
}
