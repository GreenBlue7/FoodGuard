# 백엔드 CI

`.github/workflows/backend-ci.yml`은 develop/main 대상 PR과 해당 브랜치 push, 수동 실행에서 동작한다. backend 코드, migration runner, judge 사례 테이블 SQL 또는 workflow가 변경되면 실행한다. 브랜치 보호에 연결될 수 있는 기존 workflow/job 이름은 유지한다.

## 검사 순서

1. Java 17 및 Gradle 의존성 준비.
2. 독립된 PostgreSQL 17 + pgvector 서비스 시작 및 준비 상태 확인.
3. 배포용 `deploy/migrate.sh`로 backend의 번호 있는 SQL과 `judge/sql/cases.sql` 적용. 두 번째 실행으로 동일 migration의 재실행도 확인한다.
4. `./gradlew clean test bootJar --no-daemon --console=plain`으로 테스트와 실행 파일 빌드를 수행한다. 이전 build 디렉터리의 보고서를 재사용하지 않는다.
5. 실패하더라도 취소되지 않은 실행은 생성된 HTML/JUnit XML 보고서를 `backend-test-reports` artifact로 7일 보관한다. 결과 파일이 없으면 경고를 남긴다.

CI용 DB·JWT 값은 공개된 테스트 전용 값이며 운영 설정으로 사용하지 않는다. 실제 사용자/제품/광고 데이터나 운영 DB/키는 사용하지 않는다. 제품·사례 seed를 적재하지 않으며 테스트가 필요한 자료를 만든다. judge·식약처·메일은 테스트 대역을 사용하고, 자동 worker·데이터 수집·메일 발송은 끈다. GitHub 저장소 쓰기 권한이나 운영 secrets 없이 실행할 수 있다.

## 결과 확인과 범위

PR의 Checks 또는 저장소 Actions → Backend CI에서 실행 결과를 확인한다. 테스트 실패 시 실행 상세의 Artifacts에서 backend-test-reports를 내려받아 HTML 또는 XML을 확인할 수 있다. CI 통과는 실제 AI 판정 품질·시간·비용, 외부 SMTP, 전체 서비스 배포의 정상 동작을 보장하지 않는다.

로컬 검증은 같은 새 PostgreSQL/pgvector와 migration runner로 수행할 수 있다. GitHub Actions 실행 결과는 해당 커밋의 PR Checks 또는 Actions에서 확인한다.

공식 참고: [서비스 DB](https://docs.github.com/en/actions/tutorials/use-containerized-services/create-postgresql-service-containers), [보고서 artifact](https://github.com/actions/upload-artifact).
