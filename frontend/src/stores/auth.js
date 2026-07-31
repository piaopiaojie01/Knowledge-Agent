import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { authLogin } from '../api'

export const useAuthStore = defineStore('auth', () => {
  const token = ref(localStorage.getItem('ka_token') || '')
  const role = ref(localStorage.getItem('ka_role') || '')
  const username = ref(localStorage.getItem('ka_username') || '')
  const loading = ref(false)
  const error = ref('')

  const isAdmin = computed(() => role.value === 'ADMIN')
  const isLoggedIn = computed(() => !!token.value)

  async function login(u, p) {
    loading.value = true; error.value = ''
    try {
      const { data } = await authLogin(u, p)
      if (data.code === 200) {
        const d = data.data
        token.value = d.token; role.value = d.role; username.value = d.username
        localStorage.setItem('ka_token', d.token)
        localStorage.setItem('ka_role', d.role)
        localStorage.setItem('ka_username', d.username)
        return d
      } else { error.value = data.message; return null }
    } catch (e) { error.value = '连接失败'; return null }
    finally { loading.value = false }
  }

  function logout() {
    token.value = ''; role.value = ''; username.value = ''
    localStorage.removeItem('ka_token'); localStorage.removeItem('ka_role')
    localStorage.removeItem('ka_username'); localStorage.removeItem('ka_session')
  }

  return { token, role, username, loading, error, isAdmin, isLoggedIn, login, logout }
})
