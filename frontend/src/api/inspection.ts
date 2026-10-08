import type { InspectionJob, InspectionRequest, Product } from '@/types/inspection'
import { request } from './http'

// 모두 상대 경로로 부르고, 백엔드 주소는 코드에 두지 않음
// 개발 서버는 vite.config.ts의 프록시가, 운영 환경은 nginx가 /api를 백엔드로 넘긴다
const ENDPOINTS = {
  products: '/api/products', // GET ?query= → Product[]
  inspections: '/api/inspections', // POST InspectionRequest → { jobId }
  inspection: (jobId: string) => `/api/inspections/${encodeURIComponent(jobId)}`, // GET → InspectionJob
}

// 화면 컴포넌트는 이 인터페이스로만 API를 호출
export interface InspectionApi {
  searchProducts(query: string): Promise<Product[]>
  createInspection(req: InspectionRequest): Promise<{ jobId: string }>
  // 없는 jobId면 NotFoundError(./errors)로 reject
  getJob(jobId: string): Promise<InspectionJob>
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
}
