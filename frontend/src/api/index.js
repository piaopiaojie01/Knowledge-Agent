import axios from 'axios'

const api = axios.create({ baseURL: '/api' })

const AUTH_KEYS = ['ka_token', 'ka_role', 'ka_username', 'ka_session']

function genRequestId() {
  return (crypto.randomUUID && crypto.randomUUID()) ||
    'req-' + Date.now() + '-' + Math.random().toString(36).slice(2, 10)
}

function clearAuthAndReload() {
  AUTH_KEYS.forEach(k => localStorage.removeItem(k))
  window.location.reload()
}

// 单飞：并发 401 只触发一次刷新，避免重复调用 /auth/refresh
let refreshPromise = null

async function refreshToken() {
  const token = localStorage.getItem('ka_token')
  if (!token) return null
  try {
    const { data } = await api.post('/auth/refresh', null, {
      headers: { Authorization: `Bearer ${token}` }
    })
    if (data?.code === 200 && data.data?.token) {
      localStorage.setItem('ka_token', data.data.token)
      return data.data.token
    }
  } catch (e) { /* 刷新失败走统一清理 */ }
  return null
}

// 请求拦截：自动带 token
api.interceptors.request.use(cfg => {
  // 可观测性：透传请求 ID，便于后端/Agent 日志串联
  cfg.headers['X-Request-Id'] = cfg.headers['X-Request-Id'] || genRequestId()
  const token = localStorage.getItem('ka_token')
  if (token) cfg.headers.Authorization = `Bearer ${token}`
  return cfg
})

// 响应拦截：401 先尝试刷新重试一次，仍失败才清 token 跳登录
api.interceptors.response.use(r => r, async err => {
  const { response, config } = err
  const isAuthEndpoint = config?.url && /auth\/(login|refresh)/.test(config.url)
  if (response?.status === 401 && config && !config._retry && !isAuthEndpoint) {
    config._retry = true
    if (!refreshPromise) refreshPromise = refreshToken().finally(() => { refreshPromise = null })
    const newToken = await refreshPromise
    if (newToken) {
      config.headers.Authorization = `Bearer ${newToken}`
      return api(config)
    }
  }
  clearAuthAndReload()
  return Promise.reject(err)
})

// ── Auth ──
export const authLogin = (u, p) => api.post('/auth/login', { username: u, password: p })
export const authLogout = () => api.post('/auth/logout')

// ── KB ──
export const getKBList = () => api.get('/kb')
export const createKB = (name) => api.post('/kb', { name, description: '' })
export const updateKB = (id, payload) => api.put(`/kb/${id}`, payload)
export const deleteKB = (id) => api.delete(`/kb/${id}`)

// ── Docs ──
export const uploadDoc = (formData) => api.post('/docs/upload', formData)
export const getDocs = (kbId) => api.get(`/docs/kb/${kbId}`)
export const getDoc = (id) => api.get(`/docs/${id}`)
export const deleteDoc = (id) => api.delete(`/docs/${id}`)
export const searchDocs = (q) => api.get(`/docs/search?q=${encodeURIComponent(q)}`)

// ── Chat / RAG ──
export const ragQuery = (question, kbNames, history, sessionId) =>
  api.post('/rag/query', { question, kbNames, history, sessionId })
export const ragSearch = (question, kbNames, topK) =>
  api.post('/rag/search', { question, kbNames, topK: topK || 10 })

