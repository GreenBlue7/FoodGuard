<script setup lang="ts">
import BaseIcon from '@/components/ui/BaseIcon.vue'

defineProps<{
  dataAsOf: string | null // 판단 기준일 YYYY-MM-DD. 사례 DB가 비어 있으면 null
  model: string
}>()
</script>

<template>
  <footer class="flex items-start gap-3 rounded-2xl bg-white/50 px-5 py-4 text-sm text-ink-700">
    <BaseIcon name="info" :size="16" class="mt-0.5 shrink-0" />
    <div class="space-y-1">
      <p>
        <!-- 기준일이 없으면 날짜 문장만 빼고, 사전 점검 안내는 항상 보여 줌 -->
        <template v-if="dataAsOf">
          <span class="tabular-nums">{{ dataAsOf }}</span> 기준 자료로 판단했습니다.
        </template>
        이 결과는 사전 점검용이며 최종 법적 판단이 아닙니다.
      </p>
      <p class="text-xs text-ink-600">판정 모델: {{ model }}</p>
    </div>
  </footer>
</template>
