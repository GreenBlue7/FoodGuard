import { useAuthStore } from '@/stores/auth'
import type { LoginResponse } from '@/types/auth'
import { ApiError, NotFoundError, SessionExpiredError } from './errors'

interface RequestOptions {
  method?: 'GET' | 'POST'
  body?: unknown // JSON으로 바꿔 보냄
  // false: 로그인·회원가입·갱신처럼 Access Token 없이 보내는 요청. 401이 와도 세션 만료로 보지 않음
  auth?: boolean
  retried?: boolean // 내부용: 갱신 후 다시 보낸 요청이면 true (한 번만 재시도)
}

// 세션이 만료됐을 때 할 일 (로그인 화면으로 이동 등)
// http.ts가 라우터를 직접 import하면 router → 화면 → api → http → router로 순환하므로,
// 앱 시작 시 바깥(main.ts)에서 등록하게 함
let sessionExpiredHandler: () => void = () => {}

export function onSessionExpired(handler: () => void) {
  sessionExpiredHandler = handler
}

// 진행 중인 갱신. 여러 요청이 동시에 401을 받아도 갱신 요청은 하나만 나가고 결과를 같이 기다림
// (Refresh Token은 한 번 쓰면 바뀌므로, 같은 쿠키로 여러 번 갱신하면 서버가 재사용 공격으로 볼 수 있음)
let refreshing: Promise<boolean> | null = null

// HttpOnly 쿠키의 Refresh Token으로 새 Access Token을 받음. 성공하면 true
// 쿠키는 JS가 읽을 수 없지만, 같은 사이트로 보내는 요청에는 브라우저가 알아서 실어 보냄
export function refreshSession(): Promise<boolean> {
  refreshing ??= (async () => {
    const authStore = useAuthStore()
    try {
      authStore.setSession(
        await request<LoginResponse>('/api/auth/refresh', { method: 'POST', auth: false }),
      )
      return true
    } catch (error) {
      // 쿠키가 없거나 만료·폐기됨. 네트워크 오류일 때는 남은 상태를 건드리지 않음
      if (error instanceof ApiError && error.status === 401) authStore.clearSession()
      return false
    } finally {
      refreshing = null
    }
  })()
  return refreshing
}

// 갱신까지 실패: 세션을 지우고 등록된 할 일(로그인 화면으로 이동)을 실행
function expireSession(method: string, path: string): never {
  useAuthStore().clearSession()
  sessionExpiredHandler()
  throw new SessionExpiredError(`로그인이 만료되었습니다: ${method} ${path}`)
}

// 백엔드 API 공통 요청 함수
// 2xx이면서 본문이 JSON인 응답만 성공으로 보고 파싱해 반환
// 204 → undefined
// 404 → NotFoundError
// 로그인 상태에서 보낸 요청의 401 → 한 번 갱신해서 다시 보냄. 갱신도 실패하면 SessionExpiredError
// 그 밖의 2xx가 아닌 응답, 네트워크 오류, JSON이 아닌 응답 → ApiError
export async function request<T>(
  path: string,
  { method = 'GET', body, auth = true, retried = false }: RequestOptions = {},
): Promise<T> {
  const headers = new Headers({ Accept: 'application/json' })
  if (body !== undefined) headers.set('Content-Type', 'application/json')

  // 요청 전에 판단해 둠: 401이 왔을 때 "로그인 상태였는가"로 세션 만료 여부를 가름
  const authStore = useAuthStore()
  const hadSession = auth && authStore.accessToken !== null
  // 이미 만료된 Access Token은 보내 봐야 401이므로 먼저 갱신
  if (hadSession && !authStore.hasValidToken() && !(await refreshSession())) {
    return expireSession(method, path)
  }
  if (hadSession) headers.set('Authorization', `Bearer ${authStore.accessToken}`)

  let response: Response
  try {
    response = await fetch(path, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
    })
  } catch (error) {
    // 서버에 닿지 못함 (네트워크 끊김, 백엔드가 꺼져 있음 등)
    throw new ApiError(`서버에 연결하지 못했습니다: ${method} ${path}`, null, { cause: error })
  }

  if (response.status === 401 && hadSession) {
    // 서버가 토큰을 거부함 (만료 직후, 비밀키 변경 등). 한 번만 갱신해서 다시 보냄
    if (!retried && (await refreshSession())) {
      return request<T>(path, { method, body, auth, retried: true })
    }
    return expireSession(method, path)
  }
  // 본문 없는 성공 응답 (로그아웃 등)
  if (response.status === 204) return undefined as T
  if (response.status === 404) throw new NotFoundError(`찾을 수 없습니다: ${method} ${path}`)
  if (!response.ok) {
    throw new ApiError(
      `요청이 실패했습니다 (${response.status}): ${method} ${path}`,
      response.status,
    )
  }

  // 백엔드 대신 HTML 오류 페이지 등이 돌아온 경우를 걸러냄
  const contentType = response.headers.get('Content-Type') ?? ''
  if (!contentType.includes('application/json')) {
    throw new ApiError(
      `JSON이 아닌 응답입니다 (${contentType || '형식 없음'}): ${method} ${path}`,
      response.status,
    )
  }
  try {
    return (await response.json()) as T
  } catch (error) {
    throw new ApiError(`응답 JSON을 읽지 못했습니다: ${method} ${path}`, response.status, {
      cause: error,
    })
  }
}
