import { ref } from 'vue'
import { defineStore } from 'pinia'
import type { InspectionRequest, Product, ProductTypeAnswer } from '@/types/inspection'

// 검사 요청 전의 입력 상태(/ 화면)
// 결과 화면은 이 스토어 없이 jobId만으로 동작
export const useInspectionStore = defineStore('inspection', () => {
  const selectedProducts = ref<Product[]>([])
  const adText = ref('')
  // 2a 흐름의 답
  // null이면 미응답 (건강기능식품은 "번호 없이 진행"을 눌러야 값이 들어옴)
  const productTypeAnswer = ref<ProductTypeAnswer | null>(null)

  function selectProduct(product: Product) {
    if (selectedProducts.value.some((selected) => selected.id === product.id)) return
    selectedProducts.value.push(product)
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

  return {
    selectedProducts,
    adText,
    productTypeAnswer,
    selectProduct,
    deselectProduct,
    toRequest,
  }
})
