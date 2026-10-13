import { createApp } from 'vue'
import { createPinia } from 'pinia'
// 글꼴은 전부 CDN 대신 패키지로 포함. 발표장 네트워크와 상관없이 글꼴이 나옴
// 세 패키지 모두 글자 범위별로 쪼갠 파일이라 화면에 쓰인 글자 범위만 내려받음
// 본문: Wanted Sans (가변 굵기)
import 'wanted-sans/fonts/webfonts/variable/split/WantedSansVariable.css'
// 소제목·큰 숫자: IBM Plex Sans KR
// 소제목은 한글이 필요해 500 전체, 큰 숫자(300)는 숫자뿐이라 라틴 범위만 불러옴
import '@fontsource/ibm-plex-sans-kr/500.css'
import '@fontsource/ibm-plex-sans-kr/latin-300.css'
// 로고: Fraunces (굵기·광학 크기 축 + 이탤릭)
import '@fontsource-variable/fraunces/opsz.css'
import '@fontsource-variable/fraunces/opsz-italic.css'
import './assets/main.css'

import App from './App.vue'
import router from './router'

const app = createApp(App)

app.use(createPinia())
app.use(router)

app.mount('#app')
