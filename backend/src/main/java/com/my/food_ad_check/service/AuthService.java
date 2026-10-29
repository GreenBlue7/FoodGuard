package com.my.food_ad_check.service;

import com.my.food_ad_check.dto.LoginRequest;
import com.my.food_ad_check.dto.LoginResponse;
import com.my.food_ad_check.dto.SignupRequest;
import com.my.food_ad_check.dto.UserResponse;
import com.my.food_ad_check.entity.User;
import com.my.food_ad_check.repository.UserRepository;

import java.util.Optional;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AuthService {

    private static final String DUPLICATE_EMAIL_MESSAGE = "이미 가입된 이메일입니다.";
    private static final String EMAIL_UNIQUE_CONSTRAINT = "uq_users_email";
    // 없는 이메일과 틀린 비밀번호에 같은 메시지를 써서 가입 여부가 드러나지 않게
    private static final String INVALID_CREDENTIALS_MESSAGE = "이메일 또는 비밀번호가 올바르지 않습니다.";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    // 없는 이메일일 때 대신 비교할 가짜 해시
    // 두 실패 경우의 응답 시간을 같게 맞춰서 타이밍 공격 방지
    private final String dummyPasswordHash;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, TokenService tokenService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.dummyPasswordHash = passwordEncoder.encode("timing-equalizer-not-a-real-password");
    }

    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest request) {
        Optional<User> user = userRepository.findByEmail(User.normalizeEmail(request.email()));
        String storedHash = user.map(User::getPassword).orElse(dummyPasswordHash);
        boolean passwordMatches = passwordEncoder.matches(request.password(), storedHash);
        if (user.isEmpty() || !passwordMatches) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, INVALID_CREDENTIALS_MESSAGE);
        }
        return tokenService.issueAccessToken(user.get());
    }

    // ------ save()가 아니라 saveAndFlush()인 이유 ------
    // JPA는 기본적으로 쓰기 지연
    // save()를 호출해도 INSERT SQL이 바로 나가지 않고 영속성 컨텍스트에 쌓아뒀다가,
    // 트랜잭션 커밋 직전 flush 시점에 한꺼번에 DB로 보냄
    // @Transactional의 커밋은 메서드가 끝난 뒤 프록시가 수행
    @Transactional
    public UserResponse signUp(SignupRequest request) {
        // 이메일 스트링 정규화
        String email = User.normalizeEmail(request.email());
        // 동일한 이메일의 유저가 존재시 에러 반환
        // 동시 가입 경쟁은 db의 unique 제약이 막음
        if (userRepository.existsByEmail(email)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, DUPLICATE_EMAIL_MESSAGE);
        }

        User user = new User(email, passwordEncoder.encode(request.password()), trimToNull(request.companyName()));
        try {
            // 바로 flush해서, 같은 이메일 동시 가입이 커밋 시점이 아니라 여기서 실패하게 함
            return UserResponse.from(userRepository.saveAndFlush(user));
        } catch (DataIntegrityViolationException e) {
            if (e.getCause() instanceof ConstraintViolationException violation
                    && EMAIL_UNIQUE_CONSTRAINT.equals(violation.getConstraintName())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, DUPLICATE_EMAIL_MESSAGE, e);
            }
            throw e;
        }
    }

    // trimToNull() --> 양쪽 공백을 제거한 뒤, 결과가 빈 문자열이면 null로 바꿔 반환
    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
