import { createRouter, createWebHistory } from 'vue-router'
import InspectView from '@/views/InspectView.vue'
import InspectionResultView from '@/views/InspectionResultView.vue'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    { path: '/', name: 'inspect', component: InspectView },
    {
      path: '/inspections/:jobId',
      name: 'inspection-result',
      component: InspectionResultView,
      props: true, // jobId를 prop으로 받아 라우터 객체에 덜 의존하게 한다
    },
    // 그 외 경로는 모두 첫 화면으로
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
})

export default router
