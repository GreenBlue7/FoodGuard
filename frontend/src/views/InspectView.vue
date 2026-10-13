<script setup lang="ts">
import { computed, nextTick, ref, useTemplateRef, watch } from 'vue'
import { useRouter } from 'vue-router'
import { inspectionApi } from '@/api/inspection'
import AdTextInput from '@/components/inspect/AdTextInput.vue'
import InspectSteps from '@/components/inspect/InspectSteps.vue'
import ProductChip from '@/components/product/ProductChip.vue'
import ProductList from '@/components/product/ProductList.vue'
import ProductSearch from '@/components/product/ProductSearch.vue'
import ProductSummary from '@/components/product/ProductSummary.vue'
import ProductTypeQuestion from '@/components/product/ProductTypeQuestion.vue'
import BaseIcon from '@/components/ui/BaseIcon.vue'
import { useProductSearch } from '@/composables/useProductSearch'
import { useInspectionStore } from '@/stores/inspection'
import type { ProductTypeAnswer } from '@/types/inspection'

const store = useInspectionStore()
const { query, results, status } = useProductSearch()

const selectedIds = computed(() => store.selectedProducts.map((product) => product.id))

const productSearch = useTemplateRef('productSearch')
const chipList = useTemplateRef('chipList')

async function removeChip(productId: string, index: number) {
  store.deselectProduct(productId)
  await nextTick()
  const buttons = chipList.value?.querySelectorAll('button') ?? []
  const target = buttons[index] ?? buttons[index - 1]
  if (target) target.focus()
  else productSearch.value?.focus()
}

const notFound = computed(() => status.value === 'done' && results.value.length === 0)

const statusMessage = computed(() => {
  // 이전 결과가 화면에 남아 있으면 목록을 흐리게 하는 것으로 검색 중임을 알림
  // 글자마다 이 줄이 생겼다 사라지면 아래 목록이 위아래로 흔들리기 때문
  if (status.value === 'loading') return results.value.length > 0 ? '' : '검색 중…'
  if (status.value === 'error') return '검색에 실패했습니다. 잠시 후 다시 시도해 주세요.'
  if (notFound.value) return '제품을 찾지 못했습니다.'
  return ''
})

const typeQuestionOpen = ref(store.productTypeAnswer !== null)
watch(notFound, (value) => {
  if (value) typeQuestionOpen.value = true
})
const showTypeQuestion = computed(
  () => typeQuestionOpen.value && store.selectedProducts.length === 0,
)

const typeChoice = ref<ProductTypeAnswer | null>(store.productTypeAnswer)

const disabledReason = computed(() => {
  if (store.adText.trim() === '') return '광고 문구를 입력해 주세요'
  if (store.selectedProducts.length === 0 && store.productTypeAnswer === null) {
    return typeChoice.value === 'HEALTH_FUNCTIONAL'
      ? "신고번호로 다시 검색하거나 '번호 없이 진행'을 눌러 주세요"
      : '제품을 선택하거나 제품 종류를 알려 주세요'
  }
  return null
})

const productStepDone = computed(
  () => store.selectedProducts.length > 0 || store.productTypeAnswer !== null,
)
const adTextStepDone = computed(() => store.adText.trim() !== '')

const router = useRouter()
const submitting = ref(false)
const submitFailed = ref(false)

async function submit() {
  submitting.value = true
  submitFailed.value = false
  try {
    const { jobId } = await inspectionApi.createInspection(store.toRequest())
    await router.push({ name: 'inspection-result', params: { jobId } })
  } catch {
    submitFailed.value = true
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="flex flex-col gap-8">
    <!-- 제목 + 진행 단계 -->
    <div class="flex flex-col gap-6">
      <h1 class="text-5xl tracking-tight text-ink-900">광고 문구 검사</h1>
      <InspectSteps
        :product-done="productStepDone"
        :ad-text-done="adTextStepDone"
        class="max-w-3xl"
      />
    </div>

    <!-- 왼쪽: 제품 선택 / 오른쪽: 광고 문구 + 검사 버튼 -->
    <div class="grid grid-cols-2 items-start gap-5">
      <section aria-labelledby="product-section-title" class="card space-y-5 p-6">
        <h2 id="product-section-title" class="text-xl text-ink-900">제품 선택</h2>

        <ProductSearch ref="productSearch" v-model="query" />

        <p role="status" class="px-1 text-sm text-ink-700 empty:mb-0">{{ statusMessage }}</p>

        <!-- 결과 목록을 chip·요약보다 위에 -> 제품을 고를 때 목록이 아래로 밀리지 않게
             검색 중에도 이전 결과를 흐리게 남겨 둠. 글자마다 목록을 지웠다 다시 그리면
             페이지 높이가 바뀌어 화면 전체가 흔들림 -->
        <ProductList
          v-if="results.length > 0"
          :products="results"
          :selected-ids="selectedIds"
          :aria-busy="status === 'loading'"
          class="transition-opacity duration-200"
          :class="{ 'opacity-50': status === 'loading' }"
          @select="store.selectProduct"
        />

        <ul
          v-if="store.selectedProducts.length > 0"
          ref="chipList"
          aria-label="선택한 제품"
          class="flex flex-wrap gap-2"
        >
          <li v-for="(product, index) in store.selectedProducts" :key="product.id" class="min-w-0">
            <ProductChip :product="product" @remove="removeChip(product.id, index)" />
          </li>
        </ul>

        <ProductSummary
          v-if="store.selectedProducts.length > 0"
          :products="store.selectedProducts"
        />

        <ProductTypeQuestion
          v-if="showTypeQuestion"
          v-model="store.productTypeAnswer"
          v-model:choice="typeChoice"
        />
      </section>

      <div class="flex flex-col gap-5">
        <section aria-labelledby="ad-section-title" class="card flex flex-col gap-5 p-6">
          <h2 id="ad-section-title" class="text-xl text-ink-900">광고 문구</h2>
          <AdTextInput v-model="store.adText" />
        </section>

        <div class="space-y-3">
          <p
            v-if="submitFailed"
            role="alert"
            class="rounded-2xl border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700"
          >
            검사를 시작하지 못했습니다. 잠시 후 다시 시도해 주세요.
          </p>

          <button
            type="button"
            class="group flex w-full items-center justify-between gap-3 rounded-full bg-primary py-2 pr-2 pl-7 text-base text-white shadow-sm transition-colors duration-200 enabled:hover:bg-black disabled:cursor-not-allowed disabled:bg-black/[0.07] disabled:text-ink-600 disabled:shadow-none"
            :disabled="disabledReason !== null || submitting"
            :aria-describedby="disabledReason ? 'inspect-disabled-reason' : undefined"
            @click="submit"
          >
            {{ submitting ? '검사 요청 중…' : '검사하기' }}
            <span
              class="flex size-11 shrink-0 items-center justify-center rounded-full bg-accent text-ink-900 group-disabled:bg-white/70 group-disabled:text-ink-400"
            >
              <BaseIcon name="arrow-up-right" :size="18" />
            </span>
          </button>
          <p v-if="disabledReason" id="inspect-disabled-reason" class="px-2 text-sm text-ink-700">
            {{ disabledReason }}
          </p>
        </div>
      </div>
    </div>
  </div>
</template>
