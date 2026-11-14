package com.my.food_ad_check.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Service
public class NotificationEmailPreferences {
    private final JdbcTemplate db;
    private final NotificationEmailSender mail;
    private final String publicApi;
    public record Settings(String email, boolean verified, boolean enabled) { }

    public NotificationEmailPreferences(JdbcTemplate db, NotificationEmailSender mail,
            @Value("${notification-email.public-api-url:http://localhost:8080}") String publicApi) {
        this.db = db; this.mail = mail;
        this.publicApi = baseUrl(publicApi);
    }

    static String baseUrl(String url) {
        var uri = java.net.URI.create(url);
        if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                || uri.getHost() == null || uri.getRawQuery() != null || uri.getRawFragment() != null
                || uri.getRawUserInfo() != null) throw new IllegalArgumentException("Invalid email public URL");
        return url.replaceAll("/+$", "");
    }

    public Settings get(Long userId) {
        var rows = db.query("""
                SELECT u.email, p.enabled, (p.verified_at IS NOT NULL AND p.verified_email=u.email) AS verified
                FROM users u LEFT JOIN notification_email_preferences p ON p.user_id=u.id WHERE u.id=?
                """, (rs, row) -> new Settings(rs.getString("email"), rs.getBoolean("verified"),
                rs.getBoolean("enabled") && rs.getBoolean("verified")), userId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "회원을 찾을 수 없습니다.");
        return rows.get(0);
    }

    @Transactional
    public Settings setEnabled(Long userId, boolean enabled) {
        // Serialize opt-out and a sender's final eligibility check for this user.
        lockUser(userId);
        Settings settings = get(userId);
        if (enabled && !settings.verified()) throw new ResponseStatusException(HttpStatus.CONFLICT, "먼저 이메일 주소를 확인해 주세요.");
        db.update("""
                INSERT INTO notification_email_preferences(user_id,enabled) VALUES (?,?)
                ON CONFLICT(user_id) DO UPDATE SET enabled=excluded.enabled
                """, userId, enabled);
        if (!enabled) db.update("""
                UPDATE notification_email_outbox SET state='SKIPPED', lease_until=NULL
                WHERE user_id=? AND state IN ('PENDING','PROCESSING')
                """, userId);
        return get(userId);
    }

    @Transactional
    public void requestVerification(Long userId) {
        mail.requireEnabled();
        lockUser(userId);
        Settings settings = get(userId);
        if (settings.verified()) return;
        db.update("INSERT INTO notification_email_preferences(user_id) VALUES (?) ON CONFLICT DO NOTHING", userId);
        Integer recent = db.queryForObject("""
                SELECT count(*) FROM notification_email_preferences WHERE user_id=?
                AND verification_last_sent_at > localtimestamp - interval '1 minute'
                """, Integer.class, userId);
        if (recent != null && recent > 0) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "1분 후 다시 요청해 주세요.");
        byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        db.update("""
                UPDATE notification_email_preferences SET token_hash=?, token_expires_at=localtimestamp+interval '30 minutes',
                verification_last_sent_at=localtimestamp, verified_email=? WHERE user_id=?
                """, hash(token), settings.email(), userId);
        // A send failure rolls back the token and the throttle, so the user can retry.
        mail.send(settings.email(), "[FoodGuard] 이메일 주소 확인", """
                FoodGuard 제품 정보 변경 알림을 받을 이메일인지 확인해 주세요.
                아래 링크를 열고 '이메일 확인' 버튼을 눌러주세요. 링크는 30분 동안 유효합니다.
                확인만으로 이메일 알림이 켜지지는 않습니다. 로그인 후 수신 설정을 켜주세요.

                """ + publicApi + "/email/verify#token=" + token);
    }

    @Transactional
    public void verify(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) invalidToken();
        // GET links never consume tokens. POST consumes a valid token exactly once.
        int changed = db.update("""
                UPDATE notification_email_preferences p SET verified_at=localtimestamp, token_hash=NULL,token_expires_at=NULL
                FROM users u WHERE u.id=p.user_id AND p.token_hash=? AND p.token_expires_at>localtimestamp
                AND p.verified_email=u.email
                """, hash(token));
        if (changed != 1) invalidToken();
    }

    private void lockUser(Long userId) {
        if (db.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE", userId).isEmpty())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "회원을 찾을 수 없습니다.");
    }
    private static void invalidToken() { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "확인 링크가 만료되었거나 유효하지 않습니다."); }
    static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
