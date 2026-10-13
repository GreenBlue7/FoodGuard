<script setup lang="ts">
import { computed } from 'vue'
import BaseIcon from '@/components/ui/BaseIcon.vue'
import type { IconName } from '@/components/ui/icon-names'
import type { JobStatus } from '@/types/inspection'

// 검사 작업 상태 배지. 검사 중에는 포인트색(노랑)을 쓰고,
// 완료·실패는 판정과 같은 초록·빨강 계열로 둔다.
const props = defineProps<{ status: JobStatus }>()

// Tailwind가 클래스를 찾을 수 있도록 클래스 문자열은 동적으로 만들지 않고 전부 적는다
const META: Record<JobStatus, { label: string; wrap: string; icon: IconName }> = {
  PENDING: {
    label: '대기 중',
    wrap: 'border-black/10 bg-black/[0.04] text-ink-700',
    icon: 'clock',
  },
  RUNNING: { label: '검사 중', wrap: 'border-accent bg-accent/30 text-ink-900', icon: 'loader' },
  DONE: { label: '완료', wrap: 'border-emerald-200 bg-emerald-50 text-emerald-700', icon: 'check' },
  FAILED: { label: '실패', wrap: 'border-red-200 bg-red-50 text-red-700', icon: 'x' },
  TIMEOUT: { label: '시간 초과', wrap: 'border-red-200 bg-red-50 text-red-700', icon: 'alert' },
}

const meta = computed(() => META[props.status])
</script>

<template>
  <span
    class="inline-flex shrink-0 items-center gap-1 rounded-full border px-2 py-0.5 text-xs font-medium"
    :class="meta.wrap"
  >
    <BaseIcon
      :name="meta.icon"
      :size="12"
      :class="{ 'animate-spin motion-reduce:animate-none': status === 'RUNNING' }"
    />
    {{ meta.label }}
  </span>
</template>
