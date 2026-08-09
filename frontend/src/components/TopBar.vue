<template>
  <div class="header">
    <div class="brand">
      <div class="logo-mark sm">✦</div>
      <h1>Knowledge Agent</h1>
    </div>
    <div class="header-right">
      <div class="tabs">
        <div :class="['tab', { active: chat.currentTab === 'chat' }]" @click="chat.currentTab = 'chat'">问答</div>
        <div :class="['tab', { active: chat.currentTab === 'docs' }]" @click="chat.currentTab = 'docs'">文档</div>
        <div :class="['tab', { active: chat.currentTab === 'search' }]" @click="chat.currentTab = 'search'">检索</div>
      </div>
      <div class="status">
        <span><span class="dot" :class="{ on: health.agent }"></span>Agent</span>
        <span><span class="dot" :class="{ on: health.backend }"></span>Backend</span>
      </div>
      <span v-if="chat.tokenStats.totalAll" class="token-badge">
        C:{{ (chat.tokenStats.session/1000).toFixed(1) }}K | T:{{ (chat.tokenStats.totalAll/1000).toFixed(1) }}K
      </span>
      <button v-if="auth.isAdmin" class="btn-ghost danger" @click="auth.adminOpen = true">
        ⚙ 管理<span v-if="chat.notifCount" class="notif-badge">{{ chat.notifCount }}</span>
      </button>
      <button class="btn-ghost" @click="auth.logout()">退出</button>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useAuthStore } from '../stores/auth'
import { useChatStore } from '../stores/chat'
const auth = useAuthStore()
const chat = useChatStore()
const health = ref({ agent: false, backend: false })

onMounted(async () => {
  try { const r = await fetch('/api/health'); const { data } = await r.json(); health.value = data } catch (e) { }
})
</script>
