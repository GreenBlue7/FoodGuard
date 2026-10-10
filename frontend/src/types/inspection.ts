// 제품 종류. UNDETERMINED는 2a 흐름의 "판단 보류"
export type ProductType = 'HEALTH_FUNCTIONAL' | 'FUNCTIONAL_GENERAL' | 'GENERAL' | 'UNDETERMINED'
export type ProductStatus = 'ACTIVE' | 'DISCONTINUED' | 'EXPORT_ONLY'

export interface Product {
  id: string
  name: string
  manufacturer: string
  reportNo: string | null // 신고번호. 일반식품은 없을 수 있음
  type: ProductType
  approvedFunctions: string[] // 식약처 인정 기능성. 건기식이 아니면 빈 배열
  status: ProductStatus
}

// 2a 흐름에서 사용자가 고른 답
export type ProductTypeAnswer = 'HEALTH_FUNCTIONAL' | 'FUNCTIONAL_GENERAL' | 'GENERAL' | 'UNKNOWN'

export interface InspectionRequest {
  productIds: string[] // 후보 없음 흐름이면 빈 배열
  productTypeAnswer?: ProductTypeAnswer
  adText: string
}

export type JobStatus = 'PENDING' | 'RUNNING' | 'DONE' | 'FAILED' | 'TIMEOUT'

// 제품 종류를 어디서 알았는지
// - MFDS: 식약처 데이터 (제품을 선택함)
// - USER_DECLARED: 2a에서 판매자가 "일반식품"이라고 답함 → 일반식품 기준으로 검사
//   (화면: "판매자가 선택한 제품 종류(일반식품)로 검사했습니다")
// - UNVERIFIED: 확인 못 함(판단 보류). 2a 답이 모름 / 기능성 표시 일반식품 / 신고번호 없는 건기식
//   (화면: "제품 정보를 확인하지 못해 제품 종류를 특정하지 않고 검사했습니다")
export type ProductInfoSource = 'MFDS' | 'USER_DECLARED' | 'UNVERIFIED'

export interface InspectionJob {
  jobId: string
  status: JobStatus
  createdAt: string // ISO 8601
  request: InspectionRequest // 검사에 쓴 요청. 결과 화면의 원문 표시와 재시도에 사용
  productInfoSource: ProductInfoSource
  layer1: Layer1Result | null // RUNNING 중에도 올 수 있음
  layer2: Layer2Result | null // DONE일 때만
}

// 원문 안의 위치. 단위는 유니코드 코드 포인트, end는 미포함(exclusive)
export interface TextRange {
  start: number
  end: number
}

export interface Layer1Item {
  ruleId: string
  label: string // 예: "의약품 아님 고지 문구"
  found: boolean
  range: TextRange | null // found가 true일 때 원문 위치
}

export interface Layer1Result {
  appliedProductType: ProductType // 어떤 규칙 세트를 적용했는지
  // 빈 배열이면 확인할 필수 문구가 없는 제품 종류
  // (화면: "모두 포함" 대신 "이 제품 종류에는 확인할 필수 문구가 없습니다")
  items: Layer1Item[]
  allPassed: boolean
}

export interface Sentence {
  index: number
  text: string
  range: TextRange
  isMandatory: boolean // 의무 문구라서 판정 대상에서 제외됨
}

export type Verdict = 'VIOLATION' | 'REVIEW' | 'OK'

export interface LawRef {
  id: string
  title: string // 예: "식품 등의 표시·광고에 관한 법률"
  article: string // 예: "제8조 제1항 제1호"
  text: string // 조문 요지
}

export interface CaseRef {
  id: string
  year: number
  excerpt: string // 적발된 문구
  source: string // 출처 (예: "식약처 보도자료 2024-03")
}

export interface Finding {
  id: string
  verdict: Verdict
  sentenceIndexes: number[] // 길이 2 이상이면 여러 문장에 걸친 위반
  reason: string
  lawRefs: LawRef[]
  cases: CaseRef[] // 비어 있을 수 있음
  isTestimonial: boolean // 후기 형식 문장 여부
}

export interface Layer2Result {
  sentences: Sentence[]
  findings: Finding[]
  model: string // 예: "gemini-2.5-pro"
  dataAsOf: string // 판단 기준일 YYYY-MM-DD
  judgedAt: string // ISO 8601
}
