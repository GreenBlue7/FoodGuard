<script setup lang="ts">
import AppHeader from './AppHeader.vue'
</script>

<template>
  <!-- 초광폭 화면에서도 카드와 줄 길이가 읽기 좋도록 최대 폭을 두고 가운데 정렬 -->
  <div class="mx-auto flex min-h-dvh w-full max-w-[1440px] flex-col px-8">
    <!-- 상세페이지 이미지 첨부 버튼(attach-click)은 업로드 API가 정해지면 여기서 연결 -->
    <AppHeader />
    <!-- min-w-0: 넓은 자식(그리드, 긴 단어)이 페이지를 옆으로 밀지 않고 줄어들게 -->
    <main class="flex min-w-0 flex-1 flex-col pb-12">
      <RouterView />
    </main>
  </div>
</template>
