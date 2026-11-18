# 운영 상태 조회 (2026-11-16)

`GET /api/operations/status` — ADMIN 권한의 Access Token 필요.
로그인하지 않으면 401, 일반 USER이면 403이다. 기존 계정 권한을 자동으로 올리지 않는다.
이 변경은 조회 API이며 별도의 관리자 화면은 포함하지 않는다.

## 확인 가능한 항목

- database: SELECT 1 연결 확인 결과와 DB 시간대.
- inspections: PENDING/RUNNING/DONE/FAILED/TIMEOUT 전체 기록 수, 선점 시간이 만료된
  RUNNING 수, 가장 오래된 실제 대기열의 접수 시각, 작업자 활성화 설정.
- productSync: 정기 수집 설정, coveredThrough(한국 날짜 기준 수집 범위), 마지막 전체
  수집 성공일, 실행 기록상 마지막 성공 시각, 가장 최근 수집의 시작/종료/모드/상태/오류 코드.
- email: 제품 변경 안내 outbox의 대기/재시도 대기/처리 중/SMTP 수락/취소/최종 실패/선점 만료 수.
- externalChecks: judge, mfds, smtp 모두 NOT_CHECKED. 조회에서 외부 서비스에 접속하지 않는다.

email 숫자는 알림 대기열 항목 수이며 이메일 통수가 아니다. 한 메일에 여러 항목이 묶일 수 있다.
SENT는 SMTP 서버 수락이며 수신함 도착·열람 보장이 아니다. 주소 확인 메일은 이 outbox 집계에
포함하지 않는다. email.lastSentAt은 기존 DB의 시간대 없는 시각이며 database.timeZone 기준이다.
대기열/수집 실행의 timestamptz는 UTC Instant로 반환한다. 과거 검사·메일 시간은 다시 쓰지 않는다.

## 상태와 오류

HTTP 200:
- OK: 조회 성공, 아래 주의 조건 없음. 실제 AI/메일/API 연결이나 작업자 생존을 보장하지 않는다.
- ATTENTION: 검사 선점 만료, 메일 선점 만료/최종 실패, 작업자 비활성화 상태의 대기 검사,
  가장 최근 정기 수집 실행 실패 중 하나 이상. 수집/이메일 기능이 꺼진 것 자체는 오류로 단정하지 않는다.

HTTP 503, status=UNAVAILABLE:
- DATABASE_UNAVAILABLE: DB 연결 확인 실패. database.status=DOWN.
- STATUS_QUERY_FAILED: 연결 확인은 됐으나 스키마/집계 조회 실패. database.status=UP.
조회하지 못한 inspections/productSync/email은 null이다. 확인하지 못한 내용을 0건으로 만들지 않는다.
응답은 no-store이며 SQL, 예외 전문, 호스트, 키, 계정, 광고 문구, 수신 주소는 반환하지 않는다.

여러 SELECT가 진행되는 동안 정상 작업으로 숫자가 바뀔 수 있으며 원자적 회계 스냅샷은 아니다.
FAILED 검사 수는 전체 저장 기록 기준이다. 최종 실패 메일은 정리/처리 전까지 주의 대상에 남는다.
이 API는 작업 복구, 재검수, 수집, 메일 발송, 실패 초기화를 실행하지 않는다.
서버 프로세스 자체가 시작되지 않으면 이 API도 열리지 않으므로 컨테이너 로그를 확인해야 한다.

## 정기 수집 실행 기록

014_product_sync_runs.sql을 시작 전에 적용한다. ProductRefreshService는 시작을 먼저
저장하고, 페이지 수집과 체크포인트 저장까지 끝나야 SUCCEEDED를 기록한다.
실패 코드: IMPORT_NOT_COMPLETED / IMPORT_EXCEPTION / CHECKPOINT_FAILED.
API 결과 메시지·원문·인증정보·예외 메시지를 저장하지 않는다. 실패 시 기존 체크포인트 유지.
DB 장애 등으로 실패 기록 자체도 저장하지 못하면 원래 오류를 숨기지 않는다.

RUNNING은 완료 기록이 없는 실행이다. 서버 중단이나 기록 실패 후 남을 수 있으며 프로세스가
현재 살아 있다는 증거가 아니다. 조회로 자동 실패 처리하거나 API를 다시 호출하지 않는다.
실행 기록은 DB에 보존돼 재시작 후에도 확인할 수 있다.

이 기록의 범위는 ProductRefreshService를 통한 정기/동일 경로 갱신이다.
기존 직접 load-all API나 과거 별도 데이터 보충 도구의 실행 기록을 소급 생성하지 않는다.
기존 coveredThrough/lastFullSyncOn은 유지하며, 새 기록이 없으면 latestRun과
lastRecordedSuccessAt은 null(미확인)로 제공한다. judge의 사례 기준일 dataAsOf와는 별개다.

예시(실제 토큰은 저장소에 쓰지 않는다):

```sh
curl -H 'Authorization: Bearer <ADMIN_ACCESS_TOKEN>' https://your-service.example/api/operations/status
```

관리 화면 연결 시 위 필드를 읽기 전용으로 표시하면 된다. 개인정보/실패 원문 조회나
재발송·재실행 버튼은 이번 API 범위에 포함하지 않는다.
