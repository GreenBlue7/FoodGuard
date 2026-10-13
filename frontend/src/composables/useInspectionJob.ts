import { onScopeDispose, ref, toValue, watch, type MaybeRefOrGetter } from 'vue'
import { inspectionApi } from '@/api/inspection'
import { NotFoundError } from '@/api/errors'
import type { InspectionJob, JobStatus } from '@/types/inspection'

const POLL_INTERVAL_MS = 1000
const TIMEOUT_MS = 60_000

// loading: 첫 응답 전 / ready: job 있음 / not-found: 없는 작업 / error: 그 밖의 조회 실패
type JobLoadState = 'loading' | 'ready' | 'not-found' | 'error'

export function isInProgress(status: JobStatus): boolean {
  return status === 'PENDING' || status === 'RUNNING'
}

// jobId로 작업을 불러오고, 끝날 때까지 1초마다 다시 조회
// 폴링을 시작한 뒤 60초가 지나도 끝나지 않으면 timedOut을 켜고 멈춘다
// 이 시간 초과는 프론트가 기다리기를 그만둔 것일 뿐이라 서버의 TIMEOUT 상태와는 구분
export function useInspectionJob(jobId: MaybeRefOrGetter<string>) {
  const job = ref<InspectionJob | null>(null)
  const loadState = ref<JobLoadState>('loading')
  const timedOut = ref(false)

  let stopPolling = () => {}

  function startPolling(id: string) {
    stopPolling()
    timedOut.value = false

    let stopped = false
    let pollTimer: ReturnType<typeof setTimeout> | undefined

    // 응답이 아예 안 오는 경우도 잡으려고 폴링과 별도의 타이머로 잰다
    const timeoutTimer = setTimeout(() => {
      timedOut.value = true
      stop()
    }, TIMEOUT_MS)

    const stop = () => {
      stopped = true
      clearTimeout(pollTimer)
      clearTimeout(timeoutTimer)
    }
    stopPolling = stop

    const poll = async () => {
      try {
        const next = await inspectionApi.getJob(id)
        if (stopped) return // 이미 멈춘 폴링의 늦은 응답은 버림
        job.value = next
        loadState.value = 'ready'
        if (isInProgress(next.status)) pollTimer = setTimeout(poll, POLL_INTERVAL_MS)
        else stop()
      } catch (error) {
        if (stopped) return
        loadState.value = error instanceof NotFoundError ? 'not-found' : 'error'
        stop()
      }
    }
    void poll()
  }

  // jobId가 바뀌면(재시도로 같은 화면에서 이동) 이전 작업의 상태를 지우고 처음부터 조회
  watch(
    () => toValue(jobId),
    (id) => {
      job.value = null
      loadState.value = 'loading'
      startPolling(id)
    },
    { immediate: true },
  )

  // 화면을 떠나면 폴링을 멈춤
  onScopeDispose(() => stopPolling())

  // 프론트 시간 초과 뒤 같은 jobId로 다시 조회
  // 새 작업은 만들지 않고 60초를 새로 잰다.
  function recheck() {
    startPolling(toValue(jobId))
  }

  return { job, loadState, timedOut, recheck }
}
