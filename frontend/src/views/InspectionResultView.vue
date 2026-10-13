<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { inspectionApi } from '@/api/inspection'
import JobStatusBadge from '@/components/result/JobStatusBadge.vue'
import Layer1Panel from '@/components/result/Layer1Panel.vue'
import Layer2Panel from '@/components/result/Layer2Panel.vue'
import BaseIcon from '@/components/ui/BaseIcon.vue'
import { isInProgress, useInspectionJob } from '@/composables/useInspectionJob'
import type { ProductInfoSource, Verdict } from '@/types/inspection'
import { VERDICT_ICONS, VERDICT_LABELS } from '@/utils/verdict'

const props = defineProps<{
  jobId: string
}>()

// 스토어 없이 jobId만으로 상태를 복원 (새로고침·링크 공유 대응)
const { job, loadState, timedOut, recheck } = useInspectionJob(() => props.jobId)

// 서버가 알려 준 상태
// 프론트 시간 초과(timedOut)는 여기에 X
const status = computed(() => job.value?.status ?? null)
const inProgress = computed(
  () => !timedOut.value && status.value !== null && isInProgress(status.value),
)
// 서버가 실패·시간 초과를 확정한 경우에만 새 작업으로 재시도
const canRetry = computed(
  () => job.value !== null && (status.value === 'FAILED' || status.value === 'TIMEOUT'),
)

const headline = computed(() => {
  if (timedOut.value) return '응답이 늦어지고 있습니다. 검사는 계속 진행 중일 수 있습니다.'
  if (loadState.value === 'loading') return '검사 정보를 불러오는 중…'
  if (loadState.value === 'not-found') return '작업을 찾을 수 없습니다.'
  if (loadState.value === 'error') {
    return '검사 정보를 불러오지 못했습니다. 잠시 후 새로고침해 주세요.'
  }
  switch (status.value) {
    case 'PENDING':
    case 'RUNNING':
      return `검사 중입니다… (작업 번호 #${props.jobId})`
    case 'DONE':
      return '검사가 끝났습니다.'
    case 'FAILED':
      return '판정에 실패했습니다.'
    case 'TIMEOUT':
      return '시간이 초과되었습니다.'
    default:
      return ''
  }
})

// 제품 정보를 식약처 데이터로 확인하지 못한 경우 상태 카드 안에서 알림
const PRODUCT_INFO_NOTICES: Partial<Record<ProductInfoSource, string>> = {
  USER_DECLARED: '판매자가 선택한 제품 종류(일반식품)로 검사했습니다.',
  UNVERIFIED: '제품 정보를 확인하지 못해 제품 종류를 특정하지 않고 검사했습니다.',
}
const productInfoNotice = computed(() =>
  job.value ? PRODUCT_INFO_NOTICES[job.value.productInfoSource] : undefined,
)

// 요약 카드의 큰 숫자
// 판정이 끝난 경우에만 판정별 건수를 셈
const verdictStats = computed(() => {
  const findings = status.value === 'DONE' ? job.value?.layer2?.findings : undefined
  if (!findings) return []
  return (
    (['VIOLATION', 'REVIEW', 'OK', 'HOLD'] as const)
      .map((verdict) => ({
        verdict,
        label: VERDICT_LABELS[verdict],
        icon: VERDICT_ICONS[verdict],
        count: findings.filter((finding) => finding.verdict === verdict).length,
      }))
      // 판단 보류는 0건이면 칸만 차지하므로 있을 때만 표시
      .filter((stat) => stat.verdict !== 'HOLD' || stat.count > 0)
  )
})
const STAT_ICON_CLASSES: Record<Verdict, string> = {
  VIOLATION: 'text-red-600',
  REVIEW: 'text-orange-600',
  OK: 'text-emerald-600',
  HOLD: 'text-ink-600',
}

const router = useRouter()
const retrying = ref(false)
const retryFailed = ref(false)

// 같은 요청으로 새 작업을 만들고 그 결과 화면으로 이동
async function retry() {
  if (job.value === null) return
  retrying.value = true
  retryFailed.value = false
  try {
    const { jobId } = await inspectionApi.createInspection(job.value.request)
    await router.push({ name: 'inspection-result', params: { jobId } })
  } catch {
    retryFailed.value = true
  } finally {
    retrying.value = false
  }
}
</script>

