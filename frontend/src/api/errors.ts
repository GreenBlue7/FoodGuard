// 백엔드 API 호출이 실패했을 때 던짐
// status는 응답의 HTTP 상태 코드, 응답을 아예 받지 못한 경우(네트워크 오류)는 null
export class ApiError extends Error {
  readonly status: number | null

  constructor(message: string, status: number | null, options?: ErrorOptions) {
    super(message, options)
    this.name = 'ApiError'
    this.status = status
  }
}

// 로그인한 상태로 보낸 요청에 서버가 401을 줬을 때 던짐 (토큰 만료, 계정 삭제 등)
// 로그인 실패(틀린 비밀번호)의 401은 이것이 아니라 일반 ApiError로 옴
export class SessionExpiredError extends ApiError {
  constructor(message: string, options?: ErrorOptions) {
    super(message, 401, options)
    this.name = 'SessionExpiredError'
  }
}

// 요청한 대상이 없을 때(404) 던짐
// 결과 화면이 "작업을 찾을 수 없습니다"를 다른 오류와 구분하는 데 사용
export class NotFoundError extends ApiError {
  constructor(message: string, options?: ErrorOptions) {
    super(message, 404, options)
    this.name = 'NotFoundError'
  }
}
