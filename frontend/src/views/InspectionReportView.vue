<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { inspectionApi } from '@/api/inspection'
import { ApiError } from '@/api/errors'

const props = defineProps<{ jobId: string }>()
const html = ref<string | null>(null)
const downloadUrl = ref<string | null>(null)
const loading = ref(false)
const error = ref<string | null>(null)
let generation = 0

function releaseDownload() {
  if (downloadUrl.value !== null) URL.revokeObjectURL(downloadUrl.value)
  downloadUrl.value = null
}

watch(
  () => props.jobId,
  async (jobId) => {
    const current = ++generation
    releaseDownload()
    html.value = null
    error.value = null
    loading.value = true
    try {
      const document = await inspectionApi.getReport(jobId)
      if (current !== generation) return
      html.value = document
      downloadUrl.value = URL.createObjectURL(
        new Blob([document], { type: 'text/html;charset=utf-8' }),
      )
    } catch (cause) {
      if (current !== generation) return
      error.value =
        cause instanceof ApiError && cause.status === 409
          ? '완료된 검사만 리포트로 제공할 수 있습니다.'
          : cause instanceof ApiError && cause.status === 422
            ? '저장된 판정 정보가 부족해 리포트를 만들지 못했습니다.'
            : '리포트를 불러오지 못했습니다. 검사 결과로 돌아가 다시 시도해 주세요.'
    } finally {
      if (current === generation) loading.value = false
    }
  },
  { immediate: true },
)

onBeforeUnmount(() => {
  generation++
  releaseDownload()
})
</script>

<template>
  <div class="flex flex-col gap-5">
    <div class="flex flex-wrap items-center justify-between gap-4">
      <h1 class="text-4xl tracking-tight text-ink-900">검수 리포트</h1>
      <RouterLink
        :to="{ name: 'inspection-result', params: { jobId: props.jobId } }"
        class="text-sm text-ink-700 underline underline-offset-4"
      >
        검사 결과로 돌아가기
      </RouterLink>
    </div>
    <p v-if="loading" role="status" class="text-sm text-ink-700">리포트를 불러오는 중…</p>
    <p v-else-if="error" role="alert" class="text-sm text-red-700">{{ error }}</p>
    <template v-else-if="html && downloadUrl">
      <div class="flex flex-wrap items-center gap-3 text-sm">
        <a
          :href="downloadUrl"
          :download="`foodguard-inspection-${props.jobId}.html`"
          class="rounded-full border border-black/10 bg-white px-5 py-2.5 text-ink-900"
        >
          HTML 파일 저장
        </a>
        <a
          :href="downloadUrl"
          target="_blank"
          rel="noopener"
          class="text-ink-700 underline underline-offset-4"
        >
          새 창에서 보기
        </a>
        <span class="text-xs text-ink-600">문서의 인쇄 버튼에서 PDF로 저장할 수 있습니다.</span>
      </div>
      <iframe
        :srcdoc="html"
        title="검수 리포트 미리보기"
        sandbox="allow-scripts allow-modals allow-popups allow-popups-to-escape-sandbox"
        class="h-[1100px] w-full border border-black/10 bg-white"
      />
    </template>
  </div>
</template>
