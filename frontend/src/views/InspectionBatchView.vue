<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import InspectionTicket from '@/components/inspect/InspectionTicket.vue'

const route = useRoute()
const ids = computed(() => {
  const value = route.query.jobs
  if (typeof value !== 'string' || !/^\d+(,\d+){0,2}$/.test(value)) return []
  return value.split(',')
})
</script>

<template>
  <div class="space-y-6">
    <h1 class="text-4xl text-ink-900">검사 접수 현황</h1>
    <p class="text-sm text-ink-700">
      각 번호표를 차례로 검사하며 완료된 결과부터 확인할 수 있습니다. 한 항목이 실패해도 나머지
      검사는 계속됩니다.
    </p>
    <p class="text-xs text-ink-600">
      진행 중인 동일 요청은 같은 번호표를 재사용합니다. 화면을 닫아도 접수된 검사는 유지되며, 검수
      이력에서 다시 확인할 수 있습니다.
    </p>
    <ol v-if="ids.length" class="space-y-4" aria-label="접수한 검사 번호표">
      <InspectionTicket
        v-for="(id, index) in ids"
        :key="`${index}:${id}`"
        :job-id="id"
        :index="index"
      />
    </ol>
    <p v-else role="alert" class="text-sm text-red-700">조회할 번호표를 확인해 주세요.</p>
    <div class="flex gap-6 text-sm underline">
      <RouterLink :to="{ name: 'history' }">검수 이력 보기</RouterLink>
      <RouterLink :to="{ name: 'inspect' }">새 검사 작성</RouterLink>
    </div>
  </div>
</template>
