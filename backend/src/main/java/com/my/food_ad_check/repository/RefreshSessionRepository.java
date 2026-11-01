package com.my.food_ad_check.repository;

import com.my.food_ad_check.entity.RefreshSession;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface RefreshSessionRepository extends JpaRepository<RefreshSession, UUID> {

    // SELECT ... FOR UPDATE: 같은 세션을 동시에 갱신하는 요청은 한 번에 하나씩 처리된다
    // (잠그지 않으면 두 요청이 같은 토큰을 동시에 "아직 안 쓴 토큰"으로 보고 둘 다 회전할 수 있음)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<RefreshSession> findWithLockById(UUID id);
}
