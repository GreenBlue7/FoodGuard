import { ref, watch } from 'vue'
import { defineStore } from 'pinia'
import type { InspectionRequest, Product, ProductTypeAnswer } from '@/types/inspection'
import { useAuthStore } from '@/stores/auth'

// 검사 요청 전의 입력 상태(/ 화면)
// 결과 화면은 이 스토어 없이 jobId만으로 동작
export const useInspectionStore = defineStore('inspection', () => {
  const batchDrafts = ref<{ id: string; label: string; request: InspectionRequest }[]>([])
  const auth = useAuthStore()
  watch(
    () => auth.accessToken,
    (token) => {
      if (token === null) {
        batchDrafts.value = []
        selectedProducts.value = []
        adText.value = ''
        productTypeAnswer.value = null
      }
    },
  )
  const selectedProducts = ref<Product[]>([])
  const adText = ref('')
  // 2a 흐름의 답
  // null이면 미응답 (건강기능식품은 "번호 없이 진행"을 눌러야 값이 들어옴)
  const productTypeAnswer = ref<ProductTypeAnswer | null>(null)

  // 작성 중인 검사 항목에는 제품 하나를 지정한다.
  // 여러 검사는 각각 목록에 담고 별도 번호표로 접수한다.
  function selectProduct(product: Product) {
    if (selectedProducts.value.some((selected) => selected.id === product.id)) return
    selectedProducts.value = [product]
    // 제품을 고르면 식약처 데이터로 종류를 알 수 있으므로 2a 답은 버림
    productTypeAnswer.value = null
  }

  function deselectProduct(productId: string) {
    selectedProducts.value = selectedProducts.value.filter((product) => product.id !== productId)
  }

  // 2a 답은 제품이 하나도 없을 때만 남아 있으므로(selectProduct 참고) 있으면 그대로 탑재
  function toRequest(): InspectionRequest {
    const request: InspectionRequest = {
      productIds: selectedProducts.value.map((product) => product.id),
      adText: adText.value,
    }
    if (productTypeAnswer.value !== null) request.productTypeAnswer = productTypeAnswer.value
    return request
  }

  function addBatchDraft(label: string) {
    if (batchDrafts.value.length >= 3) return false
    batchDrafts.value.push({ id: crypto.randomUUID(), label, request: toRequest() })
    selectedProducts.value = []
    productTypeAnswer.value = null
    adText.value = ''
    return true
  }

  function removeBatchDraft(id: string) {
    batchDrafts.value = batchDrafts.value.filter((item) => item.id !== id)
  }

  function clearBatchDrafts() {
    batchDrafts.value = []
  }

  return {
    selectedProducts,
    adText,
    productTypeAnswer,
    selectProduct,
    deselectProduct,
    toRequest,
    batchDrafts,
    addBatchDraft,
    removeBatchDraft,
    clearBatchDrafts,
  }
})
