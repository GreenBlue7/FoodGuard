<script setup lang="ts">
import BaseIcon from '@/components/ui/BaseIcon.vue'

defineProps<{
  dataAsOf: string // 판단 기준일 YYYY-MM-DD
  model: string
}>()
</script>

<template>
  <footer class="flex items-start gap-3 rounded-2xl bg-white/50 px-5 py-4 text-sm text-ink-700">
    <BaseIcon name="info" :size="16" class="mt-0.5 shrink-0" />
    <div class="space-y-1">
      <p>
        <span class="tabular-nums">{{ dataAsOf }}</span> 기준 자료로 판단했습니다. 이 결과는 사전
        점검용이며 최종 법적 판단이 아닙니다.
      </p>
      <p class="text-xs text-ink-600">판정 모델: {{ model }}</p>
    </div>
  </footer>
</template>
