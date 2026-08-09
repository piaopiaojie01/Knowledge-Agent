<template>
  <div style="display:flex;flex:1;overflow:hidden">
    <Sidebar />
    <div class="main-area">
      <TopBar />
      <div class="messages" ref="msgContainer" v-show="chat.currentTab === 'chat'">
        <div v-for="m in chat.messages" :key="m.id" :class="['msg', m.role]">
          <div v-html="renderContent(m)"></div>
          <div v-if="m.sources && m.sources.length" class="src-block">
            <div class="src-head">参考来源</div>
            <div v-for="(s, i) in m.sources" :key="(s.title || 'src') + '-' + i" class="src-item">
              <span class="src-dot">·</span>{{ s.title || '未知' }}
              <span class="src-pct">{{ ((s.score || 0) * 100).toFixed(0) }}%</span>
            </div>
          </div>
          <div v-if="m.role === 'agent' && m.done && !m.greeting && !isErrorMsg(m)" class="fb-row">
            <span v-for="r in [1, -1]" :key="r"
              :class="['fb-btn', { active: fbMap[m.id] === r, done: fbMap[m.id] !== undefined }]"
              :title="r === 1 ? '有帮助' : '没帮助'"
              @click="rate(m, r)">{{ r === 1 ? '👍' : '👎' }}</span>
          </div>
        </div>
        <div ref="msgEnd"></div>
      </div>
      <!-- 文档预览模态 -->
      <div v-if="docView" class="admin-overlay" @click.self="docView = null">
        <div class="admin-box" style="width:720px;display:flex;flex-direction:column">
          <h3><span>{{ docView.title }}</span><span class="modal-close" @click="docView = null">&times;</span></h3>
          <div class="modal-meta">
            {{ docView.fileType }} · {{ docView.chunkCount || 0 }} 分块 · {{ docView.createdAt?.substring(0,10) }}
          </div>
          <div class="doc-content">{{ docView.content }}</div>
        </div>
      </div>
      <div class="input-area" v-if="chat.currentTab === 'chat'">
        <textarea v-model="question" placeholder="输入问题，Enter 发送..." rows="2"
          @keydown.enter.exact.prevent="sendMsg" />
        <button class="btn" @click="sendMsg" :disabled="chat.sending || !question.trim()">
          <span v-if="chat.sending" class="spin"></span>
          {{ chat.sending ? '思考中' : '发 送' }}
        </button>
      </div>
      <div class="messages" v-if="chat.currentTab === 'docs'" style="display:block">
        <h3 class="page-title">文档列表</h3>
        <table class="doc-table">
          <thead><tr><th>标题</th><th>类型</th><th>状态</th><th>分块</th><th>日期</th><th></th></tr></thead>
          <tbody>
            <tr v-for="d in docs" :key="d.id">
              <td><span class="doc-title" @click="viewDoc(d.id)">{{ d.title }}</span></td>
              <td><span :class="['doc-type', d.fileType]">{{ d.fileType }}</span></td>
              <td>
                <DocStatus :doc="d" />
              </td>
              <td style="color:var(--text-3)">{{ d.chunkCount || 0 }} 分块</td>
              <td style="color:var(--text-3);font-size:12px">{{ d.createdAt?.substring(0,10) }}</td>
              <td><span class="icon-btn danger" @click="removeDoc(d.id)">✕</span></td>
            </tr>
            <tr v-if="!docs.length"><td colspan="6"><div class="empty-state">请先在左侧选择一个知识库</div></td></tr>
          </tbody>
        </table>
      </div>
      <div class="messages" v-if="chat.currentTab === 'search'" style="display:block">
        <div class="search-row">
          <input v-model="searchQuery" @keyup.enter="doSearch" placeholder="语义搜索知识库..." class="search-input" />
          <button class="btn" @click="doSearch">
            <span v-if="searching" class="spin"></span>{{ searching ? '检索中' : '搜索' }}
          </button>
        </div>
        <div v-for="r in searchResults" :key="r.rank" class="search-card">
          <div class="sc-head">
            <span class="sc-title">{{ r.title }}</span>
            <span class="sc-score">{{ (r.score * 100).toFixed(1) }}%</span>
          </div>
          <div class="sc-content">{{ r.content?.substring(0, 300) }}</div>
          <div class="sc-source">来源: {{ r.kb_name }}</div>
        </div>
        <div v-if="searchDone && !searchResults.length" class="empty-state">
          {{ searchQuery ? '未找到相关结果' : '输入关键词搜索知识库' }}
        </div>
      </div>
    </div>
    <!-- 入库落定通知（成功/失败），4 秒自动消失 -->
    <div class="toast-wrap">
      <div v-for="t in toasts" :key="t.id" :class="['toast', { error: t.type === 'error' }]">
        <span class="t-icon">{{ t.type === 'error' ? '✕' : '✓' }}</span> {{ t.text }}
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, watch, nextTick, onMounted, onUnmounted } from 'vue'
import { marked } from 'marked'
import DOMPurify from 'dompurify'
import Sidebar from '../components/Sidebar.vue'
import TopBar from '../components/TopBar.vue'
import DocStatus from '../components/DocStatus.vue'
import { detectSettlements } from '../utils/ingest'
import { useChatStore } from '../stores/chat'
import { useKBStore } from '../stores/kb'
import { getDocs, getDoc, deleteDoc, searchDocs, ragSearch, sendFeedback } from '../api'

