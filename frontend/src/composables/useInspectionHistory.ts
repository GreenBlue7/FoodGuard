import { type LocationQueryValue, useRoute, useRouter } from 'vue-router'
import { computed, onWatcherCleanup, ref, watch } from 'vue'
import type { InspectionHistoryItem } from '@/types/inspection'
import { inspectionApi } from '@/api/inspection'

// 한 페이지에 보여줄 검수 기록 개수
// 화면의 페이지 번호 계산(totalPages)이 이 값에 따라 달라지므로 프론트가 정해서 보냄
const PAGE_SIZE = 20
// 1 이상의 정수 (0으로 시작하거나 공백·소수점·지수 표기가 섞인 값은 제외)
const PAGE_PATTERN = /^[1-9]\d*$/

// loading: 요청 중 / done: 결과 도착 / error: 요청 실패
type HistoryStatus = 'loading' | 'done' | 'error'

// 내 검수 이력 목록. 현재 페이지는 URL의 ?page(1부터)가 기준
// 0부터 세는 API의 page는 이 파일 밖으로 내보내지 않음
export function useInspectionHistory() {
  const route = useRoute()
  const router = useRouter()

  const items = ref<InspectionHistoryItem[]>([])
  const totalElements = ref(0)
  const totalPages = ref(0)
  const status = ref<HistoryStatus>('loading')

  // route.query.page가 바뀔 때마다 parsePage를 다시 돌리고, 유효하지 않으면 1
  const currentPage = computed(() => parsePage(route.query.page) ?? 1)

  // 형식이 틀린 ?page는 주소에서 지움 (화면은 이미 1페이지를 보여 주고 있음)
  // 방문 기록에 잘못된 주소가 남지 않도록 push가 아니라 replace
  watch(
    () => route.query.page,
    (rawPage) => {
      if (rawPage === undefined || parsePage(rawPage) !== null) return
      void router.replace({ query: { ...route.query, page: undefined } })
    },
    { immediate: true },
  )

  // 목록 요청
  // 같은 라우트에서 쿼리만 바뀌면 컴포넌트가 재사용되므로, 페이지가 바뀔 때마다 직접 다시 요청
  watch(
    currentPage,
    async (page) => {
      status.value = 'loading'
      // 다른 페이지로 넘어가거나 화면을 떠나면, 이미 보낸 요청의 응답은 늦게 와도 무시
      // (onWatcherCleanup은 await보다 앞에서 등록해야 함)
      let cancelled = false
      onWatcherCleanup(() => {
        cancelled = true
      })

      try {
        const historyPage = await inspectionApi.getHistory(page - 1, PAGE_SIZE)
        if (cancelled) return

        items.value = historyPage.items
        totalElements.value = historyPage.totalElements
        totalPages.value = historyPage.totalPages
        status.value = 'done'
      } catch {
        if (cancelled) return
        status.value = 'error'
      }
    },
    { immediate: true },
  )

  // 페이지 번호(1부터)로 이동. 1페이지는 ?page 없이 /history로 둬서 주소를 하나로 유지
  // 1보다 작은 번호는 주소에 들어가기 전에 막음 (들어가면 정리되긴 하지만 방문 기록에 잘못된 주소가 남음)
  function goToPage(page: number) {
    if (page < 1) return
    void router.push({ query: { ...route.query, page: page === 1 ? undefined : String(page) } })
  }

  return { items, totalElements, totalPages, currentPage, status, goToPage }
}

// URL의 ?page 값을 1부터 세는 페이지 번호로 바꿈. 형식이 맞지 않으면 null
function parsePage(value: LocationQueryValue | LocationQueryValue[] | undefined): number | null {
  // 1단계: 페이지 값이 string인지 확인 (없거나(undefined), 비었거나(null), 여러 개(배열)면 유효하지 않음)
  if (typeof value !== 'string') return null
  // 2단계: 정규식으로 형식 확인
  if (!PAGE_PATTERN.test(value)) return null

  // 3단계: 너무 큰 값 거르기 (정규식은 통과해도 JS 숫자로 정확히 표현할 수 없음)
  const page = Number(value)
  return Number.isSafeInteger(page) ? page : null
}
