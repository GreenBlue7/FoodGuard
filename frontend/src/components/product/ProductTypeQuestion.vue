<script setup lang="ts">
import BaseIcon from '@/components/ui/BaseIcon.vue'
import type { ProductTypeAnswer } from '@/types/inspection'

// 확정된 2a 답. 건강기능식품은 "번호 없이 진행"을 눌러야 확정되고, 그 전에는 null이다.
const answer = defineModel<ProductTypeAnswer | null>({ required: true })
// 라디오에서 고른 값. 건강기능식품은 확정 전에도 고를 수 있어서 answer와 따로 둔다.
// 부모가 "건강기능식품만 고른 상태"를 알아야 검사 버튼 안내 문구를 고를 수 있어 밖에 둔다.
const choice = defineModel<ProductTypeAnswer | null>('choice', { required: true })

const OPTIONS: { value: ProductTypeAnswer; label: string }[] = [
  { value: 'HEALTH_FUNCTIONAL', label: '건강기능식품' },
  { value: 'FUNCTIONAL_GENERAL', label: '기능성 표시 일반식품' },
  { value: 'GENERAL', label: '일반식품' },
  { value: 'UNKNOWN', label: '모름' },
]

function onChoose(value: ProductTypeAnswer) {
  choice.value = value
  answer.value = value === 'HEALTH_FUNCTIONAL' ? null : value
}
</script>

<template>
  <fieldset class="rounded-2xl bg-black/[0.03] p-4">
    <!-- float: legend를 테두리 위가 아닌 상자 안쪽 첫 줄에 두기 위한 처리 -->
    <legend class="float-left mb-3 w-full px-1 text-sm text-ink-700">제품 종류를 알려주세요</legend>
    <!-- 라디오는 화면에서만 숨기고(sr-only) 알약 모양 label이 선택 상태를 보여 준다.
         키보드 화살표 이동과 화면 낭독기는 원래 라디오 그대로 동작한다 -->
    <div class="clear-both flex flex-wrap gap-2">
      <label
        v-for="option in OPTIONS"
        :key="option.value"
        class="cursor-pointer rounded-full border border-black/10 bg-white px-4 py-2 text-sm text-ink-700 transition-colors duration-200 hover:border-black/20 has-checked:border-primary has-checked:bg-primary has-checked:text-white has-[:focus-visible]:ring-4 has-[:focus-visible]:ring-accent/60"
      >
        <input
          type="radio"
          name="product-type-answer"
          class="sr-only"
          :value="option.value"
          :checked="choice === option.value"
          @change="onChoose(option.value)"
        />
        {{ option.label }}
      </label>
    </div>

    <div
      v-if="choice === 'HEALTH_FUNCTIONAL'"
      class="mt-4 flex flex-col items-start gap-3 rounded-2xl bg-accent/25 p-4 text-sm"
    >
      <p class="text-ink-900">포장의 신고번호를 입력해 다시 검색해 주세요.</p>
      <p v-if="answer === 'HEALTH_FUNCTIONAL'" class="flex items-center gap-1.5 text-ink-900">
        <BaseIcon name="check" :size="14" />
        신고번호 없이 진행합니다.
      </p>
      <button
        v-else
        type="button"
        class="rounded-full bg-primary px-4 py-2 text-sm text-white transition-colors duration-200 hover:bg-black"
        @click="answer = 'HEALTH_FUNCTIONAL'"
      >
        번호 없이 진행
      </button>
    </div>
  </fieldset>
</template>
