<script setup lang="ts">
import BaseIcon from '@/components/ui/BaseIcon.vue'

// md: 헤더 한 줄 배치 / lg: 로그인·회원가입 화면 위쪽의 큰 세로 배치
withDefaults(defineProps<{ size?: 'md' | 'lg' }>(), { size: 'md' })
</script>

<template>
  <!-- 로고: 노란 원 마크 + Fraunces 워드마크 + 한 줄 설명
       마크는 화면 곳곳의 노란 원(선택 체크, 화살표 버튼)과 같은 모양으로 맞춤
       'Guard'만 이탤릭으로 바꿔 한 단어 안에서 리듬을 줌
       마크 회전은 부모에 group 클래스가 있을 때만 동작 (헤더의 링크) -->
  <span v-if="size === 'md'" class="flex items-center gap-3 text-ink-900">
    <span
      class="flex size-10 shrink-0 items-center justify-center rounded-full bg-accent shadow-sm transition-transform duration-300 group-hover:-rotate-12 motion-reduce:transition-none"
    >
      <BaseIcon name="leaf" :size="20" />
    </span>
    <span class="font-fraunces text-[1.875rem] leading-none tracking-[-0.02em]">
      Food<span class="italic">Guard</span>
    </span>
    <span aria-hidden="true" class="h-6 w-px bg-ink-900/15" />
    <span class="text-sm text-ink-600">식품 광고 사전 점검</span>
  </span>

  <span v-else class="flex flex-col items-center gap-4 text-ink-900">
    <span class="flex items-center gap-4">
      <span
        class="flex size-16 shrink-0 items-center justify-center rounded-full bg-accent shadow-sm"
      >
        <BaseIcon name="leaf" :size="32" />
      </span>
      <span class="font-fraunces text-[3.25rem] leading-none tracking-[-0.02em]">
        Food<span class="italic">Guard</span>
      </span>
    </span>
    <span class="text-sm text-ink-600">식품 광고 사전 점검</span>
  </span>
</template>