// 流式问答：SSE 逐行解析（axios 不支持浏览器流式响应，用 fetch）
export async function ragQueryStream(question, kbNames, history, sessionId, { onDelta, onFinal, onError }) {
  const token = localStorage.getItem('ka_token')
  const resp = await fetch('/api/rag/query/stream', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Accept': 'text/event-stream',
      'X-Request-Id': genRequestId(),
      ...(token ? { Authorization: `Bearer ${token}` } : {})
    },
    body: JSON.stringify({ question, kbNames, history, sessionId })
  })
  if (!resp.ok || !resp.body) {
    // fetch 绕过 axios 拦截器，401 需自行清 token 并跳登录
    if (resp.status === 401) {
      const ok = await refreshToken()
      if (!ok) clearAuthAndReload()
    }
    throw new Error('stream http ' + resp.status)
  }
  const reader = resp.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  let finished = false
  let gotFinal = false
  let gotError = false
  let finalP = null
  const handleLine = (line) => {
    line = line.replace(/\r$/, '')
    if (!line.startsWith('data:')) return
    const payload = line.slice(5).trim()
    if (!payload) return
    if (payload === '[DONE]') { finished = true; gotFinal = true; return }
    try {
      const obj = JSON.parse(payload)
      if (obj.type === 'delta') onDelta && onDelta(obj.text || '')
      else if (obj.type === 'final') { gotFinal = true; finalP = Promise.resolve(onFinal && onFinal(obj)) }
      else if (obj.type === 'error') { finished = true; gotError = true; onError && onError(obj.message || '未知错误') }
    } catch (e) { }
  }
  for (;;) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    let idx
    while ((idx = buffer.indexOf('\n')) >= 0) {
      handleLine(buffer.slice(0, idx))
      buffer = buffer.slice(idx + 1)
      if (finished) break
    }
    if (finished) break
  }
  buffer += decoder.decode()
  if (buffer) handleLine(buffer)
  if (finalP) await finalP
  // 流正常关闭但没收到 final/[DONE]/error → 视为不完整，抛错让调用方走回退逻辑
  if (!gotFinal && !gotError) throw new Error('stream incomplete')
}

// ── Feedback ──
export const sendFeedback = (payload) => api.post('/feedback', payload)

// ── Conversation ──
export const getSessions = () => api.get('/conversation/list')
export const loadSession = (sid) => api.get(`/conversation/${sid}`)
export const saveMsg = (sid, role, content, it, ot) =>
  api.post('/conversation', { sessionId: sid, role, content, inputTokens: it || 0, outputTokens: ot || 0 })
export const deleteSession = (sid) => api.delete(`/conversation/${sid}`)
export const renameSession = (sid, title) => api.put(`/conversation/${sid}/title`, { title })
export const getTokenStats = () => api.get('/conversation/stats')

// ── Admin ──
export const getAdminUsers = () => api.get('/admin/users')
export const createUser = (u, p) => api.post('/admin/users', { username: u, password: p })
export const deleteUser = (id) => api.delete(`/admin/users/${id}`)
export const updateUserStatus = (id, isActive) => api.put(`/admin/users/${id}/status`, { isActive })
export const resetUserPassword = (id, password) => api.put(`/admin/users/${id}/password`, { password })
export const forceLogoutUser = (id) => api.post(`/admin/users/${id}/force-logout`)
export const getAdminStats = () => api.get('/admin/stats')
export const getAuditLog = () => api.get('/admin/audit')
export const getAdminKbs = () => api.get('/admin/kbs')
export const getAdminPerms = () => api.get('/admin/permissions')
export const grantPermAdmin = (username, kbId, permissionType) =>
  api.post('/admin/permissions/grant', { username, kbId, permissionType })
export const revokePermAdmin = (username, kbId) =>
  api.post('/admin/permissions/revoke', { username, kbId })
export const getModelConfig = () => api.get('/admin/model-config')
export const updateModelConfig = (payload) => api.put('/admin/model-config', payload)
export const getCurrentModel = () => api.get('/model-config/current')
export const getSkills = () => api.get('/admin/skills')
export const updateSkill = (name, payload) => api.put(`/admin/skills/${name}`, payload)
export const getMcpServers = () => api.get('/admin/mcp-servers')
export const createMcpServer = (payload) => api.post('/admin/mcp-servers', payload)
export const updateMcpServer = (id, payload) => api.put(`/admin/mcp-servers/${id}`, payload)
export const deleteMcpServer = (id) => api.delete(`/admin/mcp-servers/${id}`)
export const batchUsers = (csv) => api.post('/admin/users/batch', { csv })
export const listFeedback = () => api.get('/feedback')
export const getNotifications = () => api.get('/notifications')
export const grantPerm = (username, kbId, permissionType) =>
  api.post('/permissions/grant', { username, kbId, permissionType })
export const revokePerm = (username, kbId) => api.post('/permissions/revoke', { username, kbId })

// ── Notifications ──
export const getNotifCount = () => api.get('/notifications/unread-count')
export const markNotifsRead = () => api.post('/notifications/mark-read')

// ── Permissions ──
export const getPerms = () => api.get('/permissions')
