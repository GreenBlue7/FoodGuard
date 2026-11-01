## 로컬 개발 환경

### 1 필요한 것

- Docker Desktop
- JDK 17 이상 (없는 Java 17 툴체인은 Gradle이 자동으로 받음)
- Node.js 22.18 이상 또는 24.12 이상

### 2 `.env` 만들기

레포 루트에서 `.env.example`을 `.env`로 복사한 뒤 값을 채워야 작동함. `.env`는 gitignore해놨음

| 키                                     | 설명                                                                                                               |
| -------------------------------------- | ------------------------------------------------------------------------------------------------------------------ |
| `GEMINI_API_KEY`                       | Google AI Studio에서 각자 발급                                                                                     |
| `JWT_SECRET`                           | `openssl rand -base64 32`의 출력값. **없으면 백엔드가 시작되지 않음.** 각자 다른 값을 쓰고 공유X                   |
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | 백엔드가 접속할 DB. docker compose의 DB를 쓰면 `jdbc:postgresql://localhost:5433/foodguard`, `postgres`, `devpass` |

### 3 실행

```bash
docker compose up -d --build            # DB(5433) + judge(8000)

cd backend && ./gradlew bootRun         # 8080. Windows는 gradlew.bat bootRun
cd frontend && npm ci && npm run dev    # http://localhost:5173
```

백엔드는 반드시 `backend` 폴더에서 실행해야 루트의 `.env`를 읽음

### 4 로그인

- 회원가입·로그인을 제외한 모든 API는 로그인이 필요
- 검수 기록은 만든 사용자만 열람 가능

### 5 기존 DB를 쓰던 경우 (한 번만)

SQL은 docker 볼륨을 처음 만들 때만 자동 실행됨. 기존 DB에 `users` 테이블이 없으면 백엔드가 안 켜짐

- 데이터 필요 없음: `docker compose down -v` 후 `docker compose up -d` (제품 데이터도 삭제됨)
- 데이터 유지: 아래 순서대로 실행
  ```bash
  docker compose exec -T db psql -U postgres -d foodguard -c "DELETE FROM inspection_records;"
  docker compose exec -T db psql -U postgres -d foodguard < backend/db/003_users.sql
  docker compose exec -T db psql -U postgres -d foodguard < backend/db/004_refresh_sessions.sql
  ```

## Git 워크플로우

### 1 브랜치 전략

| 브랜치                           | 용도                               |
| -------------------------------- | ---------------------------------- |
| `main`                           | 최종 발표 배포용                   |
| `develop`                        | 메인 브랜치 - 모든 PR의 merge 대상 |
| `feature/<topic>`                | 기능 개발                          |
| `chore/<topic>`, `infra/<topic>` | 빌드/인프라/설정 변경              |
| `fix/<topic>`                    | 버그 수정                          |

### 2 작업 원칙

```bash
# 로컬 develop 브랜치로 이동
git checkout develop
# origin/develop 브랜치 최신 데이터 fetch + rebase
git pull --rebase origin develop
# 최신 develop 상태에서 분기하여 작업
git checkout -b feature/내가할일

# 작업 완료 후: origin/develop이 앞으로 이동했을 수 있으므로
git fetch origin # origin/develop 최신화
git rebase origin/develop # 내 커밋들을 최신 develop 위로 재배치
# Conflict 발생 시: 충돌 파일 수정 --> git add <파일> --> git rebase --continue

# --force-with-lease: 마지막 fetch후 다른 사람이 같은 브랜치에 push했을 경우 강제 push거부
git push -u --force-with-lease origin feature/내가할일
# 이후 동일 브랜치 재push 시: git push --force-with-lease

# GitHub에서 PR 생성 → develop으로
```
