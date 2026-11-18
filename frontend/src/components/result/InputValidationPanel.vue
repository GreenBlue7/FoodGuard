<script setup lang="ts">
import BaseIcon from '@/components/ui/BaseIcon.vue'
import type { InputValidationResult } from '@/types/inspection'
defineProps<{ validation: InputValidationResult }>()
</script>

<template>
  <section
    class="flex flex-col gap-5 rounded-[1.75rem] bg-primary p-6 text-white shadow-[0_12px_32px_-16px_rgb(0,0,0,0.4)]"
    aria-labelledby="input-validation-title"
  >
    <h2 id="input-validation-title" class="text-xl">1차 입력 검증</h2>
    <p class="text-sm" :class="validation.status === 'PASSED' ? 'text-accent' : 'text-red-300'">
      {{
        validation.status === 'PASSED' ? '검사 접수 조건 확인 완료' : '검사할 수 없는 요청입니다'
      }}
    </p>
    <p v-if="validation.characterCount !== null" class="text-sm text-white/80">
      입력 {{ validation.characterCount }} / {{ validation.maxCharacters }}자
    </p>
    <ul class="flex flex-col gap-2">
      <li
        v-for="check in validation.checks"
        :key="check.code"
        class="flex items-center justify-between gap-3 rounded-2xl bg-white/[0.06] px-4 py-3 text-sm"
      >
        <span>{{ check.label }}</span>
        <BaseIcon
          :name="check.passed ? 'check' : 'x'"
          :size="16"
          :class="check.passed ? 'text-accent' : 'text-red-300'"
        />
      </li>
    </ul>
    <p v-for="notice in validation.notices" :key="notice" class="text-sm text-white/80">
      {{ notice }}
    </p>
    <p class="text-xs text-white/70">
      접수 전 입력 확인 결과입니다. 광고 내용은 문장별 판정에서 확인해 주세요.
    </p>
  </section>
</template>
