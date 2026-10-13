<script setup lang="ts">
import BaseIcon from '@/components/ui/BaseIcon.vue'
import type { Product } from '@/types/inspection'

defineProps<{
  product: Product
}>()

defineEmits<{
  remove: []
}>()
</script>

<template>
  <span
    class="inline-flex max-w-full items-center gap-1 rounded-full bg-white py-1 pr-1 pl-4 text-sm text-ink-900 shadow-sm ring-1 ring-black/5"
  >
    <span class="min-w-0 break-words">{{ product.name }}</span>
    <button
      type="button"
      class="inline-flex size-7 shrink-0 items-center justify-center rounded-full text-ink-600 transition-colors duration-200 hover:bg-primary hover:text-white"
      @click="$emit('remove')"
    >
      <BaseIcon name="x" :size="14" />
      <span class="sr-only">{{ product.name }} 선택 해제</span>
    </button>
  </span>
</template>
