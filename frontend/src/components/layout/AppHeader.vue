<script setup lang="ts">
import { RouterLink, useRoute } from 'vue-router'
import BaseIcon from '@/components/ui/BaseIcon.vue'
import type { IconName } from '@/components/ui/icon-names'

// 클릭했을 때 할 일은 AppShell이 정함
const emit = defineEmits<{ 'attach-click': [] }>()

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
    <!-- 로고: 얇은 테두리 알약 안에 Fraunces -->
    <RouterLink
      :to="{ name: 'inspect' }"
      class="rounded-full border border-ink-900/70 px-5 py-1.5 font-fraunces text-2xl text-ink-900 transition-colors hover:bg-white/50"
    >
      FoodGuard
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
      type="button"
      class="flex items-center gap-2 rounded-full bg-white/60 px-5 py-3.5 text-sm text-ink-900 transition-colors hover:bg-white"
      @click="emit('attach-click')"
    >
      <BaseIcon name="upload" :size="16" />
      상세페이지 이미지 첨부
    </button>
  </header>
</template>
