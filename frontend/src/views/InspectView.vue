<script setup lang="ts">
import { computed, nextTick, ref, useTemplateRef, watch } from 'vue'
import { useRouter } from 'vue-router'
import { inspectionApi } from '@/api/inspection'
import { ApiError } from '@/api/errors'
import { countCodePoints, MAX_AD_TEXT_CHARACTERS } from '@/utils/text'
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

// 마지막으로 끝난 검색의 안내 문구
// 검색이 진행되는 동안에는 이 문구도 목록도 바꾸지 않고, 결과가 오면 한 번에 바꿈
// "검색 중…" 같은 중간 상태를 보여 주면 단어 사이에서 멈출 때마다 화면이 깜빡이기 때문
const statusMessage = ref('')
watch(status, (value) => {
  if (value === 'idle') statusMessage.value = ''
  else if (value === 'error')
    statusMessage.value = '검색에 실패했습니다. 잠시 후 다시 시도해 주세요.'
  else if (value === 'done') statusMessage.value = notFound.value ? '제품을 찾지 못했습니다.' : ''
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
  if (countCodePoints(store.adText) > MAX_AD_TEXT_CHARACTERS)
    return '광고 문구를 3,000자 이내로 줄여 주세요'
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
const adTextStepDone = computed(
  () => store.adText.trim() !== '' && countCodePoints(store.adText) <= MAX_AD_TEXT_CHARACTERS,
)

const router = useRouter()
const submitting = ref(false)
const submitError = ref<string | null>(null)
const batchError = ref<string | null>(null)

function addToBatch() {
  if (disabledReason.value !== null || submitting.value) return
  const label = store.selectedProducts[0]?.name ?? '제품 정보 미등록'
  if (!store.addBatchDraft(label)) return
  typeChoice.value = null
  typeQuestionOpen.value = false
  batchError.value = null
}

async function submitBatch() {
  if (submitting.value || store.batchDrafts.length === 0) return
  submitting.value = true
  batchError.value = null
  try {
    const result = await inspectionApi.createBatch(store.batchDrafts.map((item) => item.request))
    store.clearBatchDrafts()
    await router.push({
      name: 'inspection-batch',
      query: { jobs: result.items.map((item) => item.jobId).join(',') },
    })
  } catch (error) {
    batchError.value =
      error instanceof ApiError &&
      error.status !== null &&
      error.status >= 400 &&
      error.status < 500
        ? error.message
        : '접수 응답을 확인하지 못했습니다. 다시 접수하기 전에 검수 이력에서 접수 여부를 확인해 주세요.'
  } finally {
    submitting.value = false
  }
}

async function submit() {
  if (disabledReason.value !== null || submitting.value) return
  submitting.value = true
  submitError.value = null
  try {
    const { jobId } = await inspectionApi.createInspection(store.toRequest())
    await router.push({ name: 'inspection-result', params: { jobId } })
  } catch (error) {
    submitError.value =
      error instanceof ApiError
        ? error.message
        : '검사를 시작하지 못했습니다. 잠시 후 다시 시도해 주세요.'
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
          <button
            type="button"
            class="w-full rounded-full border border-black/15 px-6 py-3 text-sm text-ink-900 disabled:cursor-not-allowed disabled:opacity-40"
            :disabled="disabledReason !== null || submitting || store.batchDrafts.length >= 3"
            @click="addToBatch"
          >
            검사 목록에 담기 ({{ store.batchDrafts.length }} / 3)
          </button>
          <p
            v-if="submitError"
            role="alert"
            class="rounded-2xl border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700"
          >
            {{ submitError }}
          </p>

          <button
            type="button"
            class="group flex w-full items-center justify-between gap-3 rounded-full bg-primary py-2 pr-2 pl-7 text-base text-white shadow-sm transition-colors duration-200 enabled:hover:bg-black disabled:cursor-not-allowed disabled:bg-black/[0.07] disabled:text-ink-600 disabled:shadow-none"
            :disabled="disabledReason !== null || submitting"
            :aria-describedby="disabledReason ? 'inspect-disabled-reason' : undefined"
            @click="submit"
          >
            {{
              submitting
                ? '검사 요청 중…'
                : store.batchDrafts.length
                  ? '현재 항목만 바로 검사'
                  : '검사하기'
            }}
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
    <section
      v-if="store.batchDrafts.length > 0"
      class="card space-y-5 p-6"
      aria-labelledby="batch-title"
    >
      <h2 id="batch-title" class="text-xl text-ink-900">
        접수할 검사 목록 · {{ store.batchDrafts.length }}건
      </h2>
      <p class="text-sm text-ink-700">
        각 항목은 제품 하나와 광고 문구 하나입니다. 최대 3건을 함께 접수하고 차례로 검사합니다. AI
        검사는 항목별로 실행됩니다.
      </p>
      <ol class="divide-y divide-black/10">
        <li
          v-for="(item, index) in store.batchDrafts"
          :key="item.id"
          class="flex items-start justify-between gap-4 py-4"
        >
          <div class="min-w-0 space-y-1">
            <p class="text-sm text-ink-900">{{ index + 1 }}. {{ item.label }}</p>
            <p class="line-clamp-2 break-words text-sm text-ink-700">{{ item.request.adText }}</p>
            <p class="text-xs text-ink-600">{{ countCodePoints(item.request.adText) }} / 3000자</p>
          </div>
          <button
            type="button"
            class="shrink-0 text-sm underline"
            :disabled="submitting"
            :aria-label="`${index + 1}번 검사 목록에서 삭제`"
            @click="store.removeBatchDraft(item.id)"
          >
            삭제
          </button>
        </li>
      </ol>
      <p class="text-xs text-ink-600">
        목록에 담은 항목만 접수합니다. 작성 중인 문구는 먼저 목록에 담아 주세요. 기존 대기·진행 중인
        검사도 사용자별 최대 3건 제한에 포함됩니다.
      </p>
      <p v-if="batchError" role="alert" class="text-sm text-red-700">{{ batchError }}</p>
      <button
        type="button"
        class="rounded-full bg-primary px-7 py-3 text-sm text-white disabled:opacity-40"
        :disabled="submitting"
        @click="submitBatch"
      >
        {{ submitting ? '접수 중…' : `목록 ${store.batchDrafts.length}건 접수하기` }}
      </button>
    </section>
  </div>
</template>
