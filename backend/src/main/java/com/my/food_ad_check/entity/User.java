package com.my.food_ad_check.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.Locale;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "users") // "user"는 PostgreSQL 예약어
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA 전용 (외부에서 빈 객체 생성 금지)
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String email;

    // Argon2id 해시
    // 평문 비밀번호는 엔티티에 없음
    @Column(nullable = false)
    private String password;

    @Column(name = "company_name", length = 100)
    private String companyName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    // id는 DB가 정하고, role과 createdAt은 가입 시점에 고정
    public User(String email, String encodedPassword, String companyName) {
        this.email = normalizeEmail(email);
        this.password = encodedPassword;
        this.companyName = companyName;
        this.role = Role.USER;
        this.createdAt = LocalDateTime.now();
    }

    public void changePassword(String encodedPassword) {
        this.password = encodedPassword;
    }

    // 이메일 대소문자 정규화
    // 조회할 때도 같은 규칙 사용
    public static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
