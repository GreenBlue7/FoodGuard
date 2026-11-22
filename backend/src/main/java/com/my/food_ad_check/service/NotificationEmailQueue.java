package com.my.food_ad_check.service;

import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class NotificationEmailQueue {
    private final JdbcTemplate db;
    private final TransactionTemplate transactions;
    private final NotificationEmailSender mail;
    private final String publicApi;
    private record Item(long id, String product, String reportNo, String detectedAt) { }
    private record Batch(UUID id, long userId, String email, List<Item> items) { }

    public NotificationEmailQueue(JdbcTemplate db, PlatformTransactionManager manager, NotificationEmailSender mail,
            @Value("${notification-email.public-api-url:http://localhost:8080}") String publicApi) {
        this.db = db; this.transactions = new TransactionTemplate(manager); this.mail = mail;
        this.publicApi = NotificationEmailPreferences.baseUrl(publicApi);
    }

    /** Called inside the same transaction as the web notification. No SMTP call here. */
    public void enqueue(long notificationId) {
        db.update("""
                INSERT INTO notification_email_outbox(notification_id,user_id)
                SELECT n.id,n.user_id FROM product_change_notifications n
                JOIN users u ON u.id=n.user_id JOIN notification_email_preferences p ON p.user_id=u.id
                WHERE n.id=? AND p.enabled AND p.verified_at IS NOT NULL AND p.verified_email=u.email
                ON CONFLICT(notification_id) DO NOTHING
                """, notificationId);
    }

    public int dispatch() {
        mail.requireEnabled();
        int sent = 0;
        // Bounded work: at most 20 recipients x 100 changes per scheduler invocation.
        for (int index=0; index<20; index++) {
            Batch batch = transactions.execute(status -> claim());
            if (batch == null) break;
            try {
                Boolean delivered = transactions.execute(status -> deliver(batch));
                if (Boolean.TRUE.equals(delivered)) sent++;
            } catch (RuntimeException failure) {
                // Do not store SMTP exception messages: they may contain addresses or server credentials.
                transactions.executeWithoutResult(status -> db.update("""
                        UPDATE notification_email_outbox SET state=CASE WHEN attempts>=5 THEN 'FAILED' ELSE 'PENDING' END,
                        available_at=localtimestamp + make_interval(mins => (1 << least(attempts-1,4))),
                        lease_until=NULL,last_error='SMTP_SEND_FAILED'
                        WHERE batch_id=? AND state='PROCESSING'
                        """, batch.id()));
            }
        }
        return sent;
    }

    private Batch claim() {
        db.update("""
                UPDATE notification_email_outbox SET state=CASE WHEN attempts>=5 THEN 'FAILED' ELSE 'PENDING' END,
                lease_until=NULL,last_error='DELIVERY_LEASE_EXPIRED'
                WHERE state='PROCESSING' AND lease_until<localtimestamp
                """);
        db.update("""
                UPDATE notification_email_outbox o SET state='SKIPPED' WHERE state='PENDING' AND NOT EXISTS
                (SELECT 1 FROM notification_email_preferences p JOIN users u ON u.id=p.user_id
                 WHERE p.user_id=o.user_id AND p.enabled AND p.verified_at IS NOT NULL AND p.verified_email=u.email)
                """);
        var candidates = db.queryForList("""
                SELECT u.id,u.email FROM users u JOIN notification_email_preferences p ON p.user_id=u.id
                WHERE p.enabled AND p.verified_at IS NOT NULL AND p.verified_email=u.email
                AND EXISTS(SELECT 1 FROM notification_email_outbox o WHERE o.user_id=u.id AND o.state='PENDING'
                           AND o.available_at<=localtimestamp)
                AND NOT EXISTS(SELECT 1 FROM notification_email_outbox o WHERE o.user_id=u.id AND o.state='PROCESSING')
                ORDER BY u.id LIMIT 1 FOR UPDATE OF u SKIP LOCKED
                """);
        if (candidates.isEmpty()) return null;
        long userId = ((Number)candidates.get(0).get("id")).longValue();
        List<Item> items = db.query("""
                SELECT o.notification_id, coalesce(p.prdlst_nm,'제품 정보 없음') AS product,c.prdlst_report_no,c.detected_at
                FROM notification_email_outbox o JOIN product_change_notifications n ON n.id=o.notification_id
                JOIN product_function_changes c ON c.id=n.change_id LEFT JOIN products p ON p.prdlst_report_no=c.prdlst_report_no
                WHERE o.user_id=? AND o.state='PENDING' AND o.available_at<=localtimestamp
                ORDER BY o.queued_at,o.notification_id LIMIT 100 FOR UPDATE OF o
                """, (rs,row) -> new Item(rs.getLong("notification_id"),rs.getString("product"),
                rs.getString("prdlst_report_no"),rs.getTimestamp("detected_at").toLocalDateTime().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))), userId);
        if (items.isEmpty()) return null;
        UUID batchId = UUID.randomUUID();
        for (Item item : items) db.update("""
                UPDATE notification_email_outbox SET state='PROCESSING',attempts=attempts+1,
                lease_until=localtimestamp+interval '5 minutes',batch_id=? WHERE notification_id=?
                """, batchId,item.id());
        return new Batch(batchId,userId,(String)candidates.get(0).get("email"),items);
    }

    private boolean deliver(Batch batch) {
        // Hold this user's lock through SMTP so a completed opt-out cannot be followed by a new send.
        var users = db.queryForList("SELECT email FROM users WHERE id=? FOR UPDATE",batch.userId());
        Integer eligible = db.queryForObject("""
                SELECT count(*) FROM notification_email_preferences WHERE user_id=? AND enabled
                AND verified_at IS NOT NULL AND verified_email=?
                """, Integer.class,batch.userId(),batch.email());
        Integer active = db.queryForObject("SELECT count(*) FROM notification_email_outbox WHERE batch_id=? AND state='PROCESSING'",Integer.class,batch.id());
        if (users.isEmpty() || !batch.email().equals(users.get(0).get("email")) || eligible == null || eligible != 1
                || active == null || active != batch.items().size()) {
            db.update("UPDATE notification_email_outbox SET state='SKIPPED',lease_until=NULL WHERE batch_id=? AND state='PROCESSING'",batch.id());
            return false;
        }
        StringBuilder body = new StringBuilder("검수한 제품의 기능성 정보 변경이 발견되었습니다.\n")
                .append("이 알림은 기존 광고의 위반이 확정되었다는 뜻이 아닙니다.\n")
                .append("현재 게재 중인 광고를 확인한 뒤 필요하면 재검수해 주세요.\n\n");
        for (Item item : batch.items()) body.append(item.product()).append(" (신고번호: ").append(item.reportNo())
                .append(")\n변경 발견: ").append(item.detectedAt()).append(" (한국 시각)\n현재 광고 확인: ")
                .append(publicApi).append("/email/notifications/").append(item.id()).append("\n\n");
        body.append("제품 정보 변경을 수집한 시점에 발송하는 안내입니다. 실시간 변경 감지를 보장하지 않습니다.\n")
                .append("이메일 수신 설정: ").append(publicApi).append("/email/settings\n");
        mail.send(batch.email(),"[FoodGuard] 제품 기능성 정보 변경 " + batch.items().size() + "건",body.toString());
        db.update("""
                UPDATE notification_email_outbox SET state='SENT',sent_at=localtimestamp,lease_until=NULL,last_error=NULL
                WHERE batch_id=? AND state='PROCESSING'
                """,batch.id());
        return true;
    }
}
