<script setup lang="ts">
import { onMounted } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { authApi } from '@/api/auth'
import BaseIcon from '@/components/ui/BaseIcon.vue'
import BrandLogo from './BrandLogo.vue'
import type { IconName } from '@/components/ui/icon-names'
import { useAuthStore } from '@/stores/auth'

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

const authStore = useAuthStore()

// 헤더는 로그인한 화면에서만 나타나므로 여기서 내 정보를 한 번 받아 둠
// 로그인 화면에서 돌아오면 헤더가 새로 만들어져 다시 받음
onMounted(async () => {
  if (authStore.user || !authStore.hasValidToken()) return
  try {
    authStore.setUser(await authApi.me())
  } catch {
    // 401이면 http.ts가 이미 로그인 화면으로 보냄. 그 밖의 실패는 이메일만 비워 둠
  }
})

// 서버의 로그인 세션(Refresh Token)을 지우고 쿠키도 지움. 남은 Access Token은 짧은 수명이 지나면 끝남
// 화면 이동 대신 페이지를 새로 불러서, 메모리에 남은 이전 사용자의 입력(검사 중이던 문구 등)도 지움
async function logout() {
  try {
    await authApi.logout()
  } catch {
    // 서버에 알리지 못해도 이 브라우저에서는 로그아웃함
  }
  authStore.clearSession()
  window.location.assign('/login')
}
</script>

<template>
  <header class="flex items-center gap-3 py-6">
    <!-- 로고 모양은 BrandLogo에 있음. 링크의 group 클래스가 마크 회전 효과를 켬 -->
    <RouterLink :to="{ name: 'inspect' }" class="group">
      <BrandLogo />
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

    <!-- 로그인한 계정과 로그아웃 -->
    <div class="flex items-center gap-3">
      <span v-if="authStore.user" class="max-w-56 truncate text-sm text-ink-600">
        {{ authStore.user.email }}
      </span>
      <button
        type="button"
        class="rounded-full bg-white/60 px-5 py-3.5 text-sm text-ink-900 transition-colors hover:bg-white"
        @click="logout"
      >
        로그아웃
      </button>
    </div>
  </header>
</template>
