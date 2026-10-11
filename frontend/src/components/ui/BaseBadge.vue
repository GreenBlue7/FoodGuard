<script setup lang="ts">
// 색 의미: red 위반 / orange 검토 필요 / emerald 문제없음 / gray 보류·제외·일반 정보
const { tone = 'gray' } = defineProps<{
  tone?: 'gray' | 'red' | 'orange' | 'emerald'
}>()

const TONE_CLASSES = {
  gray: 'border-black/10 bg-black/[0.04] text-ink-700',
  red: 'border-red-200 bg-red-50 text-red-700',
  orange: 'border-orange-200 bg-orange-50 text-orange-700',
  emerald: 'border-emerald-200 bg-emerald-50 text-emerald-700',
}
</script>

<template>
  <span
    class="inline-flex shrink-0 items-center gap-1 rounded-full border px-2 py-0.5 text-xs font-medium"
    :class="TONE_CLASSES[tone]"
  >
    <slot />
  </span>
</template>
