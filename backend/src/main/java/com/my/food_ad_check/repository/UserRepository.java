package com.my.food_ad_check.repository;

import com.my.food_ad_check.entity.User;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

// 호출하는 쪽에서 이메일을 먼저 User.normalizeEmail로 정규화
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);
}
