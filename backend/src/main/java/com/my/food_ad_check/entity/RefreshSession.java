package com.my.food_ad_check.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 로그인 한 번에 해당하는 Refresh Token 세션. 토큰 원문은 저장하지 않고 해시만 저장한다
@Entity
@Table(name = "refresh_sessions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA 전용
public class RefreshSession {

    // 토큰 앞부분에 들어가는 값. 이 값으로 세션을 찾고, 뒷부분(비밀값)의 해시로 진짜인지 확인한다
    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(name = "previous_token_hash", length = 64)
    private String previousTokenHash;

    @Column(name = "rotated_at")
    private LocalDateTime rotatedAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public RefreshSession(UUID id, Long userId, String tokenHash, LocalDateTime expiresAt, LocalDateTime createdAt) {
        this.id = id;
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.createdAt = createdAt;
    }

    // 새 토큰으로 교체한다. 쓴 토큰은 직전 토큰으로만 남겨서, 다시 오면 재사용으로 알아챈다
    public void rotate(String newTokenHash, LocalDateTime now, LocalDateTime newExpiresAt) {
        this.previousTokenHash = this.tokenHash;
        this.tokenHash = newTokenHash;
        this.rotatedAt = now;
        this.expiresAt = newExpiresAt;
    }

    public boolean isExpired(LocalDateTime now) {
        return !now.isBefore(expiresAt);
    }
}
