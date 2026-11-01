// 백엔드 POST /api/auth/login 응답 (LoginResponse.java)
export interface LoginResponse {
  accessToken: string
  tokenType: 'Bearer'
  expiresIn: number // 토큰이 만료될 때까지 남은 초
}

// 백엔드 UserResponse.java. 아래 두 API가 같은 모양으로 응답함 (비밀번호는 오지 않음)
// - POST /api/auth/signup : 회원가입 (201)
// - GET  /api/users/me    : 내 정보
export interface User {
  id: number
  email: string
  companyName: string | null
  createdAt: string
}
