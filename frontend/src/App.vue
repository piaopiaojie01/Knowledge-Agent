<template>
  <Login v-if="!auth.isLoggedIn" />
  <Chat v-else />
</template>

<script setup>
import Login from './views/Login.vue'
import Chat from './views/Chat.vue'
import { useAuthStore } from './stores/auth'
const auth = useAuthStore()
</script>
