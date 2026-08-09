<template>
  <div class="login-overlay">
    <div class="login-box">
      <div class="logo-mark">✦</div>
      <h2>Knowledge Agent</h2>
      <div class="sub">知识库智能问答平台</div>
      <input v-model="username" type="text" placeholder="用户名" @keyup.enter="doLogin" />
      <input v-model="password" type="password" placeholder="密码" @keyup.enter="doLogin" />
      <button class="btn btn-block" @click="doLogin" :disabled="auth.loading">
        <span v-if="auth.loading" class="spin"></span>
        {{ auth.loading ? '登录中...' : '登 录' }}
      </button>
      <div class="error" v-if="auth.error">{{ auth.error }}</div>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useAuthStore } from '../stores/auth'
const auth = useAuthStore()
const username = ref('admin')
const password = ref('admin123')

async function doLogin() {
  await auth.login(username.value, password.value)
}
</script>
