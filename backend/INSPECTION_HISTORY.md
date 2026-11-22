# 검수 이력 조회 보완

## 사용자 흐름

이력 목록에서 제품명·검사 시각·위반/검토 건수를 확인한다.
항목의 jobId로 `GET /api/inspections/{jobId}`를 호출하면 저장된 당시 결과를 연다.
AI 판정이나 식약처 정보 조회를 다시 하지 않는다.

## 목록 API

`GET /api/inspections?page=0&size=20`

로그인 토큰 필요. 본인 기록만 조회하며 검사 시각 내림차순, 같은 시각이면 ID 내림차순.
기존 page/size/totalElements/totalPages와 각 항목의 기존 필드는 유지한다.
page는 최소 0, size는 1~50으로 기존처럼 보정한다.

항목에 추가된 필드:

| 필드 | 타입 | 의미 |
|---|---|---|
| countsAvailable | boolean | 저장 결과에서 건수를 확인할 수 있는지 |
| violationCount | integer 또는 null | 위반 지적 건수 |
| reviewCount | integer 또는 null | 검토 필요 지적 건수 (HOLD 포함) |
| dataAsOf | YYYY-MM-DD 문자열 또는 null | 당시 저장된 사례/판정 자료의 기준일 |
| previousJobId | 문자열 또는 null | 수정·재확인 이전 검사 ID |

예시 (설명용 값이며 실제 AI 실행 결과가 아님):

```json
{
  "items": [
    {
      "jobId": "42",
      "status": "DONE",
      "productName": "제품명",
      "adTextPreview": "광고 문구",
      "createdAt": "2026-11-14T13:00:00",
      "countsAvailable": true,
      "violationCount": 1,
      "reviewCount": 2,
      "dataAsOf": "2026-10-01",
      "previousJobId": "41"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1
}
```

## 건수 해석

- 목록은 상세 결과 화면의 `layer2.findings`를 기준으로 지적 항목 수를 센다.
  두 문장에 걸친 지적 하나는 1건이다. 문장 수나 고유 법조문 수가 아니다.
- 표시용 결과가 없는 옛 기록은 `judgeResponse.results`로 대체 집계한다.
- HOLD는 현재 결과 화면과 동일하게 검토 필요로 포함한다.
- 실패/시간 초과/진행 중, JSON 손상, 판정 배열 누락, 알 수 없는 판정은
  `countsAvailable=false`, 두 count는 null이다. 화면은 `집계 불가` 또는 `—`로 표시한다.
- 완료된 결과의 명시적인 빈 판정 배열은 집계 가능, 0건이다.
  0건은 법적 적합 인증을 의미하지 않는다.
- 기준일은 저장된 layer2.dataAsOf 또는 옛 judgeResponse.kbVersion의 날짜에서만 읽는다.
  날짜를 알 수 없으면 null이다. 오늘 날짜나 현재 DB의 기준일로 채우지 않는다.
- 기존 기록을 덮어쓰거나 재검수하지 않는다. 추가 DB 마이그레이션은 없다.

## 프런트 연결

1. 이력 메뉴에서 목록 API를 호출한다.
2. 제품이 없는 기록의 productName은 null이므로 `제품 미선택` 등으로 표시한다.
3. countsAvailable을 먼저 확인한 뒤 위반·검토 건수를 보여 준다.
4. 기준일 null은 `기준일 정보 없음`으로 표시한다.
5. 항목을 누르면 기존 결과 화면 `/inspections/{jobId}`를 연다.
6. previousJobId가 있으면 이전 결과로 이동할 수 있다. 이전 결과 API도 본인 소유 여부를 검사한다.
7. 시간 표시에는 기존 createdAt의 시간대 규약을 유지한다. 이번 변경에서 날짜 저장 형식을 바꾸지 않았다.

프런트 코드는 변경하지 않았다. 실제 화면에 이력 메뉴가 자동으로 생기지는 않는다.

## 코드 흐름과 검증

InspectionJobController.history → InspectionHistoryService.getMyHistory →
본인 소유 요약 projection → InspectionHistoryResponse.from → InspectionHistoryMetadata.read.

요청한 한 페이지(최대 50건)의 result_json을 서버에서 읽어 집계하고 원문 JSON은 목록에 노출하지 않는다.
현재 규모에서는 스키마·과거 기록 변경 없이 동작하도록 했다. 결과 JSON이 커지거나 목록 요청이 많아지면
별도 요약 컬럼을 저장하는 방식으로 최적화할 수 있다.

테스트: 지적 건수와 문장 수 구분, HOLD 처리, 옛 결과 대체 집계, 손상된 기록,
기준일 누락, 다른 회원 접근 차단, 안정적인 페이지 정렬, 페이지 크기 제한, 이모지 미리보기.
