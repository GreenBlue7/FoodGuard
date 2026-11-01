<script setup lang="ts">
import { computed, useId } from 'vue'

// 라벨이 붙은 한 줄 입력창. 광고 문구 입력창(AdTextInput)과 같은 테두리·포커스 표시
const props = defineProps<{
  label: string
  type?: 'text' | 'email' | 'password'
  autocomplete?: string
  hint?: string // 입력창 아래 안내 (예: 비밀번호 규칙)
  error?: string // 있으면 hint 대신 빨간 글씨로 표시하고 화면 낭독기에도 오류로 알림
  required?: boolean
}>()

const model = defineModel<string>({ required: true })

// 라벨·안내 문구를 입력창과 연결하려면 화면 안에서 겹치지 않는 id가 필요
const id = useId()
const describedBy = computed(() => (props.error || props.hint ? `${id}-desc` : undefined))
</script>

<template>
  <div>
    <label :for="id" class="block px-1 text-sm text-ink-700">{{ label }}</label>
    <input
      :id="id"
      v-model="model"
      :type="type ?? 'text'"
      :autocomplete="autocomplete"
      :required="required"
      :aria-invalid="error ? true : undefined"
      :aria-describedby="describedBy"
      class="mt-2 block w-full rounded-2xl border bg-white/90 px-4 py-3 text-sm outline-hidden transition focus:ring-4 focus:ring-accent/40"
      :class="
        error ? 'border-red-600/60 focus:border-red-600' : 'border-black/10 focus:border-primary'
      "
    />
    <p
      v-if="error || hint"
      :id="`${id}-desc`"
      class="mt-2 px-1 text-xs"
      :class="error ? 'text-red-700' : 'text-ink-600'"
    >
      {{ error || hint }}
    </p>
  </div>
</template>
