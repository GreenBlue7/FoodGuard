<script setup lang="ts">
import { computed, ref } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { authApi } from '@/api/auth'
import AuthLayout from '@/components/auth/AuthLayout.vue'
import BaseIcon from '@/components/ui/BaseIcon.vue'
import TextField from '@/components/ui/TextField.vue'
import { safeRedirect } from '@/router'
import { useAuthStore } from '@/stores/auth'
import { loginErrorMessage } from '@/utils/authErrors'

const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()

const email = ref('')
const password = ref('')
const submitting = ref(false)
const errorMessage = ref('')

// 보던 화면에서 토큰이 거부되어 넘어온 경우 (main.ts의 onSessionExpired)
const sessionExpired = computed(() => route.query.expired === '1')

// 회원가입으로 넘어가도 로그인 후 돌아갈 주소를 잃지 않게 넘겨줌
const signupLink = computed(() => ({
  name: 'signup',
  query: route.query.redirect ? { redirect: route.query.redirect } : {},
}))

async function submit() {
  if (submitting.value) return
  submitting.value = true
  errorMessage.value = ''
  try {
    authStore.setSession(await authApi.login(email.value, password.value))
    // replace: 뒤로 가기를 눌러도 로그인 화면으로 돌아오지 않게
    await router.replace(safeRedirect(route.query.redirect))
  } catch (error) {
    // 입력값은 그대로 둬서 다시 입력하지 않아도 되게 함
    errorMessage.value = loginErrorMessage(error)
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <AuthLayout title="로그인">
    <p
      v-if="sessionExpired && !errorMessage"
      role="status"
      class="mb-5 rounded-2xl bg-accent/30 px-4 py-3 text-sm text-ink-900"
    >
      로그인이 만료되었습니다. 다시 로그인해 주세요.
    </p>

    <!-- 버튼 클릭이 아닌 form 제출로 처리해서 어느 입력창에서든 Enter가 동작 -->
    <form class="flex flex-col gap-4" novalidate @submit.prevent="submit">
      <TextField v-model="email" label="이메일" type="email" autocomplete="email" required />
      <TextField
        v-model="password"
        label="비밀번호"
        type="password"
        autocomplete="current-password"
        required
      />

      <p v-if="errorMessage" role="alert" class="px-1 text-sm text-red-700">{{ errorMessage }}</p>

      <button
        type="submit"
        class="mt-2 flex w-full items-center justify-center gap-2 rounded-full bg-primary py-3 text-base text-white shadow-sm transition-colors duration-200 enabled:hover:bg-black disabled:cursor-not-allowed disabled:bg-black/[0.07] disabled:text-ink-600 disabled:shadow-none"
        :disabled="submitting"
      >
        <BaseIcon
          v-if="submitting"
          name="loader"
          :size="18"
          class="animate-spin motion-reduce:animate-none"
        />
        로그인
      </button>
    </form>

    <template #footer>
      아직 계정이 없으신가요?
      <RouterLink
        :to="signupLink"
        class="text-ink-900 underline underline-offset-4 hover:text-black"
      >
        회원가입
      </RouterLink>
    </template>
  </AuthLayout>
</template>
