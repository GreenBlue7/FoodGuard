<script setup lang="ts">
import BrandLogo from '@/components/layout/BrandLogo.vue'

// 로그인·회원가입 화면의 공통 틀: 위쪽에 큰 로고, 그 아래 카드 안에 폼
defineProps<{ title: string }>()
</script>

<template>
  <div
    class="mx-auto flex w-full max-w-sm flex-1 flex-col items-center justify-center gap-10 py-12"
  >
    <BrandLogo size="lg" />

    <section class="card w-full p-8" aria-labelledby="auth-title">
      <h1 id="auth-title" class="text-2xl tracking-tight text-ink-900">{{ title }}</h1>
      <div class="mt-6">
        <slot />
      </div>
    </section>

    <!-- 화면 전환 링크 (로그인 ↔ 회원가입) -->
    <p class="text-center text-sm text-ink-600">
      <slot name="footer" />
    </p>
  </div>
</template>
