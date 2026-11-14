package com.my.food_ad_check.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.my.food_ad_check.dto.FastApiRequest;
import com.my.food_ad_check.entity.InspectionRecord;
import com.my.food_ad_check.repository.InspectionRecordRepository;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Durable inputs, atomic claims and token-guarded completion. No external calls in transactions. */
@Service
public class InspectionJobQueue {
    private final JdbcTemplate db;
    private final InspectionRecordRepository records;
    private final long leaseMs;
    private final ObjectMapper json = new ObjectMapper();

    public InspectionJobQueue(JdbcTemplate db, InspectionRecordRepository records,
            @Value("${inspection.jobs.lease-ms:600000}") long leaseMs,
            @Value("${judge.timeout-ms:180000}") long judgeTimeoutMs) {
        this.db = db;
        this.records = records;
        // Connect and read limits are separate. Allow both plus a completion margin.
        this.leaseMs = Math.max(leaseMs, 2 * judgeTimeoutMs + 60000);
    }

    @Transactional
    public InspectionRecord enqueue(InspectionRecord record, FastApiRequest request,
                                     InspectionRecommendations.Draft recommendation) {
        // Serialize submissions for this account across app instances. No paid call under this lock.
        if (db.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE", Long.class, record.getUserId()).isEmpty()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.UNAUTHORIZED, "사용자 정보를 확인할 수 없습니다.");
        }
        identifyOlderActiveJobs(record.getUserId());
        String fingerprint = InspectionRequestFingerprint.of(record, request, recommendation);
        var existing = db.queryForList("""
                SELECT id FROM inspection_records WHERE user_id=? AND request_fingerprint=?
                    AND status IN ('PENDING','RUNNING')
                """, Long.class, record.getUserId(), fingerprint);
        if (!existing.isEmpty()) return records.findByIdAndUserId(existing.get(0), record.getUserId()).orElseThrow();
        record.identifyRequest(fingerprint);
        InspectionRecord saved = records.saveAndFlush(record);
        db.update("""
                INSERT INTO inspection_job_queue(job_id,judge_request_json,recommendation_json)
                VALUES(?,?,?)
                """, saved.getId(), encode(request), recommendation == null ? null : encode(recommendation));
        return saved;
    }

    private void identifyOlderActiveJobs(Long userId) {
        // Adopt pre-migration queued work without cancelling or repeating any of its calls.
        var older = db.queryForList("""
                SELECT r.id,q.judge_request_json,q.recommendation_json
                FROM inspection_records r JOIN inspection_job_queue q ON q.job_id=r.id
                WHERE r.user_id=? AND r.request_fingerprint IS NULL AND r.status IN ('PENDING','RUNNING')
                ORDER BY r.id
                """, userId);
        for (var row : older) {
            try {
                long id = ((Number) row.get("id")).longValue();
                var old = records.findByIdAndUserId(id, userId).orElseThrow();
                var request = json.readValue((String) row.get("judge_request_json"), FastApiRequest.class);
                String draft = (String) row.get("recommendation_json");
                var recommendation = draft == null ? null : json.readValue(draft, InspectionRecommendations.Draft.class);
                String fingerprint = InspectionRequestFingerprint.of(old, request, recommendation);
                db.update("""
                        UPDATE inspection_records SET request_fingerprint=? WHERE id=?
                        AND NOT EXISTS (SELECT 1 FROM inspection_records WHERE user_id=?
                            AND request_fingerprint=? AND status IN ('PENDING','RUNNING'))
                        """, fingerprint, id, userId, fingerprint);
            } catch (JsonProcessingException invalid) {
                throw new IllegalStateException("Cannot identify persisted inspection input", invalid);
            }
        }
    }

    public record Claim(long jobId, UUID token, String requestJson, String recommendationJson) { }

    @Transactional
    public Optional<Claim> claim() {
        var candidates = db.query("""
                SELECT q.job_id,q.judge_request_json,q.recommendation_json
                FROM inspection_job_queue q JOIN inspection_records r ON r.id=q.job_id
                WHERE q.claim_token IS NULL AND r.status='PENDING'
                ORDER BY q.queued_at,q.job_id LIMIT 1 FOR UPDATE OF q,r SKIP LOCKED
                """, (rs, row) -> new Claim(rs.getLong("job_id"), UUID.randomUUID(),
                rs.getString("judge_request_json"), rs.getString("recommendation_json")));
        if (candidates.isEmpty()) return Optional.empty();
        Claim claim = candidates.get(0);
        db.update("""
                UPDATE inspection_job_queue SET claim_token=?,lease_until=clock_timestamp()+?*interval '1 millisecond'
                WHERE job_id=?
                """, claim.token(), leaseMs, claim.jobId());
        db.update("UPDATE inspection_records SET status='RUNNING' WHERE id=?", claim.jobId());
        return Optional.of(claim);
    }

    @Transactional
    public boolean finish(Claim claim, String status, Map<String, Object> result) {
        var locked = db.queryForList("""
                SELECT job_id FROM inspection_job_queue
                WHERE job_id=? AND claim_token=? AND lease_until>clock_timestamp() FOR UPDATE
                """, claim.jobId(), claim.token());
        if (locked.isEmpty()) return false; // expired/deleted work cannot overwrite a later state
        var record = records.findById(claim.jobId()).orElseThrow();
        record.finish(status, encode(result));
        records.saveAndFlush(record);
        db.update("DELETE FROM inspection_job_queue WHERE job_id=? AND claim_token=?", claim.jobId(), claim.token());
        return true;
    }

    @Transactional
    public int recoverExpired() {
        // Never replay an uncertain paid call. A user can explicitly request a new inspection.
        var expired = db.queryForList("""
                SELECT job_id FROM inspection_job_queue WHERE lease_until<clock_timestamp()
                ORDER BY lease_until LIMIT 100 FOR UPDATE SKIP LOCKED
                """, Long.class);
        int changed = 0;
        for (long id : expired) {
            changed += db.update("""
                UPDATE inspection_records SET status='FAILED',result_json=(result_json::jsonb ||
                    jsonb_build_object('failureCode','WORKER_INTERRUPTED','failureMessage',
                    '검사 처리가 중단됐습니다. 결과를 확인하지 못했으며 자동으로 재호출하지 않았습니다. 다시 검사해 주세요.',
                    'completedAt',clock_timestamp()) ||
                    CASE WHEN jsonb_exists(result_json::jsonb, 'recommendationValidation') THEN
                        jsonb_build_object('recommendationValidation',
                            (result_json::jsonb->'recommendationValidation') || jsonb_build_object(
                                'status','REVIEW_REQUIRED','message','검사 중단으로 수정안 적합성을 확인하지 못했습니다.'))
                    ELSE '{}'::jsonb END)::text
                WHERE id=? AND status='RUNNING'
                """, id);
            db.update("DELETE FROM inspection_job_queue WHERE job_id=?", id);
        }
        return changed;
    }

    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Cannot encode inspection job", e); }
    }
}
