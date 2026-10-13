<script setup lang="ts">
import { useTemplateRef } from 'vue'
import BaseIcon from '@/components/ui/BaseIcon.vue'

const query = defineModel<string>({ required: true })
const input = useTemplateRef<HTMLInputElement>('input')

// v-model은 한글 IME 조합 중에는 값을 갱신하지 않는다.
// "홍삼"을 치는 동안 "홍"만 들어가 검색이 늦어지므로 input 이벤트에서 직접 값을 읽는다.
function onInput(event: Event) {
  query.value = (event.target as HTMLInputElement).value
}

// 마지막 chip을 지웠을 때 부모가 검색창으로 포커스를 돌려보내는 데 쓴다
defineExpose({
  focus: () => input.value?.focus(),
})
</script>

<template>
  <div>
    <label for="product-search" class="block px-1 text-sm text-ink-700">제품 검색</label>
    <div class="relative mt-2">
      <BaseIcon
        name="search"
        :size="16"
        class="pointer-events-none absolute top-1/2 left-4 -translate-y-1/2 text-ink-600"
      />
      <input
        id="product-search"
        ref="input"
        type="search"
        :value="query"
        placeholder="제품명 또는 신고번호"
        autocomplete="off"
        class="w-full rounded-full border border-black/10 bg-white/90 py-3 pr-4 pl-11 text-sm outline-hidden transition placeholder:text-ink-400 focus:border-primary focus:ring-4 focus:ring-accent/40"
        @input="onInput"
      />
    </div>
  </div>
</template>
