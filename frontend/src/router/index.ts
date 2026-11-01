import { createRouter, createWebHistory } from 'vue-router'
import InspectView from '@/views/InspectView.vue'
import InspectionResultView from '@/views/InspectionResultView.vue'
import { refreshSession } from '@/api/http'
import { useAuthStore } from '@/stores/auth'

declare module 'vue-router' {
  interface RouteMeta {
    // true: 로그인 없이 들어올 수 있는 화면 (로그인·회원가입). 나머지는 모두 로그인 필요
    public?: boolean
  }
}

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    { path: '/', name: 'inspect', component: InspectView },
    {
      path: '/inspections/:jobId',
      name: 'inspection-result',
      component: InspectionResultView,
      props: true, // jobId를 prop으로 받아 라우터 객체에 덜 의존하게
    },
    {
      path: '/login',
      name: 'login',
      component: () => import('@/views/LoginView.vue'),
      meta: { public: true },
    },
    {
      path: '/signup',
      name: 'signup',
      component: () => import('@/views/SignupView.vue'),
      meta: { public: true },
    },
    // 그 외 경로는 모두 첫 화면으로
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
})

// 화면 이동 전에 로그인 여부를 확인
// 프론트의 확인은 안내일 뿐이고, 실제 접근 통제는 서버가 모든 API에서 토큰으로 함
// 새로고침 직후에는 메모리가 비어 있으므로, 첫 화면 이동 때 쿠키로 로그인 상태를 한 번 복원함
// (세션 방식의 kiroku가 첫 이동 때 /me를 기다리는 것과 같은 자리)
let restored: Promise<boolean> | null = null

router.beforeEach(async (to) => {
  const authStore = useAuthStore()
  restored ??= refreshSession()
  await restored
  // 화면을 오래 열어 둬서 Access Token이 만료됐으면 이동 전에 갱신
  if (authStore.accessToken !== null && !authStore.hasValidToken()) await refreshSession()
  const loggedIn = authStore.hasValidToken()

  if (!to.meta.public && !loggedIn) {
    // 로그인 후 원래 가려던 화면으로 돌려보내려고 주소를 기억
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  if (to.meta.public && loggedIn) return { name: 'inspect' }
})

// 로그인 후 이동할 주소. ?redirect= 값은 누구나 링크에 넣을 수 있으므로 앱 내부 경로만 허용
// (외부 주소를 허용하면 로그인 직후 피싱 사이트로 보내는 오픈 리다이렉트가 됨)
export function safeRedirect(value: unknown): string {
  if (typeof value !== 'string') return '/'
  // "//evil.com", "/\evil.com"은 브라우저가 다른 사이트 주소로 해석함
  if (!value.startsWith('/') || value.startsWith('//') || value.startsWith('/\\')) return '/'
  const target = router.resolve(value)
  // 로그인 화면으로 다시 보내면 끝없이 반복되므로 제외. 없는 경로는 라우터가 첫 화면으로 보냄
  if (target.meta.public) return '/'
  return target.fullPath
}

export default router
