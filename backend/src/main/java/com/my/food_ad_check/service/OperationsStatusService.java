package com.my.food_ad_check.service;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Map;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Read-only operational facts. Never reads ad/result bodies, users, addresses or credentials. */
@Service
public class OperationsStatusService {
    private final JdbcTemplate db;
    private final boolean workerEnabled;
    private final boolean refreshEnabled;
    private final boolean emailEnabled;

    public OperationsStatusService(JdbcTemplate db,
            @Value("${inspection.jobs.worker-enabled:true}") boolean workerEnabled,
            @Value("${foodsafety.scheduled-refresh-enabled:false}") boolean refreshEnabled,
            @Value("${notification-email.delivery-enabled:false}") boolean emailEnabled) {
        this.db = db;
        this.workerEnabled = workerEnabled;
        this.refreshEnabled = refreshEnabled;
        this.emailEnabled = emailEnabled;
    }

    public record Database(String status, String timeZone) { }
    public record Inspections(boolean workerEnabled, long pending, long running, long done,
                              long failed, long timedOut, long expiredRunning, Instant oldestPendingAt) { }
    public record SyncRun(String mode, String status, Instant startedAt, Instant finishedAt, String errorCode) { }
    public record ProductSync(boolean scheduledEnabled, LocalDate coveredThrough, LocalDate lastFullSyncOn,
                              Instant lastRecordedSuccessAt, SyncRun latestRun) { }
    // Email timestamps are legacy timestamp-without-time-zone; interpret using database.timeZone.
    public record Email(boolean deliveryEnabled, long pending, long retryPending, long processing,
                        long sent, long skipped, long failed, long expiredProcessing, LocalDateTime lastSentAt) { }
    public record ExternalChecks(String judge, String mfds, String smtp) { }
    public record Snapshot(Instant checkedAt, String status, Database database, Inspections inspections,
                           ProductSync productSync, Email email, ExternalChecks externalChecks, String errorCode) { }

    public Snapshot snapshot() {
        Instant checked = Instant.now();
        var external = new ExternalChecks("NOT_CHECKED", "NOT_CHECKED", "NOT_CHECKED");
        try { db.queryForObject("SELECT 1", Integer.class); }
        catch (RuntimeException failure) {
            warn(failure);
            return new Snapshot(checked, "UNAVAILABLE", new Database("DOWN", null),
                    null, null, null, external, "DATABASE_UNAVAILABLE");
        }
        String zone = null;
        try {
            zone = db.queryForObject("SELECT current_setting('TimeZone')", String.class);
            Inspections inspections = inspections();
            ProductSync sync = productSync();
            Email email = email();
            boolean attention = inspections.expiredRunning() > 0 || email.expiredProcessing() > 0 || email.failed() > 0
                    || (!workerEnabled && inspections.pending() > 0)
                    || (sync.latestRun() != null && "FAILED".equals(sync.latestRun().status()));
            return new Snapshot(checked, attention ? "ATTENTION" : "OK", new Database("UP", zone),
                    inspections, sync, email, external, null);
        } catch (RuntimeException failure) {
            warn(failure);
            // A successful SELECT 1 does not prove that all required tables can be queried.
            return new Snapshot(checked, "UNAVAILABLE", new Database("UP", zone),
                    null, null, null, external, "STATUS_QUERY_FAILED");
        }
    }

    private Inspections inspections() {
        var counts = db.queryForMap("""
                SELECT count(*) FILTER (WHERE status='PENDING') AS pending,
                    count(*) FILTER (WHERE status='RUNNING') AS running,
                    count(*) FILTER (WHERE status='DONE') AS done,
                    count(*) FILTER (WHERE status='FAILED') AS failed,
                    count(*) FILTER (WHERE status='TIMEOUT') AS timed_out FROM inspection_records
                """);
        var queue = db.queryForMap("""
                SELECT count(*) FILTER (WHERE r.status='RUNNING' AND q.lease_until<statement_timestamp()) AS expired,
                    min(q.queued_at) FILTER (WHERE r.status='PENDING') AS oldest_pending
                FROM inspection_job_queue q JOIN inspection_records r ON r.id=q.job_id
                """);
        return new Inspections(workerEnabled, number(counts,"pending"), number(counts,"running"),
                number(counts,"done"), number(counts,"failed"), number(counts,"timed_out"),
                number(queue,"expired"), instant(queue.get("oldest_pending")));
    }

    private ProductSync productSync() {
        var state = db.queryForList("SELECT covered_through,last_full_sync_on FROM product_sync_state WHERE service_id='I0030'");
        var latest = db.queryForList("""
                SELECT mode,status,started_at,finished_at,error_code FROM product_sync_runs
                WHERE service_id='I0030' ORDER BY id DESC LIMIT 1
                """);
        var success = db.queryForMap("""
                SELECT max(finished_at) AS finished FROM product_sync_runs
                WHERE service_id='I0030' AND status='SUCCEEDED'
                """);
        SyncRun run = latest.isEmpty() ? null : new SyncRun((String)latest.get(0).get("mode"),
                (String)latest.get(0).get("status"), instant(latest.get(0).get("started_at")),
                instant(latest.get(0).get("finished_at")), (String)latest.get(0).get("error_code"));
        return new ProductSync(refreshEnabled, state.isEmpty() ? null : date(state.get(0).get("covered_through")),
                state.isEmpty() ? null : date(state.get(0).get("last_full_sync_on")),
                instant(success.get("finished")), run);
    }

    private Email email() {
        var counts = db.queryForMap("""
                SELECT count(*) FILTER (WHERE state='PENDING') AS pending,
                    count(*) FILTER (WHERE state='PENDING' AND attempts>0) AS retry_pending,
                    count(*) FILTER (WHERE state='PROCESSING') AS processing,
                    count(*) FILTER (WHERE state='SENT') AS sent,
                    count(*) FILTER (WHERE state='SKIPPED') AS skipped,
                    count(*) FILTER (WHERE state='FAILED') AS failed,
                    count(*) FILTER (WHERE state='PROCESSING' AND lease_until<localtimestamp) AS expired,
                    max(sent_at) FILTER (WHERE state='SENT') AS last_sent FROM notification_email_outbox
                """);
        Object sent = counts.get("last_sent");
        return new Email(emailEnabled, number(counts,"pending"), number(counts,"retry_pending"),
                number(counts,"processing"), number(counts,"sent"), number(counts,"skipped"),
                number(counts,"failed"), number(counts,"expired"),
                sent == null ? null : ((Timestamp)sent).toLocalDateTime());
    }

    private static long number(Map<String,Object> values, String key) { return ((Number)values.get(key)).longValue(); }
    private static LocalDate date(Object value) {
        return value == null ? null : value instanceof LocalDate date ? date : ((java.sql.Date)value).toLocalDate();
    }
    private static Instant instant(Object value) {
        if (value == null) return null;
        if (value instanceof Instant instant) return instant;
        if (value instanceof OffsetDateTime offset) return offset.toInstant();
        return ((Timestamp)value).toInstant();
    }
    private static void warn(RuntimeException failure) {
        LoggerFactory.getLogger(OperationsStatusService.class).warn("운영 상태 조회 실패: {}", failure.getClass().getSimpleName());
    }
}
