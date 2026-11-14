# 기능성 변경 알림과 수정 후 재검수

## 동작

- 식약처 I0030 데이터를 갱신했을 때 기존 제품의 `PRIMARY_FNCLTY` 원문이 달라지면 이전 값·새 값·감지 시각을 저장한다.
- 해당 제품을 검수했던 회원에게 서비스 내 알림을 저장한다. 동일 제품을 여러 번 검사했어도 변경 1건당 회원에게 알림 1개만 생성한다.
- 첫 제품 등록과 원문이 동일한 재갱신에는 알림을 생성하지 않는다. 여러 번의 실제 변경은 각각 별도 알림이다.
- 제품 갱신, 변경 기록, 고객 알림은 페이지 단위로 함께 커밋·롤백한다.
- 비교 대상은 원문이다. 띄어쓰기·주석 변경도 감지될 수 있으며, 변경 감지는 법적 효능 변경을 확정하지 않는다.
- 원문이 바뀌면 `approvedFunctions`도 새 원문에서 자동 재추출하여 갱신한다. 일상적인 관리자 승인 절차는 없다.
- 추출 결과가 비어 있거나 형식 검사를 통과하지 못하면 옛 목록을 비우고 `function_source_review_required=true`로 보류한다.
  이 필드는 자동 추출 실패 상태를 의미하며, 운영자에게 매번 검토를 요청하는 절차가 아니다.
  새 데이터가 수집될 때 재추출에 성공하면 자동으로 해제한다. 실패한 제품은 새 검사·재검수·추천을 409로 보류한다.
  신규 검수 응답의 `productDataReviewRequired`와 광고 불러오기 응답의 `productReviewRequired`로 상태를 전달한다.
- 자동 추출은 텍스트 분리·형식 검사이며 식약처의 별도 승인이나 법적 판단을 대체하지 않는다.
- 2026-11-12 보완: 특정 기능성 단어가 없다는 이유로 보류하지 않는다. `배변활동 원활`, `식이섬유 보충`도 유지한다.
  국문·영문 표기와 번역 이어쓰기, 성분 제목·기능성 항목 제목을 구분한다. 영문만 있는 원문은 계속 보류한다.
  제조방법 조건은 삭제하지 않고 원문 그대로 보존한다. 일일섭취량·조건 주석의 해석이 필요한 제품은 보류를 유지한다.
  기존 DB 목록을 일괄 변경하지 않으며, 새 파서는 신규·변경 데이터 자동 추출과 조회 시 파싱에 적용한다.
- 검수했던 제품이 고객 상품의 기준이다. 별도의 상품 등록·구독 목록이나 이메일/푸시 발송은 포함하지 않는다.

## DB 적용

기존 001~004 스키마 적용 후 `db/005_product_changes_reinspections.sql`과
`db/006_product_sync_state.sql`, `db/007_product_version.sql`을 순서대로 적용한다.
이 스크립트는 기존 제품·검수 기록을 삭제하지 않으며 재실행할 수 있다.
기존 운영 DB에 자동 적용하지 않는다. 새 Compose DB에도 서버 시작 전에 005를 적용해야 한다.
새 배포 DB는 deploy/compose.yml의 migration 서비스가 001~012을 순서대로 적용한다. 기존 수동 관리 DB는 현재 적용 이력을 확인한 뒤 누락된 SQL만 적용한다.

## 정기 갱신 설정

기본값은 비활성화다. 서버 환경변수로 활성화한다.

```dotenv
FOOD_SAFETY_SCHEDULED_REFRESH_ENABLED=true
FOOD_SAFETY_REFRESH_CRON=0 0 3 * * *
FOOD_SAFETY_API_KEY=발급받은키
```

