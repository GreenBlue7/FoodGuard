<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import FindingCard from '@/components/result/FindingCard.vue'
import HighlightedText from '@/components/result/HighlightedText.vue'
import ResultFooter from '@/components/result/ResultFooter.vue'
import BaseIcon from '@/components/ui/BaseIcon.vue'
import type { Finding, Layer2Result, Sentence } from '@/types/inspection'
import { VERDICT_SEVERITY } from '@/utils/verdict'

const { layer2, adText } = defineProps<{
  layer2: Layer2Result
  adText: string // 검사한 원문 전체 (job.request.adText)
}>()

// VIOLATION → REVIEW → HOLD → OK 순. props 배열을 바꾸지 않도록 복사해서 정렬한다.
// sort는 ES2019부터 안정 정렬이라 같은 판정끼리는 받은 순서를 지킨다.
const sortedFindings = computed(() =>
  [...layer2.findings].sort((a, b) => VERDICT_SEVERITY[b.verdict] - VERDICT_SEVERITY[a.verdict]),
)
// 7a: 위반·검토 필요가 하나도 없으면 카드 목록 대신 안내만 보여 준다
const hasIssues = computed(() => layer2.findings.some((finding) => finding.verdict !== 'OK'))

const sentenceByIndex = computed(
  () => new Map(layer2.sentences.map((sentence) => [sentence.index, sentence])),
)
function sentencesOf(finding: Finding): Sentence[] {
  return finding.sentenceIndexes
    .map((index) => sentenceByIndex.value.get(index))
    .filter((sentence): sentence is Sentence => sentence !== undefined)
}

// 카드를 누르면 왼쪽 원문에서 해당 문장(들)을 잠깐 테두리로 강조한다. 스크롤은 하지 않는다.
const EMPHASIS_MS = 2000
const emphasized = ref<number[]>([])
let emphasisTimer: ReturnType<typeof setTimeout> | undefined

function emphasize(indexes: number[]) {
  clearTimeout(emphasisTimer)
  emphasized.value = [...indexes]
  emphasisTimer = setTimeout(() => {
    emphasized.value = []
  }, EMPHASIS_MS)
}
onBeforeUnmount(() => clearTimeout(emphasisTimer))
</script>

<template>
  <section aria-labelledby="layer2-title" class="flex flex-col gap-5">
    <h2 id="layer2-title" class="text-2xl text-ink-900">문장별 판정</h2>

    <!-- 두 열 모두 "제목 + 상자" 구조로 맞춰 상자 윗선을 같은 높이에 둔다.
         왼쪽 원문은 카드 목록을 스크롤해도 따라오도록 sticky로 둔다.
         원문이 화면보다 길면 전체를 볼 수 없으므로 높이를 화면 안으로 제한하고 안쪽에서 스크롤한다. -->
    <div class="grid grid-cols-2 items-start gap-5">
      <div class="sticky top-6 space-y-3">
        <h3 class="px-1 text-sm text-ink-700">검사한 원문</h3>
        <div class="card max-h-[calc(100dvh-6rem)] overflow-y-auto p-6">
          <HighlightedText
            :text="adText"
            :sentences="layer2.sentences"
            :findings="layer2.findings"
            :emphasized="emphasized"
          />
        </div>
      </div>

      <div class="space-y-3">
        <h3 class="px-1 text-sm text-ink-700">
          {{ hasIssues ? `판정 ${sortedFindings.length}건` : '판정 결과' }}
        </h3>
        <div v-if="hasIssues" class="space-y-4">
          <FindingCard
            v-for="finding in sortedFindings"
            :key="finding.id"
            :finding="finding"
            :sentences="sentencesOf(finding)"
            @select="emphasize(finding.sentenceIndexes)"
          />
        </div>
        <p
          v-else
          class="flex items-center gap-2 rounded-[1.75rem] border border-emerald-200 bg-emerald-50 px-6 py-5 text-sm text-emerald-700"
        >
          <BaseIcon name="check" :size="16" />
          검토 필요 항목 없음
        </p>
      </div>
    </div>

    <ResultFooter :data-as-of="layer2.dataAsOf" :model="layer2.model" />
  </section>
</template>
