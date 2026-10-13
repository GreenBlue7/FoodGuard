<script setup lang="ts">
import { computed } from 'vue'
import BaseIcon from '@/components/ui/BaseIcon.vue'
import type { IconName } from '@/components/ui/icon-names'
import type { Finding, Sentence, Verdict } from '@/types/inspection'
import { splitByRanges } from '@/utils/text'
import { VERDICT_ICONS, VERDICT_LABELS, worstVerdictBySentence } from '@/utils/verdict'

const {
  text,
  sentences,
  findings,
  emphasized = [],
} = defineProps<{
  text: string // 검사한 원문 전체
  sentences: Sentence[]
  findings: Finding[]
  emphasized?: readonly number[] // 테두리로 잠깐 강조할 문장 index (판정 카드를 누른 경우)
}>()

// 문장 하나의 표시 방식. MANDATORY는 판정과 상관없이 회색으로 둔다.
type Tone = Verdict | 'MANDATORY'

const TONES: Record<Tone, { label: string; icon: IconName; iconClass: string; markClass: string }> =
  {
    VIOLATION: {
      label: VERDICT_LABELS.VIOLATION,
      icon: VERDICT_ICONS.VIOLATION,
      iconClass: 'text-red-700',
      markClass: 'bg-red-100 text-red-950 decoration-red-500',
    },
    REVIEW: {
      label: VERDICT_LABELS.REVIEW,
      icon: VERDICT_ICONS.REVIEW,
      iconClass: 'text-orange-600',
      markClass: 'bg-orange-100 text-orange-950 decoration-orange-500',
    },
    OK: {
      label: VERDICT_LABELS.OK,
      icon: VERDICT_ICONS.OK,
      iconClass: 'text-emerald-700',
      markClass: 'bg-emerald-50 text-emerald-950 decoration-emerald-500',
    },
    // 판단 보류는 의무 문구(회색 실선)와 구분되도록 회색 점선
    HOLD: {
      label: VERDICT_LABELS.HOLD,
      icon: VERDICT_ICONS.HOLD,
      iconClass: 'text-stone-500',
      markClass: 'bg-stone-100 text-stone-800 decoration-stone-400 decoration-dashed',
    },
    MANDATORY: {
      label: '의무 문구(판정 제외)',
      icon: 'minus',
      iconClass: 'text-slate-500',
      markClass: 'bg-slate-100 text-slate-600 decoration-slate-400',
    },
  }

// 범례에 판단 보류는 실제로 있을 때만 표시
const legend = computed<Tone[]>(() =>
  findings.some((finding) => finding.verdict === 'HOLD')
    ? ['VIOLATION', 'REVIEW', 'OK', 'HOLD', 'MANDATORY']
    : ['VIOLATION', 'REVIEW', 'OK', 'MANDATORY'],
)

// 원문을 문장 위치(코드 포인트)대로 잘라, 문장 조각에는 표시 방식을 붙인다.
// 문장 사이의 공백·줄바꿈은 tone이 null인 조각으로 그대로 남는다.
const segments = computed(() => {
  const worst = worstVerdictBySentence(findings)
  return splitByRanges(
    text,
    sentences.map((sentence) => sentence.range),
  ).map((segment) => {
    const sentence = segment.rangeIndex === null ? undefined : sentences[segment.rangeIndex]
    let tone: Tone | null = null
    if (sentence?.isMandatory) tone = 'MANDATORY'
    else if (sentence) tone = worst.get(sentence.index) ?? null
    return {
      text: segment.text,
      sentenceIndex: sentence?.index ?? null,
      tone,
      emphasized: sentence !== undefined && emphasized.includes(sentence.index),
    }
  })
})
</script>

<template>
  <div class="space-y-4">
    <ul class="flex flex-wrap gap-x-3 gap-y-1 text-xs text-ink-700" aria-label="표시 기준">
      <li v-for="tone in legend" :key="tone" class="flex items-center gap-1">
        <BaseIcon :name="TONES[tone].icon" :size="14" :class="TONES[tone].iconClass" />
        <span class="rounded-sm px-1" :class="TONES[tone].markClass">{{ TONES[tone].label }}</span>
      </li>
    </ul>

    <!-- 원문의 공백·줄바꿈을 그대로 보여 준다. 기호는 하이라이트 밖에 두어 색칠 범위가 문장과 정확히 같게 한다. -->
    <p class="text-sm leading-8 break-words whitespace-pre-wrap text-ink-900">
      <template v-for="(segment, i) in segments" :key="i">
        <template v-if="segment.tone === null">{{ segment.text }}</template>
        <template v-else>
          <BaseIcon
            :name="TONES[segment.tone].icon"
            :size="14"
            class="mr-0.5 inline-block align-[-2px]"
            :class="TONES[segment.tone].iconClass"
          />
          <mark
            :data-sentence-index="segment.sentenceIndex"
            :title="segment.tone === 'MANDATORY' ? TONES.MANDATORY.label : undefined"
            class="rounded-sm underline decoration-2 underline-offset-4 box-decoration-clone"
            :class="[
              TONES[segment.tone].markClass,
              { 'outline-2 outline-offset-2 outline-primary': segment.emphasized },
            ]"
            ><span class="sr-only">({{ TONES[segment.tone].label }}) </span>{{ segment.text }}</mark
          >
        </template>
      </template>
    </p>
  </div>
</template>
