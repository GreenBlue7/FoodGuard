<script setup lang="ts">
import { computed } from 'vue'
import BaseIcon from '@/components/ui/BaseIcon.vue'

// 검사 화면 위쪽의 진행 단계 표시
// 상태를 새로 만들지 않고 부모가 이미 아는 값만 받아 그림
const props = defineProps<{
  productDone: boolean // 제품을 골랐거나 제품 종류를 답함
  adTextDone: boolean // 광고 문구를 입력함
}>()

type StepState = 'done' | 'current' | 'todo'

const STATE_CLASSES: Record<StepState, string> = {
  done: 'bg-primary text-white',
  current: 'bg-accent text-ink-900',
  todo: 'bg-hatch border border-black/10 text-ink-600',
}

const steps = computed(() => {
  const done = [props.productDone, props.adTextDone, false]
  const current = done.indexOf(false)
  return ['제품 선택', '광고 문구', '검사'].map((label, i) => {
    const state: StepState = done[i] ? 'done' : i === current ? 'current' : 'todo'
    // 마지막 단계가 "지금 할 단계"가 되면 검사 버튼을 누를 수 있는 상태
    const text =
      state === 'done' ? '완료' : state === 'todo' ? '대기' : i === 2 ? '준비됨' : '입력 중'
    return { label, state, text }
  })
})
</script>

<template>
  <ol aria-label="진행 단계" class="flex gap-2">
    <li v-for="step in steps" :key="step.label" class="flex min-w-0 flex-1 flex-col gap-2">
      <span class="px-1 font-heading text-sm font-medium text-ink-700">{{ step.label }}</span>
      <span
        class="flex h-12 items-center gap-2 rounded-full px-5 text-sm"
        :class="STATE_CLASSES[step.state]"
      >
        <BaseIcon v-if="step.state === 'done'" name="check" :size="14" />
        {{ step.text }}
      </span>
    </li>
  </ol>
</template>
