<script setup lang="ts">
import { ref, useId } from 'vue'
import BaseBadge from '@/components/ui/BaseBadge.vue'
import BaseIcon from '@/components/ui/BaseIcon.vue'
import type { Finding, Sentence, Verdict } from '@/types/inspection'
import { VERDICT_ICONS, VERDICT_LABELS } from '@/utils/verdict'

defineProps<{
  finding: Finding
  sentences: Sentence[] // 이 판정이 가리키는 문장들 (sentenceIndexes 순서)
}>()

const emit = defineEmits<{
  select: [] // 원문에서 이 판정의 문장을 강조해 달라는 요청
}>()

const BADGE_TONES: Record<Verdict, 'red' | 'orange' | 'emerald' | 'gray'> = {
  VIOLATION: 'red',
  REVIEW: 'orange',
  OK: 'emerald',
  HOLD: 'gray',
}

const lawsOpen = ref(false)
const casesOpen = ref(false)
const id = useId()

// 마우스로는 카드 어디를 눌러도 원문을 강조한다. 카드 안의 버튼을 누른 경우는 그 버튼의 동작만 한다.
// 키보드·화면 낭독기 사용자는 "원문에서 보기" 버튼으로 같은 동작을 한다.
function onCardClick(event: MouseEvent) {
  if (event.target instanceof Element && event.target.closest('button')) return
  emit('select')
}
</script>

<template>
  <article
    class="card flex cursor-pointer flex-col gap-3 p-5 transition-colors duration-200 hover:bg-white"
    @click="onCardClick"
  >
    <div class="flex items-start justify-between gap-3">
      <div class="flex flex-wrap gap-2 pt-1.5">
        <BaseBadge :tone="BADGE_TONES[finding.verdict]">
          <BaseIcon :name="VERDICT_ICONS[finding.verdict]" :size="12" />
          {{ VERDICT_LABELS[finding.verdict] }}
        </BaseBadge>
        <BaseBadge v-if="finding.isTestimonial">후기 형식</BaseBadge>
        <BaseBadge v-if="finding.sentenceIndexes.length > 1">여러 문장에 걸친 판정</BaseBadge>
      </div>
      <!-- 흰 원형 버튼. 이름은 sr-only 글자로, 마우스 사용자에게는 title로 알려 준다 -->
      <button
        type="button"
        title="원문에서 보기"
        class="flex size-9 shrink-0 items-center justify-center rounded-full bg-white text-ink-700 shadow-sm ring-1 ring-black/5 transition-colors duration-200 hover:bg-primary hover:text-white"
        @click="emit('select')"
      >
        <BaseIcon name="eye" :size="16" />
        <span class="sr-only">원문에서 보기</span>
      </button>
    </div>

    <ul class="space-y-1">
      <li v-for="sentence in sentences" :key="sentence.index" class="break-words text-ink-900">
        “{{ sentence.text }}”
      </li>
    </ul>

    <p class="text-sm leading-relaxed text-ink-700">{{ finding.reason }}</p>

    <div v-if="finding.lawRefs.length > 0 || finding.cases.length > 0" class="flex flex-wrap gap-2">
      <button
        v-if="finding.lawRefs.length > 0"
        type="button"
        class="inline-flex items-center gap-1.5 rounded-full border border-black/10 bg-white px-3.5 py-1.5 text-xs text-ink-700 transition-colors duration-200 hover:border-black/20"
        :aria-expanded="lawsOpen"
        :aria-controls="`${id}-laws`"
        @click="lawsOpen = !lawsOpen"
      >
        {{ lawsOpen ? '근거 닫기' : '근거 보기' }} ({{ finding.lawRefs.length }})
        <BaseIcon
          name="chevron-down"
          :size="14"
          class="transition-transform duration-200"
          :class="{ 'rotate-180': lawsOpen }"
        />
      </button>
      <button
        v-if="finding.cases.length > 0"
        type="button"
        class="inline-flex items-center gap-1.5 rounded-full border border-black/10 bg-white px-3.5 py-1.5 text-xs text-ink-700 transition-colors duration-200 hover:border-black/20"
        :aria-expanded="casesOpen"
        :aria-controls="`${id}-cases`"
        @click="casesOpen = !casesOpen"
      >
        {{ casesOpen ? '참고 사례 닫기' : '참고 사례 보기' }} ({{ finding.cases.length }})
        <BaseIcon
          name="chevron-down"
          :size="14"
          class="transition-transform duration-200"
          :class="{ 'rotate-180': casesOpen }"
        />
      </button>
    </div>

    <!-- aria-controls가 가리키는 요소가 항상 있도록 v-if 대신 v-show로 접는다 -->
    <ul
      v-show="lawsOpen"
      :id="`${id}-laws`"
      aria-label="근거 조항"
      class="space-y-3 rounded-2xl bg-black/[0.03] px-4 py-3 text-sm"
    >
      <li v-for="law in finding.lawRefs" :key="law.id">
        <p class="text-ink-900">{{ law.title }} {{ law.article }}</p>
        <p class="mt-0.5 text-ink-700">{{ law.text }}</p>
      </li>
    </ul>
    <ul
      v-show="casesOpen"
      :id="`${id}-cases`"
      aria-label="참고 적발 사례"
      class="space-y-3 rounded-2xl bg-black/[0.03] px-4 py-3 text-sm"
    >
      <li v-for="item in finding.cases" :key="item.id">
        <p class="break-words text-ink-900">“{{ item.excerpt }}”</p>
        <p class="mt-0.5 text-xs text-ink-600 tabular-nums">
          {{ item.year }}년 · {{ item.source }}
        </p>
      </li>
    </ul>
  </article>
</template>
