import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { authLogin, authLogout, authMe } from '../api'

export const useAuthStore = defineStore('auth', () => {
  // P0：token 只存 HttpOnly Cookie，内存态仅用于视图判断
  const token = ref('')
  const role = ref('')
  const username = ref('')
  const loading = ref(false)
  const error = ref('')
  // 会话恢复完成前不渲染（避免刷新页面时闪登录页）
  const ready = ref(false)
  // 管理后台页面开关（仅 ADMIN 生效，App.vue 据此切换整页视图）
  const adminOpen = ref(false)
  // 管理后台初始分区（如从顶部模型徽标进入时预选「模型配置」）
  const adminTab = ref('dash')

  const isAdmin = computed(() => role.value === 'ADMIN')
  const isLoggedIn = computed(() => !!token.value)

  async function login(u, p) {
    loading.value = true; error.value = ''
    try {
      const { data } = await authLogin(u, p)
      if (data.code === 200) {
        const d = data.data
        token.value = d.token || 'cookie'
        role.value = d.role; username.value = d.username
        return d
      } else { error.value = data.message; return null }
    } catch (e) { error.value = '连接失败'; return null }
    finally { loading.value = false }
  }

  async function logout() {
    try {
      // 后端撤销 Cookie 中的 token 并清 Cookie
      await authLogout()
    } catch (e) { /* 后端不可达也继续本地清理 */ }
    token.value = ''; role.value = ''; username.value = ''
    adminOpen.value = false
    adminTab.value = 'dash'
  }

  /** 刷新页面后经 HttpOnly Cookie 恢复会话 */
  async function restore() {
    try {
      const { data } = await authMe()
      if (data.code === 200) {
        token.value = 'cookie'
        role.value = data.data.role
        username.value = data.data.username
        return true
      }
    } catch (e) { /* 未登录 */ }
    finally { ready.value = true }
    return false
  }

  return { token, role, username, loading, error, ready, adminOpen, adminTab,
           isAdmin, isLoggedIn, login, logout, restore }
})
