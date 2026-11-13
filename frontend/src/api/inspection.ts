import type {
  InspectionJob,
  InspectionRequest,
  Product,
  InspectionHistoryPage,
} from '@/types/inspection'
import { request } from './http'

// 모두 상대 경로로 부르고, 백엔드 주소는 코드에 두지 않음
// 개발 서버는 vite.config.ts의 프록시가, 운영 환경은 nginx가 /api를 백엔드로 넘긴다
const ENDPOINTS = {
  products: '/api/products', // GET ?query= --> Product[]
  // POST InspectionRequest --> { jobId }
  // GET ?page=&size= --> InspectionHistoryPage
  inspections: '/api/inspections',
  inspection: (jobId: string) => `/api/inspections/${encodeURIComponent(jobId)}`, // GET → InspectionJob
}

// 화면 컴포넌트는 이 인터페이스로만 API를 호출
export interface InspectionApi {
  searchProducts(query: string): Promise<Product[]>
  createInspection(req: InspectionRequest): Promise<{ jobId: string }>
  // 없는 jobId면 NotFoundError(./errors)로 reject
  getJob(jobId: string): Promise<InspectionJob>
  // page는 0부터, 백엔드에서 음수는 0, size는 1-50으로 보정
  getHistory(page: number, size: number): Promise<InspectionHistoryPage>
}

export const inspectionApi: InspectionApi = {
  searchProducts(query) {
    return request<Product[]>(`${ENDPOINTS.products}?${new URLSearchParams({ query })}`)
  },
  createInspection(req) {
    return request<{ jobId: string }>(ENDPOINTS.inspections, { method: 'POST', body: req })
  },
  getJob(jobId) {
    return request<InspectionJob>(ENDPOINTS.inspection(jobId))
  },
  getHistory(page, size) {
    const query = new URLSearchParams({ page: String(page), size: String(size) })
    return request<InspectionHistoryPage>(`${ENDPOINTS.inspections}?${query}`)
  },
}
