import { ref } from 'vue'
import { defineStore } from 'pinia'
import type { LoginResponse, User } from '@/types/auth'

// 만료 직전 토큰은 요청이 서버에 닿는 사이에 만료될 수 있어서 30초 일찍 만료로 봄
const EXPIRY_MARGIN_MS = 30_000

export const useAuthStore = defineStore('auth', () => {
  // Access Token은 메모리에만 둠. localStorage에 두면 페이지에 끼어든 스크립트(XSS)가 읽어 갈 수 있음
  // 새로고침하면 사라지지만, JS가 읽을 수 없는 HttpOnly 쿠키의 Refresh Token으로 다시 받음
  // (http.ts의 refreshSession, 라우터 가드가 첫 화면 이동 때 호출)
  const accessToken = ref<string | null>(null)
  const expiresAt = ref<number | null>(null) // 만료 시각 (ms, Date.now() 기준)
  // 헤더에 보여줄 내 정보. 토큰에는 id·role만 있어서 GET /api/users/me로 따로 받음
  // 받아오는 일은 헤더가 함 (스토어가 api를 import하면 store → api → http → store로 순환)
  const user = ref<User | null>(null)

  // computed가 아닌 함수인 이유: Date.now()는 반응형이 아니라서
  // computed로 만들면 시간이 지나도 다시 계산되지 않음. 부를 때마다 현재 시각으로 판단
  function hasValidToken(): boolean {
    return (
      accessToken.value !== null &&
      expiresAt.value !== null &&
      Date.now() < expiresAt.value - EXPIRY_MARGIN_MS
    )
  }

  // 로그인·갱신 성공 시. expiresIn(초)으로 만료 시각을 계산해 둠
  function setSession(response: LoginResponse) {
    accessToken.value = response.accessToken
    expiresAt.value = Date.now() + response.expiresIn * 1000
  }

  function setUser(value: User) {
    user.value = value
  }

  // 로그아웃, 갱신 실패 시
  function clearSession() {
    accessToken.value = null
    expiresAt.value = null
    user.value = null
  }

  return { accessToken, user, hasValidToken, setSession, setUser, clearSession }
})
