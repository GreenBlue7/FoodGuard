import { onWatcherCleanup, ref, watch } from 'vue'
import { inspectionApi } from '@/api/inspection'
import type { Product } from '@/types/inspection'

const DEBOUNCE_MS = 300
const MIN_QUERY_LENGTH = 2

// idle: 검색 전(검색어 2글자 미만) / loading: 입력 대기 또는 요청 중 / done: 결과 도착 / error: 요청 실패
type SearchStatus = 'idle' | 'loading' | 'done' | 'error'

// 검색어가 바뀌면 300ms 기다렸다가 제품을 검색
export function useProductSearch() {
  const query = ref('')
  const results = ref<Product[]>([])
  const status = ref<SearchStatus>('idle')

  watch(query, (value) => {
    const keyword = value.trim()
    if (keyword.length < MIN_QUERY_LENGTH) {
      results.value = []
      status.value = 'idle'
      return
    }

    status.value = 'loading'
    let cancelled = false
    const timer = setTimeout(async () => {
      try {
        const found = await inspectionApi.searchProducts(keyword)
        if (cancelled) return
        results.value = found
        status.value = 'done'
      } catch {
        if (cancelled) return
        results.value = []
        status.value = 'error'
      }
    }, DEBOUNCE_MS)

    // 다음 입력이 들어오거나 화면을 떠나면 실행
    // 대기 중인 검색은 취소하고, 이미 보낸 요청의 응답은 늦게 와도 무시
    onWatcherCleanup(() => {
      cancelled = true
      clearTimeout(timer)
    })
  })

  return { query, results, status }
}
