<template>
  <Login v-if="!auth.isLoggedIn" />
  <Admin v-else-if="auth.isAdmin && auth.adminOpen" />
  <Chat v-else />
</template>

<script setup>
import Login from './views/Login.vue'
import Chat from './views/Chat.vue'
import Admin from './views/Admin.vue'
import { useAuthStore } from './stores/auth'
const auth = useAuthStore()
</script>