기본 주기는 매일 한국시간 오전 3시이다. 실행 중인 백엔드가 있어야 동작한다.
정기 갱신의 첫 실행은 전체 제품을 페이지 단위 수집하여 기준일을 만든다.
이후에는 DB의 `product_sync_state.covered_through`를 읽어 `CHNG_DT=YYYYMMDD`로 변경분만 요청한다.
수동 `/api/data/load-all`은 전체 수집 기능으로 유지하며 정기 갱신 기준일을 직접 변경하지 않는다.
기준일은 수집 시작 당시 한국 날짜의 전날까지로 기록한다. 당일 변경 자료는 19시 이후 제공된다는 API 안내를 반영했다.
날짜 경계·늦은 게시에 대비해 저장한 기준일보다 하루 앞에서 겹쳐 조회한다. 재수집한 제품이 기존 값과 같으면 저장·알림 생성을 생략한다.
API 오류, 저장 실패, 일부 페이지 실패, 필수값 누락, 다른 수집 진행 중이면 기준일은 갱신하지 않는다.
변경분 0건(`INFO-200` 포함)은 정상 완료로 처리한다. 마지막 기준일은 DB에 남으므로 서버 재시작 후에도 이어받는다.
조회 시작일이 API의 최근 7일 범위를 벗어나거나 기준일이 비정상적으로 미래이면 전체 수집으로 복구한다.
기존 데이터가 있어도 기준일을 처음 만드는 정기 실행은 전체 수집을 한 번 수행한다.
전체 수집 성공 날짜(last_full_sync_on)를 별도로 저장하고 7일이 지나면 전체 수집한다. 실패하면 날짜를 유지해 다음 실행에서 재시도한다. 마지막 전체 수집 날짜가 없거나 미래인 경우에도 전체 수집한다. 이 날짜는 서버 재시작으로 초기화되지 않는다.
010_weekly_full_sync.sql을 백엔드 시작 전에 적용해야 한다. 과거 전체 수집 날짜는 추정해서 채우지 않는다.
운영 전 API 호출 한도에 맞춰 주기를 조절한다.
동일 프로세스의 수동·정기 갱신은 동시에 실행되지 않는다. 여러 서버 운영 시 스케줄러는 한 인스턴스에서만 활성화한다.
데이터 제공기관의 수정 시각이 아니라 우리 서버의 감지 시각을 저장한다.

## 추가 API

모두 기존 Bearer JWT 인증을 사용한다. 프론트 변경은 포함하지 않는다.

### 알림 조회

`GET /api/notifications?page=0&size=20&unreadOnly=true`

- 본인 알림만 조회, 최신 알림부터 정렬. 페이지 크기 최대 50.
- 응답: `items`, `page`, `size`, `totalElements`, `totalPages`, `unreadCount`.
- 항목: `id`, `reportNo`, `previousSource`, `currentSource`, `detectedAt`, `readAt`, `message`.
- 권장 표시: “이전에 검수한 제품의 기능성 원문이 변경됐습니다. 광고를 확인하고 재검수해주세요.”

`POST /api/notifications/{id}/read`

- 본인 알림만 읽음 처리, 성공 204. 타인·없는 알림은 404.
- 여러 번 읽음 처리해도 최초 읽은 시각을 유지한다.

### 과거 검사 기준 변경 확인

`GET /api/inspections/{jobId}/product-changes`

- 본인 검사만 조회. 검사 당시 원문·현재 원문, 검사 이후 변경 감지 여부 제공.
- `sourceChanged`, `productAvailable`, `lastDetectedAt`, `inspectedSource`, `currentSource`, `message`.
- 제품이 없으면 변경 없음으로 확정하지 않고 `productAvailable=false`와 확인 불가 안내를 반환한다.
- 알림 기록을 읽음 처리하거나 과거 검수 결과를 변경하지 않는다.

### 수정 후 재검수

`POST /api/inspections/{jobId}/reinspections`

```json
{"adText":"수정한 광고 문구"}
```

- 본인의 이전 검사에서 제품 선택·제품 종류 답변을 복원한다. 제품 정보는 현재 DB에서 다시 조회한다.
- 빈 문구·기존과 같은 문구는 400, 타인·없는 검사는 404.
- 응답: `jobId`, `previousJobId`. 원본 검사는 그대로 두고 새 검사에 이전 검사 번호를 저장한다.
- 접수 즉시 작업 번호를 반환하고 DB 대기열의 백그라운드 작업자가 AI 판정을 실행한다. 상태 조회로 완료를 확인한다.
- 새 검사를 `GET /api/inspections/{jobId}`로 조회하면 `comparison`, `productSnapshot`,
  `productDataReviewRequired`, `previousJobId`를 함께 확인할 수 있다.
- `comparison`은 전후 판정 건수와 완료 여부, 기록된 제품·모델·사례 기준의 일치 여부를 제공한다.
  오래된 기록에 스냅샷이 없거나 기준이 달라지면 일치한다고 표시하지 않는다.
