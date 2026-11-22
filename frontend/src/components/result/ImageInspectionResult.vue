<script setup lang="ts">
import { computed, nextTick, onMounted, ref, shallowRef } from 'vue'
import { inspectionApi } from '@/api/inspection'
import type { ImageInspectionResult, ImageLocation } from '@/types/inspection'

const props = defineProps<{ jobId: string }>()
const result = shallowRef<ImageInspectionResult | null>(null)
const loadFailed = ref(false)
const selection = shallowRef<ImageLocation | null>(null)
const imagePages = ref<HTMLElement | null>(null)
const marks = {
  VIOLATION: { label: '위반', class: 'text-red-800 bg-red-50' },
  REVIEW: { label: '검토 필요', class: 'text-orange-800 bg-orange-50' },
  HOLD: { label: '판단 보류', class: 'text-blue-800 bg-blue-50' },
  APPROVED: { label: '인정 기능성 범위', class: 'text-emerald-800 bg-emerald-50' },
}

const findings = computed(() => {
  const groups = new Map<
    number,
    {
      number: number
      mark: keyof typeof marks
      text: string
      reason: string
      revision: string | null
      locations: ImageLocation[]
    }
  >()
  const sentences = new Map(result.value?.sentences.map(({ id, text }) => [id, text]))
  for (const finding of result.value?.results ?? []) {
    const { number, mark, locations, reason, revision } = finding
    if (number === null || mark === null) continue
    const current = groups.get(number)
    if (current) {
      for (const location of locations) {
        if (
          !current.locations.some(
            (other) => other.page === location.page && String(other.box) === String(location.box),
          )
        ) {
          current.locations.push(location)
        }
      }
    } else {
      groups.set(number, {
        number,
        mark,
        reason,
        revision,
        locations: [...locations],
        text: finding.sentenceIds
          .map((id) => sentences.get(id))
          .filter(Boolean)
          .join(' '),
      })
    }
  }
  return [...groups.values()].sort((a, b) => a.number - b.number)
})

async function load() {
  loadFailed.value = false
  try {
    result.value = await inspectionApi.getImageResult(props.jobId)
  } catch {
    loadFailed.value = true
  }
}

async function jumpTo(location: ImageLocation) {
  selection.value = location
  await nextTick()
  const focusedBox = imagePages.value?.querySelector<HTMLElement>('[data-selected-location]')
  focusedBox?.scrollIntoView({ block: 'center', inline: 'nearest' })
  focusedBox?.focus({ preventScroll: true })
}

function highlightStyle(page: ImageInspectionResult['pages'][number], box: ImageLocation['box']) {
  const [left, top, right, bottom] = box
  return {
    left: `${(100 * left) / page.width}%`,
    top: `${(100 * top) / page.height}%`,
    width: `${(100 * (right - left)) / page.width}%`,
    height: `${(100 * (bottom - top)) / page.height}%`,
  }
}

onMounted(load)
</script>

<template>
  <div v-if="loadFailed" class="card space-y-3 p-6">
    <p role="alert">이미지 결과를 불러오지 못했습니다.</p>
    <button type="button" class="text-sm underline" @click="load">다시 불러오기</button>
  </div>
  <p v-else-if="!result" role="status" class="card p-6">이미지 결과를 불러오는 중…</p>
  <template v-else>
    <div
      v-if="result.unreadRegions.length || result.excludedLines.length"
      class="card space-y-2 p-5 text-sm text-ink-700"
    >
      <p v-if="result.unreadRegions.length">
        회색으로 표시된 {{ result.unreadRegions.length }}개 구간은 글자를 읽지 못해 판정하지
        못했습니다.
      </p>
      <details v-if="result.excludedLines.length">
        <summary>영양정보·포장 글자 등 판정 제외 {{ result.excludedLines.length }}줄</summary>
        <ul class="mt-2 list-disc space-y-1 pl-5">
          <li v-for="(line, index) in result.excludedLines" :key="index">
            <button type="button" class="text-left underline" @click="jumpTo(line)">
              {{ line.page + 1 }}페이지 · {{ line.text }}
            </button>
          </li>
        </ul>
      </details>
    </div>
    <div class="grid min-w-0 gap-5 lg:grid-cols-[minmax(0,1fr)_minmax(18rem,22rem)]">
      <section
        aria-labelledby="image-findings-title"
        class="card space-y-3 p-5 lg:sticky lg:top-4 lg:col-start-2 lg:row-start-1 lg:max-h-[80vh] lg:self-start lg:overflow-y-auto"
      >
        <h2 id="image-findings-title" class="text-xl text-ink-900">이미지에 표시된 판정</h2>
        <p v-if="!findings.length" class="text-sm text-ink-700">번호가 표시된 문구가 없습니다.</p>
        <ol class="divide-y divide-black/10">
          <li v-for="finding in findings" :key="finding.number" class="space-y-2 py-3 text-sm">
            <button
              v-if="finding.locations[0]"
              type="button"
              class="rounded-full px-3 py-1.5 font-medium underline"
              :class="marks[finding.mark].class"
              @click="jumpTo(finding.locations[0])"
            >
              {{ finding.number }}. {{ marks[finding.mark].label }}
            </button>
            <p v-else class="font-medium">{{ finding.number }}. {{ marks[finding.mark].label }}</p>
            <p class="break-words font-medium">{{ finding.text }}</p>
            <p class="break-words text-ink-700">{{ finding.reason }}</p>
            <p v-if="finding.revision" class="break-words text-ink-700">
              <span class="font-medium">수정 제안</span> {{ finding.revision }}
            </p>
            <div v-if="finding.locations.length > 1" class="flex flex-wrap gap-2">
              <button
                v-for="(location, index) in finding.locations"
                :key="index"
                type="button"
                class="underline"
                @click="jumpTo(location)"
              >
                {{ location.page + 1 }}페이지 · 위치 {{ index + 1 }}
              </button>
            </div>
          </li>
        </ol>
      </section>
      <section
        ref="imagePages"
        aria-label="검사한 이미지"
        class="min-w-0 space-y-5 lg:col-start-1 lg:row-start-1"
      >
        <figure v-for="page in result.pages" :key="page.index" class="space-y-2">
          <figcaption class="text-sm text-ink-700">{{ page.index + 1 }}페이지</figcaption>
          <div class="relative overflow-hidden rounded-lg border border-black/10 bg-white">
            <img
              :src="page.image"
              :alt="`광고 이미지 ${page.index + 1}페이지`"
              :width="page.width"
              :height="page.height"
              class="block h-auto w-full"
              loading="lazy"
            />
            <span
              v-if="selection?.page === page.index"
              data-selected-location
              tabindex="-1"
              :aria-label="`${page.index + 1}페이지에서 선택한 문구 위치`"
              class="pointer-events-none absolute rounded-sm border-2 border-indigo-700 bg-indigo-500/10 focus:ring-2 focus:ring-indigo-400"
              :style="highlightStyle(page, selection.box)"
            />
          </div>
        </figure>
      </section>
    </div>
  </template>
</template>