<template>
  <div class="flex flex-col gap-8">
    <h1 class="text-5xl tracking-tight text-ink-900">검사 결과</h1>

    <!-- 요약 카드 + 필수 문구 확인(차콜 카드). 두 카드 높이를 맞춤 -->
    <div class="grid grid-cols-3 gap-5">
      <section
        class="card flex flex-col gap-6 p-6"
        :class="job?.layer1 ? 'col-span-2' : 'col-span-3'"
      >
        <div class="flex flex-wrap items-center gap-3">
          <!-- 서버에서 막 받은 상태일 때만 보여줌 프론트 시간 초과나 조회 실패 뒤에는
               마지막 상태(예: RUNNING)가 옆 문구와 어긋남 -->
          <JobStatusBadge
            v-if="status !== null && loadState === 'ready' && !timedOut"
            :status="status"
          />
          <!-- 상태가 바뀔 때마다 화면 낭독기가 읽도록 영역은 항상 두고 문구만 바꿈 -->
          <p role="status" class="text-lg text-ink-900">{{ headline }}</p>
        </div>

        <!-- 판정별 큰 숫자 판정이 끝났을 때만 보임
             dt가 dd보다 먼저 와야 해서, 라벨을 숫자 아래에 보이도록 순서만 뒤집는다 -->
        <dl v-if="verdictStats.length > 0" class="my-auto flex items-end gap-12 px-1">
          <div
            v-for="stat in verdictStats"
            :key="stat.verdict"
            class="flex flex-col-reverse gap-1.5"
          >
            <dt class="text-sm text-ink-700">{{ stat.label }}</dt>
            <dd class="flex items-end gap-2.5">
              <span
                class="mb-1.5 flex size-8 items-center justify-center rounded-full bg-white shadow-sm ring-1 ring-black/5"
                :class="STAT_ICON_CLASSES[stat.verdict]"
              >
                <BaseIcon :name="stat.icon" :size="16" />
              </span>
              <span class="font-heading text-7xl leading-none font-light tabular-nums">{{
                stat.count
              }}</span>
            </dd>
          </div>
        </dl>

        <!-- 진행률 없는 진행 바(aria-valuenow 없음). 빗금 트랙 위로 노란 막대가 지나감 -->
        <div
          v-if="inProgress"
          role="progressbar"
          aria-label="검사 진행 상황"
          class="my-auto h-4 overflow-hidden rounded-full bg-hatch ring-1 ring-black/5"
        >
          <div
            class="h-full w-1/3 animate-progress-slide rounded-full bg-accent motion-reduce:w-full motion-reduce:animate-none motion-reduce:bg-accent/50"
          />
        </div>

        <button
          v-if="timedOut"
          type="button"
          class="inline-flex items-center gap-2 self-start rounded-full border border-black/10 bg-white px-5 py-2.5 text-sm text-ink-900 transition-colors duration-200 hover:border-black/20"
          @click="recheck"
        >
          <BaseIcon name="refresh" :size="14" />
          결과 다시 확인
        </button>

        <template v-else-if="canRetry">
          <p
            v-if="retryFailed"
            role="alert"
            class="rounded-2xl border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700"
          >
            다시 요청하지 못했습니다. 잠시 후 다시 시도해 주세요.
          </p>
          <button
            type="button"
            class="inline-flex items-center gap-2 self-start rounded-full bg-primary px-5 py-2.5 text-sm text-white transition-colors duration-200 enabled:hover:bg-black disabled:cursor-not-allowed disabled:opacity-40"
            :disabled="retrying"
            @click="retry"
          >
            <BaseIcon name="refresh" :size="14" />
            {{ retrying ? '재시도 중…' : '재시도' }}
          </button>
        </template>

        <!-- 제품 정보 안내는 카드 맨 아래에 붙여, 카드 높이가 늘어나도 빈 곳이 아래에 몰리지 않게 함 -->
        <p
          v-if="productInfoNotice"
          class="mt-auto flex items-start gap-2 rounded-2xl bg-black/[0.04] px-4 py-3 text-sm text-ink-700"
        >
          <BaseIcon name="info" :size="16" class="mt-0.5 shrink-0" />
          {{ productInfoNotice }}
        </p>
      </section>

      <!-- 1층 결과는 백엔드에서 먼저 계산되므로, 2층 판정이 끝나기 전이라도 도착하면 보여줌 -->
      <Layer1Panel v-if="job?.layer1" :layer1="job.layer1" />
    </div>

    <Layer2Panel
      v-if="status === 'DONE' && job?.layer2"
      :layer2="job.layer2"
      :ad-text="job.request.adText"
    />
  </div>
</template>
