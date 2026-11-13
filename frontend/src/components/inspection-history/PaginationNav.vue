<script setup lang="ts">
import { computed } from 'vue'
import BaseIcon from '@/components/ui/BaseIcon.vue'

// 페이지 번호 목록. 라우터를 모르고, 눌린 번호만 알림 (URL 처리는 useInspectionHistory가 함)
const props = defineProps<{
  currentPage: number // 1부터
  totalPages: number
}>()

const emit = defineEmits<{
  change: [page: number] // change --> 이벤트 이름
}>()

// 숫자는 페이지 번호, 'gap'은 생략된 구간('…' 로 표시되는 부분)
type PageItem = number | 'gap'

// 처음/마지막 페이지와 현재 페이지 앞뒤만 보여 주고 나머지는 '…'로 줄임
// 칸 수를 항상 7개로 맞춰서, 번호를 누를 때마다 버튼 위치가 밀리지 않게
const SLOT_COUNT = 7

// 어떤 칸을 보여줄 지 계산
function pageItems(current: number, total: number): PageItem[] {
  if (total <= SLOT_COUNT) return Array.from({ length: total }, (_, i) => i + 1)
  // 앞쪽: 1 2 3 4 5 … 10
  if (current <= 4) return [1, 2, 3, 4, 5, 'gap', total]
  // 뒤쪽: 1 … 6 7 8 9 10
  if (current >= total - 3) return [1, 'gap', total - 4, total - 3, total - 2, total - 1, total]
  // 가운데: 1 … 4 5 6 … 10
  return [1, 'gap', current - 1, current, current + 1, 'gap', total]
}

const items = computed(() => pageItems(props.currentPage, props.totalPages))
</script>

<template>
  <!-- 아이콘만 있는 버튼에는 aria-label을 붙인다 -->
  <nav aria-label="페이지 이동" class="flex items-center justify-center gap-1">
    <button
      type="button"
      aria-label="이전 페이지"
      class="flex size-9 items-center justify-center rounded-full text-ink-700 transition-colors hover:bg-white/60 disabled:cursor-not-allowed disabled:opacity-40 disabled:hover:bg-transparent"
      :disabled="currentPage <= 1"
      @click="emit('change', currentPage - 1)"
    >
      <BaseIcon name="chevron-left" :size="16" />
    </button>

    <template v-for="(item, index) in items" :key="item === 'gap' ? `gap-${index}` : item">
      <span v-if="item === 'gap'" class="flex size-9 items-center justify-center text-ink-500"
        >…</span
      >
      <button
        v-else
        type="button"
        class="size-9 rounded-full text-sm tabular-nums transition-colors"
        :class="item === currentPage ? 'bg-primary text-white' : 'text-ink-700 hover:bg-white/60'"
        :aria-current="item === currentPage ? 'page' : undefined"
        @click="emit('change', item)"
      >
        {{ item }}
      </button>
    </template>

    <button
      type="button"
      aria-label="다음 페이지"
      class="flex size-9 items-center justify-center rounded-full text-ink-700 transition-colors hover:bg-white/60 disabled:cursor-not-allowed disabled:opacity-40 disabled:hover:bg-transparent"
      :disabled="currentPage >= totalPages"
      @click="emit('change', currentPage + 1)"
    >
      <BaseIcon name="chevron-right" :size="16" />
    </button>
  </nav>
</template>
