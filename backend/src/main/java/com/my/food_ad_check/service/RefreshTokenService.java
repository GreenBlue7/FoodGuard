package com.my.food_ad_check.service;

import com.my.food_ad_check.config.JwtProperties;
import com.my.food_ad_check.entity.RefreshSession;
import com.my.food_ad_check.repository.RefreshSessionRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

// Refresh Token의 발급, 회전(한 번 쓰면 새것으로 교체), 재사용 감지, 폐기
@Service
public class RefreshTokenService {

    private static final int SECRET_BYTES = 32; // 256비트 무작위 값
    // 회전 직후 직전 토큰이 다시 오면, 여러 탭이 동시에 갱신한 것으로 보고 공격으로 판정하지 않는 시간
    private static final Duration REUSE_GRACE = Duration.ofSeconds(10);
    private static final String INVALID_MESSAGE = "로그인이 만료되었습니다. 다시 로그인해 주세요.";

    /**
     * @param userId 토큰 주인
     * @param next   새로 발급한 토큰. 유예 시간 안의 중복 요청이면 null (쿠키는 먼저 받은 새 토큰을 그대로 씀)
     */
    public record Rotation(Long userId, IssuedRefreshToken next) {
    }

    private record ParsedToken(UUID sessionId, String secret) {
    }

    private final RefreshSessionRepository repository;
    private final Duration ttl;
    private final SecureRandom random = new SecureRandom();

    public RefreshTokenService(RefreshSessionRepository repository, JwtProperties properties) {
        this.repository = repository;
        this.ttl = properties.refreshTokenTtl();
    }

    // 로그인 성공 시 새 로그인 세션을 만든다
    @Transactional
    public IssuedRefreshToken start(Long userId) {
        UUID sessionId = UUID.randomUUID();
        String secret = newSecret();
        LocalDateTime now = LocalDateTime.now();
        repository.save(new RefreshSession(sessionId, userId, hash(secret), now.plus(ttl), now));
        return new IssuedRefreshToken(sessionId + "." + secret, ttl);
    }

    // noRollbackFor: 아래에서 세션을 지운 뒤 401을 던지는데, 기본 설정이면 예외 때문에 삭제까지 롤백된다
    @Transactional(noRollbackFor = ResponseStatusException.class)
    public Rotation rotate(String presentedToken) {
        ParsedToken presented = parse(presentedToken).orElseThrow(RefreshTokenService::invalid);
        RefreshSession session = repository.findWithLockById(presented.sessionId())
                .orElseThrow(RefreshTokenService::invalid);
        LocalDateTime now = LocalDateTime.now();
        if (session.isExpired(now)) {
            repository.delete(session);
            throw invalid();
        }

        String presentedHash = hash(presented.secret());
        if (sameHash(presentedHash, session.getTokenHash())) {
            String secret = newSecret();
            session.rotate(hash(secret), now, now.plus(ttl));
            return new Rotation(session.getUserId(), new IssuedRefreshToken(session.getId() + "." + secret, ttl));
        }
        if (sameHash(presentedHash, session.getPreviousTokenHash())
                && session.getRotatedAt().plus(REUSE_GRACE).isAfter(now)) {
            return new Rotation(session.getUserId(), null);
        }
        // 이미 회전된 예전 토큰이 왔다: 토큰이 탈취됐을 수 있으므로 이 로그인 세션 전체를 끝낸다
        // (진짜 사용자와 공격자 모두 다시 로그인해야 함)
        repository.delete(session);
        throw invalid();
    }

    // 로그아웃. 세션 ID만 아는 사람이 남의 세션을 지우지 못하게 비밀값까지 맞아야 지운다
    @Transactional
    public void revoke(String presentedToken) {
        parse(presentedToken).ifPresent(presented -> repository.findWithLockById(presented.sessionId())
                .filter(session -> {
                    String presentedHash = hash(presented.secret());
                    return sameHash(presentedHash, session.getTokenHash())
                            || sameHash(presentedHash, session.getPreviousTokenHash());
                })
                .ifPresent(repository::delete));
    }

    private static Optional<ParsedToken> parse(String token) {
        if (token == null) return Optional.empty();
        int dot = token.indexOf('.');
        if (dot <= 0 || dot == token.length() - 1) return Optional.empty();
        try {
            return Optional.of(new ParsedToken(UUID.fromString(token.substring(0, dot)), token.substring(dot + 1)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private String newSecret() {
        byte[] bytes = new byte[SECRET_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    // 비밀번호와 달리 256비트 무작위 값이라 추측이 불가능하므로, 느린 해시(Argon2) 없이 SHA-256으로 충분하다
    private static String hash(String secret) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    // 비교에 걸리는 시간이 값과 무관하게 일정한 비교 (타이밍으로 해시를 한 글자씩 맞혀 보는 공격 방지)
    private static boolean sameHash(String a, String b) {
        return b != null && MessageDigest.isEqual(
                a.getBytes(StandardCharsets.US_ASCII), b.getBytes(StandardCharsets.US_ASCII));
    }

    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, INVALID_MESSAGE);
    }
}
