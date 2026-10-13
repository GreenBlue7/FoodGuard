import type { IconName } from '@/components/ui/icon-names'
import type { Finding, Verdict } from '@/types/inspection'

// 숫자가 클수록 심각
// 문장 색 결정과 카드 정렬에 같이 사용
// HOLD는 위험 신호는 아니지만 사람이 확인해야 해서 OK와 REVIEW 사이
export const VERDICT_SEVERITY: Record<Verdict, number> = {
  OK: 0,
  HOLD: 1,
  REVIEW: 2,
  VIOLATION: 3,
}

export const VERDICT_LABELS: Record<Verdict, string> = {
  VIOLATION: '위반',
  REVIEW: '검토 필요',
  OK: '문제없음',
  HOLD: '판단 보류',
}

// 색만으로 구분하지 않도록 판정마다 붙이는 기호
export const VERDICT_ICONS: Record<Verdict, IconName> = {
  VIOLATION: 'x',
  REVIEW: 'alert',
  OK: 'check',
  HOLD: 'help',
}

// 문장마다 걸린 판정 중 가장 심각한 것
// 한 문장이 여러 판정(여러 문장에 걸친 판정 포함)에 들어있을 수 있어서 VIOLATION > REVIEW > HOLD > OK 순으로 하나를 선택
export function worstVerdictBySentence(findings: readonly Finding[]): Map<number, Verdict> {
  const worst = new Map<number, Verdict>()
  for (const finding of findings) {
    for (const index of finding.sentenceIndexes) {
      const current = worst.get(index)
      if (current === undefined || VERDICT_SEVERITY[finding.verdict] > VERDICT_SEVERITY[current]) {
        worst.set(index, finding.verdict)
      }
    }
  }
  return worst
}
