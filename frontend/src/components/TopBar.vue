<template>
  <div class="header">
    <h1>Knowledge Agent</h1>
    <div class="header-right">
      <div class="tabs">
        <div :class="['tab', { active: chat.currentTab === 'chat' }]" @click="chat.currentTab = 'chat'">问答</div>
        <div :class="['tab', { active: chat.currentTab === 'docs' }]" @click="chat.currentTab = 'docs'">文档</div>
        <div :class="['tab', { active: chat.currentTab === 'search' }]" @click="chat.currentTab = 'search'">🔍 检索</div>
      </div>
      <button @click="auth.logout()" style="padding:5px 10px;background:rgba(100,116,139,.15);border:1px solid rgba(100,116,139,.3);border-radius:6px;color:#94a3b8;font-size:11px;cursor:pointer;font-weight:600">退出</button>
      <button v-if="auth.isAdmin" @click="showAdmin = true" style="padding:5px 12px;background:rgba(239,68,68,.15);border:1px solid rgba(239,68,68,.3);border-radius:6px;color:#ef4444;font-size:11px;cursor:pointer;font-weight:600">
        ⚙ 管理 <span v-if="chat.notifCount" style="background:#ef4444;color:#fff;border-radius:10px;padding:1px 6px;font-size:10px;margin-left:4px">{{ chat.notifCount }}</span>
      </button>
      <div class="status">
        <span><span class="dot" :class="{ on: health.agent }"></span>Agent</span>
        <span><span class="dot" :class="{ on: health.backend }"></span>Backend</span>
      </div>
      <span v-if="chat.tokenStats.totalAll" class="token-badge">
        C:{{ (chat.tokenStats.session/1000).toFixed(1) }}K | T:{{ (chat.tokenStats.totalAll/1000).toFixed(1) }}K
      </span>
      <AdminPanel v-if="showAdmin" @close="showAdmin = false" />
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useAuthStore } from '../stores/auth'
import { useChatStore } from '../stores/chat'
import AdminPanel from './AdminPanel.vue'
const auth = useAuthStore()
const chat = useChatStore()
const showAdmin = ref(false)
const health = ref({ agent: false, backend: false })

onMounted(async () => {
  try { const r = await fetch('/api/health'); const { data } = await r.json(); health.value = data } catch (e) { }
})
</script>
