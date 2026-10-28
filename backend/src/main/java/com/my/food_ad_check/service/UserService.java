package com.my.food_ad_check.service;

import com.my.food_ad_check.dto.UserResponse;
import com.my.food_ad_check.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class UserService {

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    // 토큰 발급 후 계정이 삭제되면 유효한 토큰이 남아 있을 수 있음 --> 재로그인 필요
    @Transactional(readOnly = true)
    public UserResponse getUser(Long userId) {
        return userRepository.findById(userId)
                .map(UserResponse::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        "계정을 찾을 수 없습니다. 다시 로그인해 주세요."));
    }
}