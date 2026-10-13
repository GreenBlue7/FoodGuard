<script setup lang="ts">
import { RouterLink, useRoute } from 'vue-router'
import BaseIcon from '@/components/ui/BaseIcon.vue'
import type { IconName } from '@/components/ui/icon-names'

// 클릭했을 때 할 일은 AppShell이 정함
const emit = defineEmits<{ 'attach-click': [] }>()

// 이미지 업로드는 기말 범위라 중간 발표에서는 버튼을 숨김. 기능을 붙일 때 true로 변경
const SHOW_IMAGE_ATTACH = false

// 메뉴 항목 하나의 모양
interface NavItem {
  to: string // 이동할 라우트 이름
  label: string
  icon: IconName
  activeOn: string[] // 이 항목을 강조할 라우트 이름들 (하위 페이지 포함)
}

const items: NavItem[] = [
  {
    to: 'inspect',
    label: '광고 문구 검사',
    icon: 'shield',
    activeOn: ['inspect', 'inspection-result'],
  },
]

const route = useRoute()

function isActive(item: NavItem) {
  return item.activeOn.includes(route.name?.toString() ?? '')
}
</script>

<template>
  <header class="flex items-center gap-3 py-6">
    <!-- 로고: 노란 원 마크 + Fraunces 워드마크 + 한 줄 설명
         마크는 화면 곳곳의 노란 원(선택 체크, 화살표 버튼)과 같은 모양으로 맞춤
         'Guard'만 이탤릭으로 바꿔 한 단어 안에서 리듬을 줌 -->
    <RouterLink :to="{ name: 'inspect' }" class="group flex items-center gap-3 text-ink-900">
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
    </RouterLink>

    <!-- 메뉴: 반투명 알약 묶음. 가운데 클릭·새 탭 열기가 되도록 버튼이 아닌 링크로 -->
    <nav
      aria-label="주 메뉴"
      class="ml-auto flex items-center gap-1 rounded-full bg-white/60 p-1.5"
    >
      <RouterLink
        v-for="item in items"
        :key="item.to"
        :to="{ name: item.to }"
        class="flex items-center gap-2 rounded-full px-4 py-2 text-sm transition-colors"
        :class="
          isActive(item)
            ? 'bg-primary text-white'
            : 'text-ink-700 hover:bg-white hover:text-ink-900'
        "
      >
        <BaseIcon :name="item.icon" :size="16" />
        {{ item.label }}
      </RouterLink>
    </nav>

    <!-- 상세페이지 이미지 첨부: 아직 업로드 API가 없어 버튼만 두고 이벤트만 올림 -->
    <button
      v-if="SHOW_IMAGE_ATTACH"
      type="button"
      class="flex items-center gap-2 rounded-full bg-white/60 px-5 py-3.5 text-sm text-ink-900 transition-colors hover:bg-white"
      @click="emit('attach-click')"
    >
      <BaseIcon name="upload" :size="16" />
      상세페이지 이미지 첨부
    </button>
  </header>
</template>
