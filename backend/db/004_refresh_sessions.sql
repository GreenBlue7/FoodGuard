-- 로그인 한 번 = 한 행. Refresh Token으로 Access Token을 다시 받는 "로그인 세션"을 기록한다.
-- 003_users.sql 다음에 실행. 여러 번 실행해도 안전하다.
-- 토큰 원문은 "<id>.<비밀값>" 형태로 쿠키에만 있고, DB에는 비밀값의 SHA-256 해시만 저장한다.
CREATE TABLE IF NOT EXISTS refresh_sessions (
    id uuid PRIMARY KEY,
    -- 회원을 삭제하면 그 회원의 로그인 세션도 모두 삭제된다
    user_id bigint NOT NULL
        CONSTRAINT fk_refresh_session_user REFERENCES users (id) ON DELETE CASCADE,
    -- 지금 유효한 토큰의 해시
    token_hash varchar(64) NOT NULL,
    -- 직전에 회전된 토큰의 해시. 여러 탭이 동시에 갱신할 때를 위한 짧은 유예에 쓴다
    previous_token_hash varchar(64),
    rotated_at timestamp(6),
    -- 마지막 갱신 시점부터 다시 계산된다 (일정 기간 사용하지 않으면 만료)
    expires_at timestamp(6) NOT NULL,
    created_at timestamp(6) NOT NULL
);

-- 회원 삭제 시 CASCADE 대상 검색에 쓰인다.
CREATE INDEX IF NOT EXISTS ix_refresh_sessions_user_id ON refresh_sessions (user_id);
