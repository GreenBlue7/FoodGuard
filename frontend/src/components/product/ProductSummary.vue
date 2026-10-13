<script setup lang="ts">
import type { Product, ProductType } from '@/types/inspection'

defineProps<{
  products: Product[]
}>()

const TYPE_LABELS: Record<ProductType, string> = {
  HEALTH_FUNCTIONAL: '건강기능식품',
  FUNCTIONAL_GENERAL: '기능성 표시 일반식품',
  GENERAL: '일반식품',
  UNDETERMINED: '판단 보류',
}
</script>

<template>
  <div class="rounded-2xl bg-black/[0.03] px-5 py-4">
    <h3 class="text-sm text-ink-700">선택한 제품 정보 (식약처 데이터)</h3>
    <ul class="mt-3 space-y-3">
      <li v-for="product in products" :key="product.id" class="text-sm">
        <p class="break-words text-ink-900">{{ product.name }}</p>
        <dl class="mt-1 grid grid-cols-[auto_minmax(0,1fr)] gap-x-4 gap-y-0.5 text-ink-700">
          <dt class="text-ink-600">제품 종류</dt>
          <dd>{{ TYPE_LABELS[product.type] }}</dd>
          <dt class="text-ink-600">인정 기능성</dt>
          <dd>{{ product.approvedFunctions.join(', ') || '없음' }}</dd>
        </dl>
      </li>
    </ul>
  </div>
</template>
