# 재검수 문장별 비교

## 동작

기존 검사와 새 검사 연결·판정 건수 비교를 유지하고, 새로 저장하는 재검수 결과의
`comparison`에 `sentenceChanges`와 `sentenceSummary`를 추가한다.
이 비교 자체는 AI를 호출하지 않으며, 원본 검사 결과를 변경하지 않는다.

두 검사가 완료되고 원문·문장 위치를 검증할 수 있어야 항목 비교를 제공한다.
없거나 깨진 옛 문장 정보, 원문 누락, 문장 데이터가 너무 큰 경우에는
`sentenceComparisonAvailable=false`, `sentenceSummary=null`로 반환한다.
이를 문제 0건으로 해석해서는 안 된다.

## 수동 수정 정보를 선택적으로 전달

기존 요청은 그대로 동작한다. 정확한 문장 대응이 필요한 프런트는 다음 필드를 선택적으로 추가한다.

`POST /api/inspections/{previousJobId}/reinspections`

```json
{
  "adText": "수정 문구.",
  "sentenceEdits": [
    {"previousSentenceId": "s1", "currentStart": 0, "currentEnd": 6}
  ]
}
```

- `previousSentenceId`는 이전 `judgeResponse.sentences[].id`.
- 현재 위치는 앞뒤를 trim한 새 `adText`의 Unicode 코드포인트 기준이며 끝 위치는 제외한다.
- 현재 문장 전체의 위치를 보낸다. 이모지는 UTF-16 글자 수가 아니라 코드포인트 한 개로 센다.
- 없는 이전 문장, 중복 이전 ID, 범위 초과·겹침·공백만인 위치는 AI 호출 전에 400으로 거절한다.
- 현재 AI 응답의 실제 문장 경계와 일치하지 않으면 확정 대응으로 쓰지 않고 `editHintsNotMatched`로 개수를 알린다.
- `/recheck`도 같은 선택 필드를 받는다. 기존 `confirmedCurrentAd=true`는 계속 필요하다.
- 사용자가 선택한 수정안 검증 경로는 실제 적용한 원문과 추천 문장을 대조해 대응을 자동으로 확인한다.

## 응답

기존 `previousCounts`, `currentCounts`, `bothCompleted`, `sameRecordedCriteria`는 유지한다.

`sentenceChanges[]`:

| 필드 | 의미 |
|---|---|
| `changeType` | `UNCHANGED`, `MODIFIED`, `ADDED`, `REMOVED` |
| `before`, `after` | 이전·현재 문장. 없는 쪽은 null |
| 문장 객체 | `index`, `id`, `text`, `range`, `verdict`, `violationTypes`, `reasons` |
| `mappingMethod` | `USER_EDIT_RANGE`, `SELECTED_RECOMMENDATION`, `EXACT_TEXT`, `DUPLICATE_OCCURRENCE`, `SIMILAR_TEXT`, `UNMATCHED` |
| `mappingCertain` | 문장 대응을 확인했는지 여부 |
| `issueChange` | 아래 항목 상태 |

| `issueChange` | 화면에서 설명할 의미 |
|---|---|
| `RESOLVED_THIS_CHECK` | 대응이 확인된 수정 문장이 이번 검사에서 OK가 됨 |
| `REMAINING_ISSUE` | 대응된 항목에 위반·검토 필요·보류 판정이 남음 |
| `NEW_ISSUE` | 기존 OK 문장의 수정 또는 추가 문장에서 위험 판정 발생 |
| `UNCHANGED_VERDICT_CHANGED` | 문구는 그대로지만 판정·위반 유형이 달라짐 |
| `NO_ISSUE` | 해당 문장에 이번 검사상 문제없음 |
| `REMOVED_NOT_ASSESSED` | 이전 문장이 삭제됨. 해결로 집계하지 않음 |
| `MAPPING_REQUIRES_CONFIRMATION` | 유사 문장·중복 문장 등으로 대응 확인 필요 |
| `RESULT_UNAVAILABLE` | 해당 문장의 판정 정보 누락·확인 불가 |
| `CRITERIA_CHANGED_OR_UNKNOWN` | 제품·모델·사례 기준이 달라졌거나 확인 불가 |

`sentenceSummary`는 문장 기준 집계다. 기존 판정 건수는 결과 객체 기준이어서 여러 문장이 묶인 판정에서 수가 다를 수 있다.
필드: `unchanged`, `modified`, `added`, `removed`, `resolvedThisCheck`, `remainingIssues`, `newIssues`,
`unchangedVerdictChanges`, `uncertainMappings`, `notComparable`.

## 보수적인 비교 원칙

- 정확한 문구로 순서가 바뀐 문장도 찾는다. 같은 문구가 반복되면 단순 발생 순서를 확정 대응으로 취급하지 않는다.
- 수정 위치 정보 없이도 비슷한 문장을 표시할 수 있지만, 유사도만으로 해결을 확정하지 않는다.
- 문장 분할·합침 등 명확히 연결하지 못한 경우 추가·삭제 또는 확인 필요로 남는다.
- 문구가 그대로인데 VIOLATION→OK가 된 경우 수정으로 해결됐다고 집계하지 않는다.
- 같은 기준 여부는 저장된 제품 스냅샷·모델명·사례 기준일을 비교한 값이다.
  프롬프트·법령 규칙의 모든 버전까지 완전히 동일함을 보장하지 않는다.
- 판정 일관성은 이번 변경에서 탐지·표시한다. 고치지 않은 문장의 판정을 재사용하거나 고정하지 않는다.
- 문장별 비교 결과는 새 검수 기록에 저장한다. 기존 저장 결과를 일괄 재작성하지 않는다.
- 추가 DB 변경이나 이미지/OCR 구현은 없다.
