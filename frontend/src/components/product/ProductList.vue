<script setup lang="ts">
import { computed } from 'vue'
import BaseBadge from '@/components/ui/BaseBadge.vue'
import BaseIcon from '@/components/ui/BaseIcon.vue'
import type { Product, ProductStatus } from '@/types/inspection'

const props = defineProps<{
  products: Product[]
  selectedIds: readonly string[]
}>()

const emit = defineEmits<{
  select: [product: Product]
}>()

const STATUS_BADGES: Partial<Record<ProductStatus, string>> = {
  DISCONTINUED: '생산종료',
  EXPORT_ONLY: '수출용',
}

const items = computed(() =>
  props.products.map((product) => ({
    product,
    selected: props.selectedIds.includes(product.id),
    badge: product.status ? STATUS_BADGES[product.status] : undefined,
  })),
)

// 이미 고른 항목은 disabled 대신 aria-disabled로 둔다.
// disabled면 누르는 순간 포커스가 사라져 키보드 사용자가 목록 위치를 잃는다.
function onClick(product: Product, selected: boolean) {
  if (!selected) emit('select', product)
}
</script>

<template>
  <ul class="divide-y divide-black/5 overflow-hidden rounded-2xl border border-black/5 bg-white/90">
    <li v-for="{ product, selected, badge } in items" :key="product.id">
      <button
        type="button"
        class="flex w-full items-start justify-between gap-3 px-5 py-3.5 text-left transition-colors duration-200 hover:bg-black/[0.02] aria-disabled:cursor-default aria-disabled:bg-accent/15"
        :aria-disabled="selected"
        @click="onClick(product, selected)"
      >
        <span class="min-w-0">
          <span class="flex flex-wrap items-center gap-x-2 gap-y-1">
            <!-- wrap-anywhere: 공백 없는 긴 이름은 전체 길이가 flex 항목의 최소 폭이 되어
                 "선택됨" 아래로 넘친다 -->
            <span class="min-w-0 text-sm wrap-anywhere text-ink-900">
              {{ product.name }}
            </span>
            <BaseBadge v-if="badge">{{ badge }}</BaseBadge>
          </span>
          <span class="mt-1 block text-xs text-ink-600">
            {{ product.manufacturer }} ·
            <span class="whitespace-nowrap tabular-nums">
              신고번호 {{ product.reportNo ?? '없음' }}
            </span>
          </span>
        </span>
        <!-- 선택 전에도 자리를 잡아 두어, 선택할 때 줄바꿈이 바뀌어 아래 항목이 밀리지 않게 한다.
             invisible(visibility: hidden)은 화면 낭독기에도 읽히지 않는다. -->
        <span
          class="inline-flex shrink-0 items-center gap-1.5 text-xs text-ink-900"
          :class="{ invisible: !selected }"
        >
          <span class="flex size-5 items-center justify-center rounded-full bg-accent">
            <BaseIcon name="check" :size="12" />
          </span>
          선택됨
        </span>
      </button>
    </li>
  </ul>
</template>
