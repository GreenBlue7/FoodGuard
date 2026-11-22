<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ApiError } from '@/api/errors'
import { inspectionApi } from '@/api/inspection'
import { useInspectionStore } from '@/stores/inspection'

const props = defineProps<{ productReady: boolean }>()
const store = useInspectionStore()
const router = useRouter()
const maxFiles = 10
const mib = 1024 * 1024
const images = ref<{ id: number; file: File }[]>([])
let nextId = 0
const submitting = defineModel<boolean>('submitting', { required: true })
const error = ref('')

function add(event: Event) {
  const input = event.target as HTMLInputElement
  const files = Array.from(input.files ?? [])
  input.value = ''
  if (files.length === 0) return
  error.value = ''
  const all = [...images.value.map(({ file }) => file), ...files]
  if (all.length > maxFiles) error.value = '이미지는 최대 10장까지 올릴 수 있습니다.'
  else if (files.some((file) => !/\.(jpe?g|png)$/i.test(file.name))) {
    error.value = 'PNG 또는 JPEG 이미지를 선택해 주세요.'
  } else if (
    all.some((file) => file.size > 50 * mib) ||
    all.reduce((sum, file) => sum + file.size, 0) > 100 * mib
  ) {
    error.value = '한 장당 50 MiB, 합계 100 MiB까지 올릴 수 있습니다.'
  } else {
    images.value.push(...files.map((file) => ({ id: nextId++, file })))
  }
}

function move(from: number, step: number) {
  const to = from + step
  if (to < 0 || to >= images.value.length) return
  const [image] = images.value.splice(from, 1)
  if (image) images.value.splice(to, 0, image)
}

function remove(index: number) {
  images.value.splice(index, 1)
  error.value = ''
}

async function submit() {
  if (!props.productReady || images.value.length === 0 || submitting.value) return
  submitting.value = true
  error.value = ''
  const { productIds, productTypeAnswer } = store.toRequest()
  try {
    const { jobId } = await inspectionApi.createImage(
      { productIds, productTypeAnswer },
      images.value.map(({ file }) => file),
    )
    await router.push({ name: 'inspection-result', params: { jobId } })
  } catch (failure) {
    if (failure instanceof ApiError && failure.status === 413) {
      error.value = '이미지 용량 한도를 초과했습니다. 파일 크기를 줄여 주세요.'
    } else if (failure instanceof ApiError && failure.status !== null && failure.status < 500) {
      error.value = failure.message
    } else {
      error.value = '접수 응답을 확인하지 못했습니다. 다시 접수하기 전에 검수 이력을 확인해 주세요.'
    }
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <section aria-labelledby="image-section-title" class="card flex min-w-0 flex-col gap-5 p-6">
    <h2 id="image-section-title" class="text-xl text-ink-900">광고 이미지</h2>
    <p class="text-sm text-ink-700">
      PNG 또는 JPEG 1~10장. 한 장당 50 MiB, 합계 100 MiB까지 올릴 수 있습니다. 긴 캡처는 통째로
      올리면 이미지 경계에서 문장이 끊기는 일을 줄일 수 있습니다.
    </p>
    <input
      type="file"
      accept=".jpg,.jpeg,.png,image/jpeg,image/png"
      multiple
      aria-label="광고 이미지 선택"
      class="w-full rounded-xl border border-black/10 p-3 text-sm"
      :disabled="submitting || images.length === maxFiles"
      @change="add"
    />
    <div v-if="images.length > 0" class="space-y-2">
      <p class="text-sm text-ink-700">아래 순서대로 위에서부터 검사합니다.</p>
      <ol class="divide-y divide-black/10">
        <li
          v-for="({ file, id }, index) in images"
          :key="id"
          class="flex flex-wrap items-center justify-between gap-2 py-2 text-sm"
        >
          <span class="min-w-0 break-all">{{ index + 1 }}. {{ file.name }}</span>
          <div class="flex shrink-0 gap-3">
            <button
              type="button"
              :disabled="submitting || index === 0"
              class="underline disabled:opacity-35"
              :aria-label="`${file.name} 위로 이동`"
              @click="move(index, -1)"
            >
              위로
            </button>
            <button
              type="button"
              :disabled="submitting || index === images.length - 1"
              class="underline disabled:opacity-35"
              :aria-label="`${file.name} 아래로 이동`"
              @click="move(index, 1)"
            >
              아래로
            </button>
            <button
              type="button"
              :disabled="submitting"
              class="underline"
              :aria-label="`${file.name} 삭제`"
              @click="remove(index)"
            >
              삭제
            </button>
          </div>
        </li>
      </ol>
    </div>
    <p v-if="!productReady" class="text-sm text-ink-700">
      제품을 선택하거나 제품 종류를 알려 주세요.
    </p>
    <p v-if="error" role="alert" class="text-sm text-red-700">{{ error }}</p>
    <button
      type="button"
      class="rounded-full bg-primary px-6 py-3 text-white disabled:cursor-not-allowed disabled:opacity-40"
      :disabled="!productReady || images.length === 0 || submitting"
      @click="submit"
    >
      {{ submitting ? '이미지 접수 중…' : '이미지 검사하기' }}
    </button>
  </section>
</template>
