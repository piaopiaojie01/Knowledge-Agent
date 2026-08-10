import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import {
  ragQuery, ragQueryStream, getSessions, loadSession, saveMsg, deleteSession, renameSession,
  getTokenStats, getNotifCount, markNotifsRead
} from '../api'
import { useKBStore } from './kb'
import { useAuthStore } from './auth'

export const useChatStore = defineStore('chat', () => {
  const messages = ref([])
  const chatHistory = ref([])
  const sessionId = ref(localStorage.getItem('ka_session') || 's' + Date.now())
  const currentTab = ref('chat')
  const sending = ref(false)
  const sessions = ref([])
  const tokenStats = ref({ session: 0, total30d: 0, totalAll: 0 })
  const notifCount = ref(0)

  async function send(question) {
    sending.value = true
    // 快照会话 id：流式期间用户可能切换会话，存库仍归属发问时的会话
    const sid = sessionId.value
    try {
      addMsg('user', question)
      // 存库失败不应中断提问
      try { await saveMsg(sid, 'user', question, 0, 0) } catch (e) { console.warn('保存用户消息失败', e) }
      // 刷新会话列表：后端会以首条问题内容命名会话
      try { await loadSessions() } catch (e) { }
      const kb = useKBStore()
      const kbNames = kb.kbs.filter(k => kb.selectedKbs.has(k.id)).map(k => k.name)
      // 流式：先插入空 agent 消息，delta 时增量追加（必须用数组里的 proxy 引用更新）
      let streamed = false
      let proxiedMsg = null
      const agentMsg = { role: 'agent', content: '', sources: null, done: false, id: Date.now() + Math.random() }
      const ensureMsg = () => {
        if (!streamed) {
          streamed = true
          messages.value.push(agentMsg)
          proxiedMsg = messages.value[messages.value.length - 1]  // 取 reactive proxy
        }
        return proxiedMsg
      }
      // 保存 agent 回答：存库/统计失败仅告警，不否定已生成的回答（流式 final 与断流时复用）
      const saveAnswer = async (m, fin) => {
        try { await saveMsg(sid, 'assistant', m.content, fin?.input_tokens || 0, fin?.output_tokens || 0) } catch (e) { console.warn('保存回答失败', e) }
        chatHistory.value.push({ role: 'assistant', content: m.content })
        try { await refreshTokenStats() } catch (e) { console.warn('刷新 token 统计失败', e) }
      }
      try {
        await ragQueryStream(question, kbNames.length ? kbNames : null, chatHistory.value.slice(-50), sid, {
          onDelta(text) {
            const m = ensureMsg()
            m.content += text
          },
          async onFinal(fin) {
            const m = ensureMsg()
            m.sources = fin.sources || null
            m.done = true
            m.inputTokens = fin.input_tokens || 0
            m.outputTokens = fin.output_tokens || 0
            m.cacheHit = fin.cache_hit_tokens
            m.cacheMiss = fin.cache_miss_tokens
            await saveAnswer(m, fin)
          },
          onError(msg) {
            const m = ensureMsg()
            m.content = '查询失败: ' + msg
          }
        })
      } catch (e) {
        if (streamed) {
          // 断流：保留已流出的部分内容并照常存库
          const m = ensureMsg()
          m.content += '\n\n*(连接中断，回答不完整)*'
          await saveAnswer(m)
        } else {
          // 流式接口不可用 → 回退到原非流式调用
          await sendFallback(question, kbNames, sid)
        }
      }
    } finally {
      sending.value = false
    }
  }

  async function sendFallback(question, kbNames, sid) {
    try {
      const { data } = await ragQuery(question, kbNames.length ? kbNames : null, chatHistory.value.slice(-50), sid)
      // 兼容后端返回：有 answer 即视为成功
      if (data.code === 200 && (data.data?.success || data.data?.answer)) {
        addMsg('agent', data.data.answer, data.data.sources, 'assistant', {
          inputTokens: data.data.inputTokens || 0,
          outputTokens: data.data.outputTokens || 0,
          cacheHit: data.data.cacheHitTokens,
          cacheMiss: data.data.cacheMissTokens
        })
        await saveMsg(sid, 'assistant', data.data.answer, data.data.inputTokens || 0, data.data.outputTokens || 0)
        await refreshTokenStats()
      } else {
        // 错误提示不进 chatHistory（避免浪费 token）
        addMsg('agent', '查询失败: ' + (data.data?.answer || data.message || '未知错误'), null, false)
      }
    } catch (e) {
      addMsg('agent', '网络连接失败', null, false)
    }
  }

  function addMsg(role, content, sources, historyRole, meta = {}) {
    messages.value.push({ role, content, sources, done: true, id: Date.now() + Math.random(), ...meta })
    // historyRole === false → 仅展示，不进发给 LLM 的历史
    if (historyRole !== false) chatHistory.value.push({ role: historyRole || role, content })
  }

  // ── Sessions ──
  async function loadSessions() {
    try { const { data } = await getSessions(); if (data.code === 200) sessions.value = data.data } catch (e) { }
  }
  async function switchSession(sid) {
    sessionId.value = sid
    localStorage.setItem('ka_session', sid)
    messages.value = []; chatHistory.value = []
    try {
      const { data } = await loadSession(sid)
      if (data.code === 200) {
        data.data.forEach(m => {
          const isAssistant = m.role === 'assistant' || m.role === 'agent'
          addMsg(isAssistant ? 'agent' : m.role, m.content, null, isAssistant ? 'assistant' : m.role)
        })
      }
    } catch (e) { }
  }
  async function newSession() {
    sessionId.value = 's' + Date.now()
    localStorage.setItem('ka_session', sessionId.value)
    messages.value = []; chatHistory.value = []
    // 欢迎语仅展示：不进 chatHistory、不显示反馈按钮
    messages.value.push({ role: 'agent', content: '新对话已创建。您好！左侧选择知识库，下方输入问题。', sources: null, done: true, greeting: true, id: Date.now() + Math.random() })
    // 反射数组：unshift 原地添加
    sessions.value.unshift({ sessionId: sessionId.value, title: '新对话' })
  }
  async function removeSession(sid) {
    try {
      await deleteSession(sid)
      const idx = sessions.value.findIndex(s => s.sessionId === sid)
      if (idx >= 0) sessions.value.splice(idx, 1)
      if (sid === sessionId.value) newSession()
    } catch (e) {
      await loadSessions()
    }
  }
  async function renameSid(sid, title) {
    try { await renameSession(sid, title); await loadSessions() } catch (e) { }
  }

  // ── Token Stats ──
  async function refreshTokenStats() {
    try { const { data } = await getTokenStats(); if (data.code === 200) tokenStats.value = data.data } catch (e) { }
  }

  // ── Notifs ──
  async function refreshNotifCount() {
    try { const { data } = await getNotifCount(); if (data.code === 200) notifCount.value = data.data } catch (e) { }
  }
  async function clearNotifs() {
    try { await markNotifsRead(); notifCount.value = 0 } catch (e) { }
  }

  return {
    messages, chatHistory, sessionId, currentTab, sending, sessions, tokenStats, notifCount,
    send, addMsg, loadSessions, switchSession, newSession, removeSession, renameSid,
    refreshTokenStats, refreshNotifCount, clearNotifs
  }
})