- 실패·시간 초과이면 전후 건수를 비교하지 않는다. 문구 삭제나 건수 감소를 특정 위반의 해결로 단정하지 않는다.
- AI 프롬프트 버전까지 완전한 판정 재현성을 보장하는 비교는 아니다.
- 문장별 수정 대응·판정 변화는 `REINSPECTION_SENTENCE_COMPARISON.md`에 설명된 기능으로 제공한다.
  수동 수정의 정확한 대응을 위해 선택적으로 `sentenceEdits`를 보낼 수 있다.

## 검증 범위

단위 테스트: 소유자 접근 제한, 변경 없음·신규 제품 무알림, 이전·이후 원문 보존,
대상 회원 한정, 읽음 처리, 재검수 연결, 입력 검증, 실패 결과 비교 방지.

실제 PostgreSQL 통합 테스트: 동일 고객의 복수 검수 중복 알림 방지, 반복 갱신 무알림,
변경 기록 실패 시 제품 갱신 롤백, 읽음 상태 저장, 이전 검사 연결 및 원본 보존.
실제 식약처·유료 AI API는 테스트에서 호출하지 않는다.

증분 갱신 테스트: 요청 경로·날짜, 최초 전체 수집, 서버 재시작 후 기준일 복원,
실패 시 기준일 보존, 0건 응답, 부분 페이지 실패, 오래된 기준일 전체 복구, 동일 제품 쓰기 생략.
API 근거: https://www.foodsafetykorea.go.kr/api/openApiInfo.do?svc_no=I0030

## 알림 → 현재 광고 재검수 → 필요한 문장만 수정 추천

1. `GET /api/notifications/{id}/ad-context`
   - 본인 알림과 해당 제품의 본인 최근 검사 문구만 불러온다. 조회만으로 AI 호출·읽음 처리를 하지 않는다.
   - `previousJobId`, `savedAdText`, `productName`, `reportNo`, `notifiedSource`, `currentSource` 제공.
   - `notificationSuperseded`는 알림 이후 원문이 다시 바뀌었는지 표시한다. 재검수는 알림 당시 값이 아니라 현재 DB 기준이다.
   - `canRecheck`, `productReviewRequired`, `currentAdConfirmationRequired`, `message`로 진행 가능 상태 전달.
2. 사용자가 저장된 문구를 현재 게재 중인 문구와 비교·수정한 뒤
   `POST /api/inspections/{previousJobId}/recheck` 호출:
   `{"adText":"현재 광고 문구", "confirmedCurrentAd":true}`.
   - 문구가 이전과 같아도 재검수할 수 있다. 확인 값이 없거나 false이면 400.
   - 기존 검사·현재 제품 정보로 새 검사를 만들고 이전 검사 번호를 연결한다.
   - 검사 중 제품 정보 버전이 바뀌면 409로 재요청을 안내한다.
3. `GET /api/inspections/{newJobId}/recommendations`
   - 완료된 본인 검사에서 VIOLATION·REVIEW이고 수정안이 있는 문장만 제공한다.
   - 항목: `id`(finding-번호), `originalText`, `suggestedText`, `start`, `end`, `validationStatus=UNVERIFIED`.
   - 이 조회에는 AI 추가 호출이 없다. 데이터가 달라졌거나 옛 검사에 버전이 없으면 현재 광고 재검수를 먼저 요구한다.
4. 사용자가 수정안을 선택하면 `POST /api/inspections/{newJobId}/recommendations/{findingId}/validate`.
   - 그 수정안으로 문장 전체를 교체한 광고를 다시 검사하고 별도 결과를 저장한다. 사용자 입력·원본 결과를 덮어쓰지 않는다.
   - 응답의 jobId로 결과를 조회하면 `recommendationValidation` 제공.
   - 교체된 문장 전체에 OK 판정이 확인된 경우만 `PASSED_THIS_CHECK`, 그 외 `REVIEW_REQUIRED`.
   - 다른 문장에 남은 위반은 전체 결과에 유지된다. 광고 전체가 안전하다고 표시하는 값이 아니다.
   - 원문 위치는 Unicode 코드포인트 기준. 수정안은 짧은 문제 구간이 아니라 해당 문장 전체를 교체한다.

추가 AI 호출은 사용자 재검수·수정안 검증 요청 시 발생한다. 정기 알림 생성 시 고객 광고를 자동으로 AI에 보내지 않는다.
프런트 화면·실시간 푸시·AI 프롬프트는 이번 변경에 포함하지 않는다.
