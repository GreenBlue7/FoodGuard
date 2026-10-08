import { fileURLToPath, URL } from 'node:url'

import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
import vueDevTools from 'vite-plugin-vue-devtools'
import tailwindcss from '@tailwindcss/vite'

// https://vite.dev/config/
export default defineConfig(({ mode }) => {
  // 개발 서버가 /api 요청을 넘길 백엔드 주소
  // 기본값은 로컬 Spring Boot(8080)
  const env = loadEnv(mode, process.cwd())
  const apiTarget = env.VITE_API_PROXY_TARGET || 'http://localhost:8080'

  return {
    plugins: [vue(), vueDevTools(), tailwindcss()],
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
