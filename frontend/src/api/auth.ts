import type { LoginResponse, User } from '@/types/auth'
import { request } from './http'

const ENDPOINTS = {
  signup: '/api/auth/signup', // POST SignupRequest → User (201)
  login: '/api/auth/login', // POST LoginRequest → LoginResponse
  me: '/api/users/me', // GET (토큰 필요) → User
  logout: '/api/auth/logout', // POST (Refresh Token 쿠키) → 204, 서버의 로그인 세션 삭제
}
// 갱신(POST /api/auth/refresh)은 모든 요청이 공유하는 동작이라 http.ts의 refreshSession에 있음

export interface SignupRequest {
  email: string
  password: string // 15~64자 (백엔드 SignupRequest와 같은 규칙)
  companyName?: string // 선택. 비워 두면 백엔드가 null로 저장
}

// 화면 컴포넌트는 이 인터페이스로만 API를 호출
export interface AuthApi {
  // 중복 이메일이면 status 409인 ApiError로 reject
  signup(req: SignupRequest): Promise<User>
  // 이메일·비밀번호가 틀리면 status 401인 ApiError로 reject (세션 만료로 처리하지 않음)
  login(email: string, password: string): Promise<LoginResponse>
  me(): Promise<User>
  logout(): Promise<void>
}

export const authApi: AuthApi = {
  signup(req) {
    return request<User>(ENDPOINTS.signup, { method: 'POST', body: req, auth: false })
  },
  login(email, password) {
    return request<LoginResponse>(ENDPOINTS.login, {
      method: 'POST',
      body: { email, password },
      auth: false,
    })
  },
  me() {
    return request<User>(ENDPOINTS.me)
  },
  logout() {
    return request<void>(ENDPOINTS.logout, { method: 'POST', auth: false })
  },
}
