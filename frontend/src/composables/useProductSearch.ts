import { computed, onWatcherCleanup, ref, watch } from 'vue'
import { inspectionApi } from '@/api/inspection'
import type { Product } from '@/types/inspection'

// 0.3초면 "홍사"(→"홍삼")처럼 글자를 치는 도중에 잠깐 멈춘 상태로도 검색이 나가서 0.5초로 둠
const DEBOUNCE_MS = 500
const MIN_QUERY_LENGTH = 2
// 한글을 조합하는 중에 끝에 남은 낱자(ㄱ~ㅣ, 호환용 자모 영역)
// "홍삼 ㅌ"처럼 다음 글자를 치는 중간 상태로 검색하면 결과가 0건이 되어
// 목록과 "제품을 찾지 못했습니다"가 글자마다 번갈아 나타나므로 검색어에서 뺌
const TRAILING_JAMO = /[ㄱ-ㆎ]+$/u

// idle: 검색 전(검색어 2글자 미만) / loading: 입력 대기 또는 요청 중 / done: 결과 도착 / error: 요청 실패
type SearchStatus = 'idle' | 'loading' | 'done' | 'error'

// 검색어가 바뀌면 500ms 기다렸다가 제품을 검색
export function useProductSearch() {
  const query = ref('')
  const results = ref<Product[]>([])
  const status = ref<SearchStatus>('idle')

  // 실제로 검색할 문자열. "홍삼"과 "홍삼 ㅌ"은 같은 값이 되므로
  // 낱자만 덧붙는 동안에는 아래 watch가 다시 실행되지 않음
  const searchKeyword = computed(() => query.value.trim().replace(TRAILING_JAMO, '').trim())

  watch(searchKeyword, (keyword) => {
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
