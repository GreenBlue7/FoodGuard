# 1차 입력 검증 (2026-11-17)

`layer1`은 **검사 접수 가능 여부**만 확인한다. 광고 내용의 적법성·필수 고지 문구는 judge에 맡기며, 입력 검증 통과는 광고 판정 통과를 뜻하지 않는다. 전체 광고/일부 광고 선택은 추가하지 않는다.

## 입력 제한

- 원본 광고 문구를 Unicode 코드포인트 기준으로 최대 **3,000자** 받는다. 공백·줄바꿈도 포함하며 일반 이모지 한 개는 한 코드포인트다. 결합 이모지는 여러 코드포인트일 수 있다.
- 빈 문자열·공백만 있는 문구는 거절한다. 초과분을 자동으로 자르지 않는다.
- 길이 확인 후 기존 동작대로 양 끝 공백을 정리해 저장하고 judge로 전달한다. 내부 공백·줄바꿈은 유지한다.
- 제품 선택은 최대 한 개이며 제품 유형 답변은 지원하는 값이어야 한다.
- 기능성 원문 변경 후 최신 기능성 목록 추출에 실패한 제품은 이전 목록으로 판정하지 않고 409로 보류한다.
- 미등록·미확인 제품은 기존 UNKNOWN 경로로 검사할 수 있다. 확인되지 않은 정보를 확정하지 않고 안내를 남긴다.

일반 접수·수정 후 재검수·알림에서 현재 광고 재검수·추천 수정안 검증에 공통 적용한다. 추천 수정안은 교체한 광고 전체의 길이를 검사한다. 변경 전 이미 대기 중이던 3,000자 초과 요청도 worker에서 유료 AI 호출 전에 FAILED(AD_TEXT_TOO_LONG)로 종료한다.

## 응답

정상 접수 응답은 기존 HTTP 200 `{ "jobId": "..." }`를 유지한다. 작업 조회의 `layer1`에 아래 형식을 저장한다. 판정 완료 전에도 조회할 수 있다.

```json
{
  "kind": "INPUT_VALIDATION",
  "status": "PASSED",
  "checkedAt": "2026-11-17T23:00:00+09:00",
  "characterCount": 21,
  "maxCharacters": 3000,
  "checks": [
    { "code": "AD_TEXT_PRESENT", "label": "광고 문구 입력", "passed": true },
    { "code": "AD_TEXT_LENGTH", "label": "3,000자 이내", "passed": true },
    { "code": "REQUEST_FORMAT", "label": "제품 선택·유형 입력 형식", "passed": true },
    { "code": "PRODUCT_DATA", "label": "접수 시 제품 데이터 사용 조건", "passed": true }
  ],
  "notices": []
}
```

입력 오류는 `{code, message, layer1}`로 응답한다. `layer1.status`는 REJECTED이며 해당 오류의 check 하나를 포함한다. 파싱할 수 없는 요청은 characterCount가 null이다. 접수 전 거절된 입력은 새 job·대기열·접수 한도를 만들지 않으며 judge를 호출하지 않는다.

| HTTP | code | 의미 |
| --- | --- | --- |
| 400 | INVALID_REQUEST_BODY | JSON/요청 본문 형식 오류 |
| 400 | EMPTY_AD_TEXT | 광고 문구가 없거나 공백뿐임 |
| 400 | AD_TEXT_TOO_LONG | 원본 광고가 3,000자 초과 |
| 400 | INVALID_PRODUCT_SELECTION | 여러 제품 또는 빈 제품 ID |
| 400 | INVALID_PRODUCT_TYPE | 지원하지 않는 제품 유형 |
| 409 | PRODUCT_DATA_NOT_READY | 최신 기능성 정보 준비 실패 |
| 429 | INSPECTION_ACTIVE_LIMIT / INSPECTION_RATE_LIMIT | 기존 접수 제한. Retry-After와 retryAfterSeconds 유지, 거절 layer1 추가 |

인증·소유권·재검수 대상 상태·문장 연결 등 각 API의 기존 오류도 유지한다. 위 표는 이 API들의 모든 오류를 대체하는 목록이 아니다.

## 호환성과 화면

새 결과는 `kind`로 입력 검증을 구분해 ‘1차 입력 검증’ 카드에 표시한다. 입력창은 코드포인트 수/3,000자와 초과 안내를 표시하고 검사 버튼을 막는다. 서버도 같은 제한을 검증한다. 기존 기록의 layer1은 다시 쓰지 않고 이전 화면 분기를 유지한다.

임시 `rule.json`, RuleCheckService와 이를 사용하던 미사용 InspectionService를 제거했다. judge 내부 판정 규칙은 변경하지 않았다. 새 접수는 provisionalStep1을 저장하지 않는다. 중복 식별값 schema 2는 이 메타데이터를 제외하고 실제 judge 입력을 비교하며, 활성 작업만 다음 접수 시 재계산해 기존 jobId를 재사용한다. 완료 기록은 수정하지 않는다.

## 검증

분리된 PostgreSQL에서 backend clean test/bootJar: 191개 통과. 3,000/3,001자, 이모지·공백·줄바꿈, 재검수·추천 수정안 길이, 미확인 제품, 제품 추출 실패, 대기 작업, 기존 활성 중복 재사용을 검증했다. 프런트 lint·타입 검사·production build도 통과했다. 별도 임시 DB와 가짜 judge로 화면에서 초과 차단 및 접수→완료·입력 검증 카드 연결을 확인했다. 실제 Gemini 판정 품질·비용 검증은 포함하지 않는다.
