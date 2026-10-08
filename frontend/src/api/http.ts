import { ApiError, NotFoundError } from './errors'

interface RequestOptions {
  method?: 'GET' | 'POST'
  body?: unknown // JSON으로 바꿔 보냄
}

// 백엔드 API 공통 요청 함수
// 2xx이면서 본문이 JSON인 응답만 성공으로 보고 파싱해 반환
// 404 → NotFoundError
// 그 밖의 2xx가 아닌 응답, 네트워크 오류, JSON이 아닌 응답 → ApiError
export async function request<T>(
  path: string,
  { method = 'GET', body }: RequestOptions = {},
): Promise<T> {
  const headers = new Headers({ Accept: 'application/json' })
  if (body !== undefined) headers.set('Content-Type', 'application/json')

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
