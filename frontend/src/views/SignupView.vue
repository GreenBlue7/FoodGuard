<script setup lang="ts">
import { computed, ref } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { authApi } from '@/api/auth'
import AuthLayout from '@/components/auth/AuthLayout.vue'
import BaseIcon from '@/components/ui/BaseIcon.vue'
import TextField from '@/components/ui/TextField.vue'
import { safeRedirect } from '@/router'
import { useAuthStore } from '@/stores/auth'
import { signupErrorMessage } from '@/utils/authErrors'

// 백엔드 SignupRequest와 같은 규칙. 길이는 백엔드 @Size와 같은 방식(String.length)으로 셈
const PASSWORD_MIN = 15
const PASSWORD_MAX = 64

const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()

const email = ref('')
const password = ref('')
const passwordConfirm = ref('')
const companyName = ref('')
const submitting = ref(false)
const errorMessage = ref('')
// 입력하는 도중에는 오류를 띄우지 않고, 한 번 제출을 시도한 뒤부터 보여줌
const attempted = ref(false)

const emailError = computed(() =>
  attempted.value && email.value.trim() === '' ? '이메일을 입력해 주세요.' : '',
)
const passwordError = computed(() => {
  if (!attempted.value) return ''
  const length = password.value.length
  return length < PASSWORD_MIN || length > PASSWORD_MAX
    ? `비밀번호는 ${PASSWORD_MIN}자 이상 ${PASSWORD_MAX}자 이하로 입력해 주세요. (현재 ${length}자)`
    : ''
})
const confirmError = computed(() =>
  attempted.value && passwordConfirm.value !== password.value
    ? '비밀번호가 일치하지 않습니다.'
    : '',
)

// 로그인 화면에서 넘어온 경우 가입 후 원래 가려던 화면으로
const loginLink = computed(() => ({
  name: 'login',
  query: route.query.redirect ? { redirect: route.query.redirect } : {},
}))

async function submit() {
  if (submitting.value) return
  attempted.value = true
  errorMessage.value = ''
  // 서버도 같은 규칙으로 다시 검사함. 여기서는 요청 전에 바로 알려주는 용도
  if (emailError.value || passwordError.value || confirmError.value) return

  submitting.value = true
  try {
    await authApi.signup({
      email: email.value,
      password: password.value,
      companyName: companyName.value.trim() || undefined,
    })
  } catch (error) {
    errorMessage.value = signupErrorMessage(error)
    submitting.value = false
    return
  }

  // 가입에 성공하면 같은 정보로 바로 로그인 (이메일 인증 절차가 없어 다시 입력받을 이유가 없음)
  try {
    authStore.setSession(await authApi.login(email.value, password.value))
    await router.replace(safeRedirect(route.query.redirect))
  } catch {
    // 가입은 됐으므로 로그인 화면에서 다시 시도하게 함
    await router.replace(loginLink.value)
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <AuthLayout title="회원가입">
    <form class="flex flex-col gap-4" novalidate @submit.prevent="submit">
      <TextField
        v-model="email"
        label="이메일"
        type="email"
        autocomplete="email"
        required
        :error="emailError"
      />
      <TextField
        v-model="password"
        label="비밀번호"
        type="password"
        autocomplete="new-password"
        required
        :hint="`${PASSWORD_MIN}자 이상 ${PASSWORD_MAX}자 이하. 문자 조합 규칙은 없습니다.`"
        :error="passwordError"
      />
      <TextField
        v-model="passwordConfirm"
        label="비밀번호 확인"
        type="password"
        autocomplete="new-password"
        required
        :error="confirmError"
      />
      <TextField v-model="companyName" label="업체명 (선택)" autocomplete="organization" />

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
        가입하기
      </button>
    </form>

    <template #footer>
      이미 계정이 있으신가요?
      <RouterLink
        :to="loginLink"
        class="text-ink-900 underline underline-offset-4 hover:text-black"
      >
        로그인
      </RouterLink>
    </template>
  </AuthLayout>
</template>
