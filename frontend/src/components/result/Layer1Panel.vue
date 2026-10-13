<script setup lang="ts">
import { computed } from 'vue'
import BaseIcon from '@/components/ui/BaseIcon.vue'
import type { Layer1Result } from '@/types/inspection'

const props = defineProps<{
  layer1: Layer1Result
}>()

const missingItems = computed(() => props.layer1.items.filter((item) => !item.found))
const foundCount = computed(() => props.layer1.items.length - missingItems.value.length)
</script>

<template>
  <!-- 차콜 체크리스트 카드. 포함된 문구는 노란 체크, 빠진 문구는 빨간 × 로 표시한다 -->
  <section
    aria-labelledby="layer1-title"
    class="flex flex-col gap-5 rounded-[1.75rem] bg-primary p-6 text-white shadow-[0_12px_32px_-16px_rgb(0_0_0/0.4)]"
  >
    <div class="flex items-baseline justify-between gap-4">
      <h2 id="layer1-title" class="text-xl">필수 문구 확인</h2>
      <p v-if="layer1.items.length > 0" class="font-heading text-4xl font-light tabular-nums">
        <span class="sr-only"
          >필수 문구 {{ layer1.items.length }}개 중 {{ foundCount }}개 포함</span
        >
        <span aria-hidden="true">{{ foundCount }}/{{ layer1.items.length }}</span>
      </p>
    </div>

    <p
      v-if="layer1.appliedProductType === 'UNDETERMINED'"
      class="flex items-start gap-2 text-sm text-white/70"
    >
      <BaseIcon name="info" :size="16" class="mt-0.5 shrink-0" />
      제품 종류를 특정하지 못해 일반식품 기준으로 검사했습니다.
    </p>

    <!-- items가 비어 있으면 allPassed도 true지만, "모두 포함"은 오해를 줄 수 있어 따로 안내한다 -->
    <p v-if="layer1.items.length === 0" class="text-sm text-white/80">
      이 제품 종류에는 확인할 필수 문구가 없습니다.
    </p>
    <p v-else-if="layer1.allPassed" class="text-sm text-accent">필수 문구 모두 포함</p>
    <p v-else class="text-sm text-red-300">
      빠진 필수 문구가 있습니다 ({{ missingItems.length }}개)
    </p>

    <ul v-if="layer1.items.length > 0" class="flex flex-col gap-2">
      <li
        v-for="item in layer1.items"
        :key="item.ruleId"
        class="flex items-center justify-between gap-3 rounded-2xl bg-white/[0.06] px-4 py-3 text-sm"
      >
        <span>
          <span class="sr-only">{{ item.found ? '포함: ' : '누락: ' }}</span
          >{{ item.label }}
        </span>
        <span
          v-if="item.found"
          class="flex size-6 shrink-0 items-center justify-center rounded-full bg-accent text-ink-900"
        >
          <BaseIcon name="check" :size="14" />
        </span>
        <span
          v-else
          class="flex size-6 shrink-0 items-center justify-center rounded-full bg-red-400/20 text-red-300"
        >
          <BaseIcon name="x" :size="14" />
        </span>
      </li>
    </ul>
  </section>
</template>
