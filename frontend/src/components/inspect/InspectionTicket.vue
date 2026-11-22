<script setup lang="ts">
import { computed } from 'vue'
import { useInspectionJob } from '@/composables/useInspectionJob'
import type { JobStatus } from '@/types/inspection'

const props = defineProps<{ jobId: string; index: number }>()
const { job, loadState, timedOut, recheck } = useInspectionJob(() => props.jobId)
const labels: Record<JobStatus, string> = {
  PENDING: '대기 중',
  RUNNING: '검사 중',
  DONE: '완료',
  FAILED: '실패',
  TIMEOUT: '처리 시간 초과',
}
const productName = computed(
  () => job.value?.productSnapshot?.judgeProduct?.names?.join(', ') || '제품 정보 미등록',
)
</script>

<template>
  <li class="rounded-2xl border border-black/10 bg-white/90 p-6">
    <div class="flex flex-wrap items-center justify-between gap-3">
      <h2 class="text-lg text-ink-900">{{ index + 1 }}번 항목 · 번호표 #{{ jobId }}</h2>
      <span v-if="job" class="rounded-full bg-black/5 px-3 py-1 text-sm" role="status">{{
        labels[job.status]
      }}</span>
    </div>
    <template v-if="job">
      <p class="mt-3 text-sm text-ink-900">{{ productName }}</p>
      <p class="mt-2 line-clamp-2 break-words text-sm text-ink-700">{{ job.request.adText }}</p>
      <p v-if="job.failureMessage" class="mt-3 text-sm text-red-700">{{ job.failureMessage }}</p>
      <RouterLink
        :to="{ name: 'inspection-result', params: { jobId } }"
        class="mt-4 inline-block text-sm underline"
        >{{ job.status === 'DONE' ? '검사 결과 보기' : '검사 상세 보기' }}</RouterLink
      >
    </template>
    <p v-else-if="loadState === 'loading'" class="mt-3 text-sm text-ink-700">
      번호표 상태를 확인하고 있습니다.
    </p>
    <p v-if="loadState === 'not-found'" class="mt-3 text-sm text-red-700">
      조회할 수 없는 번호표입니다.
    </p>
    <p v-if="loadState === 'error'" class="mt-3 text-sm text-red-700">
      상태 조회에 실패했습니다. 새 검사를 접수하지 않고 다시 조회해 주세요.
    </p>
    <p v-if="timedOut" class="mt-3 text-sm text-ink-700">
      3분 동안 조회했습니다. 서버 검사는 계속될 수 있으며 이력에서도 확인할 수 있습니다.
    </p>
    <button
      v-if="timedOut || loadState === 'error'"
      type="button"
      class="mt-3 text-sm underline"
      @click="recheck"
    >
      상태 다시 조회
    </button>
  </li>
</template>
