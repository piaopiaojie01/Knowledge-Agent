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
      <span v-if="modelName" class="model-badge" :class="{ clickable: auth.isAdmin }"
        :title="auth.isAdmin ? '点击进入模型配置' : '当前生效模型（仅管理员可配置）'"
        @click="auth.isAdmin && openModelConfig()">🤖 {{ modelName }}</span>
      <span v-if="chat.tokenStats.totalAll" class="token-badge">
        C:{{ (chat.tokenStats.session/1000).toFixed(1) }}K | T:{{ (chat.tokenStats.totalAll/1000).toFixed(1) }}K
        <template v-if="chat.tokenStats.sessionCost !== undefined"> | ≈¥{{ fmtCost(chat.tokenStats.sessionCost) }}</template>
      </span>
      <button class="btn-ghost" @click="toggleTheme" :title="themeTitle">
        {{ themeIcon }}
      </button>
      <button v-if="auth.isAdmin" class="btn-ghost danger" @click="auth.adminOpen = true">
        ⚙ 管理<span v-if="chat.notifCount" class="notif-badge">{{ chat.notifCount }}</span>
      </button>
      <button class="btn-ghost" @click="auth.logout()">退出</button>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useAuthStore } from '../stores/auth'
import { useChatStore } from '../stores/chat'
import { getCurrentModel } from '../api'
import { getThemePref, setThemePref } from '../utils/theme'
const auth = useAuthStore()
const chat = useChatStore()
const health = ref({ agent: false, backend: false })
const modelName = ref('')
const themePref = ref(getThemePref())
const themeIcon = computed(() =>
  themePref.value === 'light' ? '☀️' : themePref.value === 'dark' ? '🌙' : '⏰')
const themeTitle = computed(() =>
  themePref.value === 'light' ? '日间模式（点击切换）'
    : themePref.value === 'dark' ? '夜间模式（点击切换）'
      : '自动模式：白天日间/黑夜夜间（点击切换）')
function toggleTheme() {
  themePref.value = themePref.value === 'auto' ? 'light'
    : themePref.value === 'light' ? 'dark' : 'auto'
  setThemePref(themePref.value)
}
function fmtCost(c) {
  if (c === undefined || c === null || isNaN(c)) return ''
  if (c === 0) return '0'
  return c >= 1 ? c.toFixed(2) : c.toFixed(4)
}

function openModelConfig() {
  auth.adminTab = 'model'
  auth.adminOpen = true
}

onMounted(async () => {
  try { const { data } = await getCurrentModel(); if (data.code === 200) modelName.value = data.data.model || '' } catch (e) { }
  try { const r = await fetch('/api/health'); const { data } = await r.json(); health.value = data } catch (e) { }
})
</script>
