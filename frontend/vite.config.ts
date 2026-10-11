import { fileURLToPath, URL } from 'node:url'

import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
import vueDevTools from 'vite-plugin-vue-devtools'
import tailwindcss from '@tailwindcss/vite'

// 설정 문서: https://vite.dev/config/
export default defineConfig(({ mode }) => {
  // 개발 서버가 /api 요청을 넘길 백엔드 주소
  // 쉘 환경 변수나 .env.development.local의 VITE_API_PROXY_TARGET으로 바꿀 수 있음
  const env = loadEnv(mode, process.cwd())
  const apiTarget = env.VITE_API_PROXY_TARGET || 'http://localhost:8080'

  return {
    plugins: [
      vue(),
      vueDevTools(),
      tailwindcss(),
    ],
    resolve: {
      alias: {
        '@': fileURLToPath(new URL('./src', import.meta.url)),
      },
    },
    // 프론트 코드는 상대 경로(/api/...)만
    // 개발 중에는 이 프록시가, 운영 환경에서는 nginx가 /api 요청을 백엔드로 넘김
    server: {
      proxy: {
        '/api': { target: apiTarget, changeOrigin: true },
      },
    },
  }
})
