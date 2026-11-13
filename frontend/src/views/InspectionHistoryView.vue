<script setup lang="ts">
import { RouterLink } from 'vue-router'
import PaginationNav from '@/components/inspection-history/PaginationNav.vue'
import JobStatusBadge from '@/components/result/JobStatusBadge.vue'
import { useInspectionHistory } from '@/composables/useInspectionHistory'

const { items, totalElements, totalPages, currentPage, status, goToPage } = useInspectionHistory()

// 서버의 createdAt은 시간대 정보가 없는 LocalDateTime 문자열 (예: "2026-11-08T14:03:12.123456")
// new Date()로 바꾸면 브라우저 시간대로 해석되므로, 앞 16자만 잘라 그대로 표시
// 백엔드가 시간대가 포함된 형식으로 바뀌면 이 함수만 교체
function formatCreatedAt(value: string): string {
  return value.slice(0, 16).replace('T', ' ')
}
</script>

<template>
  <div class="flex flex-col gap-8">
    <div class="flex items-end gap-4">
      <h1 class="text-5xl tracking-tight text-ink-900">검수 이력</h1>
      <p v-if="status === 'done'" class="mb-1 text-sm text-ink-700">총 {{ totalElements }}건</p>
    </div>

    <p
      v-if="status === 'error'"
      role="alert"
      class="rounded-2xl border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700"
    >
      검수 이력을 불러오지 못했습니다. 잠시 후 페이지를 새로고침해 주세요.
    </p>

    <!-- 첫 요청 중에는 보여 줄 목록이 없음. 페이지를 넘길 때는 아래 목록을 흐리게 남겨 둠 -->
    <p
      v-else-if="status === 'loading' && items.length === 0"
      role="status"
      class="text-sm text-ink-700"
    >
      불러오는 중…
    </p>

    <div
      v-else-if="status === 'done' && items.length === 0"
      class="card flex flex-col items-start gap-4 p-6"
    >
      <template v-if="currentPage === 1">
        <p class="text-ink-900">아직 검수한 기록이 없습니다.</p>
        <RouterLink
          :to="{ name: 'inspect' }"
          class="rounded-full bg-primary px-5 py-2.5 text-sm text-white transition-colors duration-200 hover:bg-black"
        >
          광고 문구 검사하러 가기
        </RouterLink>
      </template>
      <!-- 마지막 페이지보다 큰 번호로 들어온 경우 (/history?page=99) -->
      <template v-else>
        <p class="text-ink-900">이 페이지에는 기록이 없습니다.</p>
        <button
          type="button"
          class="rounded-full border border-black/10 bg-white px-5 py-2.5 text-sm text-ink-900 transition-colors duration-200 hover:border-black/20"
          @click="goToPage(1)"
        >
          첫 페이지로
        </button>
      </template>
    </div>

    <ul
      v-else
      class="card divide-y divide-black/5 overflow-hidden transition-opacity"
      :class="{ 'opacity-60': status === 'loading' }"
      :aria-busy="status === 'loading'"
    >
      <li v-for="item in items" :key="item.jobId">
        <!-- 링크로 만들어 ⌘+클릭으로 새 탭에서 결과를 열 수 있게 함 -->
        <RouterLink
          :to="{ name: 'inspection-result', params: { jobId: item.jobId } }"
          class="flex items-center gap-4 px-6 py-4 transition-colors hover:bg-white/60"
        >
          <JobStatusBadge :status="item.status" />
          <div class="flex min-w-0 flex-1 flex-col gap-0.5">
            <p class="truncate text-ink-900">{{ item.adTextPreview }}</p>
            <p class="truncate text-sm text-ink-600">
              {{ item.productName ?? '제품 선택 없이 검사' }}
            </p>
          </div>
          <span class="shrink-0 font-mono text-sm text-ink-600">
            {{ formatCreatedAt(item.createdAt) }}
          </span>
        </RouterLink>
      </li>
    </ul>

    <PaginationNav
      v-if="totalPages > 1"
      :current-page="currentPage"
      :total-pages="totalPages"
      @change="goToPage"
    />
  </div>
</template>
