import { ApiError } from '@/api/errors'

// 백엔드는 에러 본문에 메시지를 담지 않으므로(Spring 기본 설정) 상태 코드로 문구를 정함
// 문구는 백엔드 AuthService의 메시지와 맞춤

const NETWORK_MESSAGE = '서버에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요.'

export function loginErrorMessage(error: unknown): string {
  if (!(error instanceof ApiError)) return '로그인하지 못했습니다. 잠시 후 다시 시도해 주세요.'
  if (error.status === null) return NETWORK_MESSAGE
  // 없는 이메일과 틀린 비밀번호를 서버가 구분하지 않으므로 화면도 구분하지 않음
  if (error.status === 401) return '이메일 또는 비밀번호가 올바르지 않습니다.'
  if (error.status === 400) return '이메일과 비밀번호를 입력해 주세요.'
  return '로그인하지 못했습니다. 잠시 후 다시 시도해 주세요.'
}

export function signupErrorMessage(error: unknown): string {
  if (!(error instanceof ApiError)) return '회원가입하지 못했습니다. 잠시 후 다시 시도해 주세요.'
  if (error.status === null) return NETWORK_MESSAGE
  if (error.status === 409) return '이미 가입된 이메일입니다.'
  if (error.status === 400) return '입력한 내용을 다시 확인해 주세요.'
  return '회원가입하지 못했습니다. 잠시 후 다시 시도해 주세요.'
}
