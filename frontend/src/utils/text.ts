import type { TextRange } from '@/types/inspection'

interface TextSegment {
  text: string
  range: TextRange
  rangeIndex: number | null
}

// 코드 포인트 기준 글자 수
export function countCodePoints(text: string): number {
  return Array.from(text).length
}

export function splitByRanges(text: string, ranges: readonly TextRange[]): TextSegment[] {
  const chars = Array.from(text)
  const ordered = ranges
    .map((range, index) => ({ range, index }))
    .sort((a, b) => a.range.start - b.range.start)

  const segments: TextSegment[] = []
  const push = (start: number, end: number, rangeIndex: number | null) => {
    if (end <= start) return
    segments.push({ text: chars.slice(start, end).join(''), range: { start, end }, rangeIndex })
  }

  let cursor = 0
  for (const { range, index } of ordered) {
    // 원문 길이를 넘거나 앞 범위와 겹치는 값은 잘라냄
    const start = Math.min(Math.max(range.start, cursor), chars.length)
    const end = Math.min(Math.max(range.end, start), chars.length)
    push(cursor, start, null)
    push(start, end, index)
    cursor = end
  }
  push(cursor, chars.length, null)
  return segments
}