const chat = useChatStore()
const kb = useKBStore()
const question = ref('')
const docs = ref([])
const searchQuery = ref('')
const searchResults = ref([])
const searching = ref(false)
const searchDone = ref(false)
const msgContainer = ref(null)
const msgEnd = ref(null)
const docView = ref(null)
// 已反馈的消息：{ [msgId]: rating }
const fbMap = ref({})
// 渲染缓存：key=消息 id，raw 未变则复用 html（避免流式期间每个 delta 全量 parse）
const renderCache = new Map()

function renderContent(m) {
  if (!m.content) return ''
  const hit = renderCache.get(m.id)
  if (hit && hit.raw === m.content) return hit.html
  // Fix absolute chart/icon URLs → relative (走 Vite proxy / Nginx)
  const text = m.content.replace(/https?:\/\/localhost:8080\//g, '/')
  const html = DOMPurify.sanitize(marked.parse(text))
  renderCache.set(m.id, { raw: m.content, html })
  return html
}

function isErrorMsg(m) {
  return !m.content || m.content.startsWith('查询失败') || m.content === '网络连接失败'
}

async function rate(m, rating) {
  if (fbMap.value[m.id] !== undefined) return
  // 最近一条 user 消息作为 question
  const idx = chat.messages.indexOf(m)
  let q = ''
  for (let i = idx - 1; i >= 0; i--) {
    if (chat.messages[i].role === 'user') { q = chat.messages[i].content; break }
  }
  fbMap.value = { ...fbMap.value, [m.id]: rating }
  try {
    await sendFeedback({ sessionId: chat.sessionId, question: q, answer: m.content, rating, comment: '' })
  } catch (e) { }
}

async function sendMsg() {
  const q = question.value.trim()
  if (!q || chat.sending) return
  // 立即清空输入框，避免流式等待期间用户的新输入被一并清掉
  question.value = ''
  await chat.send(q)
  nextTick(() => msgEnd.value?.scrollIntoView({ behavior: 'smooth' }))
}

// 入库落定通知：{ id, text, type: 'success' | 'error' }
const toasts = ref([])
function notify(text, type) {
  const id = Date.now() + Math.random()
  toasts.value.push({ id, text, type })
  setTimeout(() => { toasts.value = toasts.value.filter(t => t.id !== id) }, 4000)
}
// 各文档上一轮状态，用于检测 PROCESSING → ACTIVE/FAILED 落定并通知
const prevDocStatus = new Map()

async function loadDocs() {
  const sbs = [...kb.selectedKbs]
  if (!sbs.length) { docs.value = []; scheduleDocPoll(); return }
  docs.value = []
  for (const kbId of sbs) {
    try {
      const { data } = await getDocs(kbId)
      if (data.code === 200) docs.value.push(...data.data)
    } catch (e) { }
  }
  // 只在「解析中 → 落定」的跳变上通知，首次加载不打扰
  for (const n of detectSettlements(prevDocStatus, docs.value)) {
    notify(n.text, n.type)
  }
  prevDocStatus.clear()
  for (const d of docs.value) prevDocStatus.set(d.id, d.docStatus)
  scheduleDocPoll()
}

// 还有文档在解析中时每 5 秒刷新一次，全部落定（ACTIVE/FAILED）后停止轮询
let docPollTimer = null
function scheduleDocPoll() {
  clearTimeout(docPollTimer); docPollTimer = null
  if (docs.value.some(d => d.docStatus === 'PROCESSING')) {
    docPollTimer = setTimeout(loadDocs, 5000)
  }
}
// Sidebar 上传成功后通过全局事件通知刷新（新文档是 PROCESSING，会触发上面的轮询）
function onDocsUpdated() { loadDocs() }
async function removeDoc(id) { if (confirm('确定删除？')) { try { await deleteDoc(id); loadDocs() } catch (e) { } } }
async function viewDoc(id) {
  try {
    const { data } = await getDoc(id)
    if (data.code === 200) docView.value = data.data
  } catch (e) { }
}

async function doSearch() {
  if (!searchQuery.value.trim()) return
  searching.value = true; searchDone.value = false; searchResults.value = []
  try {
    const kbNames = kb.kbs.filter(k => kb.selectedKbs.has(k.id)).map(k => k.name)
    const { data } = await ragSearch(searchQuery.value.trim(), kbNames.length ? kbNames : null, 10)
    if (data.code === 200 && data.data?.sources) {
      searchResults.value = data.data.sources.map((r, i) => ({ ...r, rank: i + 1 }))
    }
  } catch (e) { }
  searching.value = false; searchDone.value = true
}

watch(() => chat.currentTab, (tab) => { if (tab === 'docs') loadDocs() })
// 切换/新建会话：清空反馈记录与渲染缓存，避免跨会话残留
watch(() => chat.sessionId, () => { fbMap.value = {}; renderCache.clear() })
watch(() => chat.messages.length, () => nextTick(() => msgEnd.value?.scrollIntoView({ behavior: 'smooth' })))
// 流式：监听最后一条消息内容变化，自动跟随滚动
watch(() => chat.messages[chat.messages.length - 1]?.content?.length, () => nextTick(() => msgEnd.value?.scrollIntoView({ behavior: 'smooth' })))

onMounted(async () => {
  window.addEventListener('docs-updated', onDocsUpdated)
  await kb.load()
  await kb.loadPerms()
  await chat.loadSessions()
  await chat.switchSession(chat.sessionId)
  await chat.refreshTokenStats()
  await chat.refreshNotifCount()
})

onUnmounted(() => {
  clearTimeout(docPollTimer)
  window.removeEventListener('docs-updated', onDocsUpdated)
})
</script>
