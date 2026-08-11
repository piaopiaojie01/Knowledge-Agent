<template>
  <div v-if="!auth.ready" class="app-loading">加载中…</div>
  <Login v-else-if="!auth.isLoggedIn" />
  <Admin v-else-if="auth.isAdmin && auth.adminOpen" />
  <Chat v-else />
</template>

<script setup>
import { onMounted, onUnmounted } from 'vue'
import Login from './views/Login.vue'
import Chat from './views/Chat.vue'
import Admin from './views/Admin.vue'
import { useAuthStore } from './stores/auth'
import { applyTheme } from './utils/theme'
const auth = useAuthStore()

let themeTimer = null
onMounted(() => {
  // 主题：auto 模式下白天日间/黑夜夜间，每分钟与切回前台时刷新
  applyTheme()
  // P0：Cookie 会话恢复（HttpOnly，JS 读不到 token）
  auth.restore()
  themeTimer = setInterval(applyTheme, 60_000)
  document.addEventListener('visibilitychange', onVisibility)
})
function onVisibility() {
  if (!document.hidden) applyTheme()
}
onUnmounted(() => {
  clearInterval(themeTimer)
  document.removeEventListener('visibilitychange', onVisibility)
})
</script>
