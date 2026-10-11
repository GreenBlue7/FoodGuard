<script setup lang="ts">
import { computed } from 'vue'
import { countCodePoints } from '@/utils/text'

const text = defineModel<string>({ required: true })

const length = computed(() => countCodePoints(text.value))

// 검색창과 같은 이유로 v-model 대신 input 이벤트에서 값을 읽음
// 한글 조합 중에도 글자 수와 검사 버튼 상태가 바로 바뀌게 하기 위해서
function onInput(event: Event) {
  text.value = (event.target as HTMLTextAreaElement).value
}
</script>

<template>
  <div>
    <label for="ad-text" class="block px-1 text-sm text-ink-700">상세페이지에 올릴 광고 문구</label>
    <textarea
      id="ad-text"
      :value="text"
      rows="12"
      aria-describedby="ad-text-length"
      class="mt-2 block w-full resize-y rounded-2xl border border-black/10 bg-white/90 px-4 py-3 text-sm leading-relaxed outline-hidden transition focus:border-primary focus:ring-4 focus:ring-accent/40"
      @input="onInput"
    ></textarea>
    <p id="ad-text-length" class="mt-2 px-1 text-right text-xs text-ink-600 tabular-nums">
      {{ length }}자
    </p>
  </div>
</template>
