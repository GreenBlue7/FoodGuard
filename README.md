> 통합 안내: 새 DB는 최신 migration과 시드를 자동 적재합니다. 기존 이력 없는 DB는 자동 변환하지 않으며 안전하게 중단합니다. 자세한 내용은 [전체 실행 통합 안내](backend/FULL_STACK_INTEGRATION.md)를 참고하세요.

## Docker로 전체 실행하기

### 1 `.env` 준비

루트의 `.env` 에 `.env.example`을 복사한 뒤 다음 값들을 채우기

- Google 직접 호출: `LLM_PROVIDER=gemini`, `GEMINI_API_KEY` 설정
- OpenRouter 경유: `LLM_PROVIDER=openrouter`, `OPENROUTER_API_KEY` 설정
- 두 방식 모두 `GEMINI_MODEL`을 설정하며 키는 커밋하지 않습니다.
- `JWT_SECRET`: `openssl rand -base64 32`의 출력값

### 2 실행

```bash
docker compose --profile full up -d --build
```

- 첫 실행은 이미지 빌드와 임베딩 모델(약 2.3GB) 다운로드로 수 분 시간소요
- DB를 처음 만들 때 식약처 건강기능식품 데이터와 적발 사례 시드가 자동으로 적재

### 3 사용

1. http://localhost:8080 접속
2. 회원가입
3. 제품 검색(예: `홍삼`) → 광고 문구 입력 → 검사
4. 접수 즉시 번호표를 받고 대기·진행·완료 상태를 조회합니다. 검사 안에서 LLM을 여러 번 호출할 수 있습니다.
5. 입력은 공백·줄바꿈 포함 최대 3,000자이며 여러 검사는 최대 3건을 목록으로 접수합니다.

### 4 종료

```bash
docker compose --profile full down
```

처음 상태로 되돌리려면 `-v`를 붙임. 가입한 계정과 검수 기록이 지워지고, 제품과 사례는 다음 실행 때 다시 자동으로 들어감

```bash
docker compose --profile full down -v
```

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
- DB 볼륨을 새로 만들면 제품과 사례 시드가 자동으로 들어감. 식약처 API 키와 적재 작업은 필요X
- 사례 CSV(`judge/data/`)를 바꾸면 시드(`judge/sql/seed_cases.sql.gz`)를 다시 생성

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
### 6 시드 데이터 갱신

DB를 새로 만들 때 들어가는 데이터는 두 파일. 원본 데이터가 바뀌면 아래 순서로 다시 만들고 커밋

| 시드 | 원본 |
| --- | --- |
| `backend/db/seed_products.sql.gz` | 식약처 Open API I0030 (건강기능식품 품목제조 신고사항) |
| `judge/sql/seed_cases.sql.gz` | `judge/data/cases_mfds_2025.csv` |

#### 제품 데이터 (식약처 API)

1. 식품안전나라 Open API에서 I0030 키를 발급받아 `.env`에 추가. 키는 `.env`에만 두고 커밋 금지

   ```
   FOOD_SAFETY_API_KEY=발급받은 키
   FOOD_SAFETY_IMPORT_ENABLED=true
   ```

2. `docker compose up -d`로 db, judge를 띄우고 `backend` 폴더에서 `./gradlew bootRun`

3. 화면에서 회원가입한 뒤 그 계정을 ADMIN으로 변경 (적재 API는 ADMIN 전용)

   ```bash
   docker compose exec db psql -U postgres -d foodguard -c "UPDATE users SET role = 'ADMIN' WHERE email = '가입한 이메일';"
   ```

4. 다시 로그인해서 JWT토큰 받기. 권한은 토큰을 발급할 때 정해지므로 권한을 바꾼 뒤 새로 받아야 함

   ```bash
   curl -s -X POST http://localhost:8080/api/auth/login -H 'Content-Type: application/json' -d '{"email":"가입한 이메일","password":"비밀번호"}'
   ```

5. 응답의 `accessToken`으로 적재 실행. 5분 정도 걸리고 끝날 때까지 응답이 없음. 진행 상황은 백엔드 로그의 `I0030 적재 진행`

   ```bash
   curl -X POST http://localhost:8080/api/data/load-all -H "Authorization: Bearer <accessToken값>"
   ```

   - 이미 있는 제품의 인정 기능성 목록은 유지되고, 새 제품만 자동으로 파싱됨 (`backend/README.md` 참고)
   - 실패하면(502) 같은 명령을 다시 실행. 저장된 제품은 덮어쓰기만 함

6. 레포 루트에서 시드 다시 만들기

   ```bash
   docker compose exec db sh -c "pg_dump -U postgres -d foodguard --data-only -t products -t product_approved_functions | gzip > /tmp/seed_products.sql.gz"
   docker compose cp db:/tmp/seed_products.sql.gz backend/db/seed_products.sql.gz
   ```

7. `docker-compose.yml`의 `# Product seed (날짜)` 주석을 오늘 날짜로 변경

8. `.env`에서 `FOOD_SAFETY_*` 두 줄 삭제

#### 사례 데이터 (CSV)

1. `judge/data/cases_mfds_2025.csv`를 새 파일로 교체, 2026년 이후 날짜가 한 줄이라도 있으면 전체가 거부됨 (2026년 사례는 평가용)
2. 레포 루트에서 적재. 적재 스크립트는 같은 `id`를 덮어쓰기만 하고 지우지 않으므로, 삭제된 행이 남지 않게 먼저 비움

   ```bash
   docker compose cp judge/data/cases_mfds_2025.csv judge:/tmp/cases.csv
   docker compose exec db psql -U postgres -d foodguard -c "TRUNCATE cases;"
   docker compose exec judge python -m scripts.load_cases /tmp/cases.csv
   ```

   출력의 `N줄을 읽었습니다`와 `모두 N줄입니다`의 숫자가 같아야 함

3. 시드 다시 만들기

   ```bash
   docker compose exec db sh -c "pg_dump -U postgres -d foodguard --data-only -t cases | gzip > /tmp/seed_cases.sql.gz"
   docker compose cp db:/tmp/seed_cases.sql.gz judge/sql/seed_cases.sql.gz
   ```

#### 확인

시드는 DB 볼륨이 비어 있을 때만 들어가므로, 볼륨을 지우고 다시 띄워서 확인. 가입한 계정과 검수 기록도 지워짐

```bash
docker compose down -v
docker compose up -d
docker compose exec db psql -U postgres -d foodguard -c "SELECT (SELECT count(*) FROM products) AS products, (SELECT count(*) FROM cases) AS cases;"
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
