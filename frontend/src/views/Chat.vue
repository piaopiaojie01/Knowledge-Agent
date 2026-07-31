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
          <h3><span>{{ docView.title }}</span><span @click="docView = null" style="cursor:pointer;font-size:20px">&times;</span></h3>
          <div style="color:#64748b;font-size:12px;margin-bottom:12px">
            {{ docView.fileType }} · {{ docView.chunkCount || 0 }} 分块 · {{ docView.createdAt?.substring(0,10) }}
          </div>
          <div style="white-space:pre-wrap;overflow-y:auto;flex:1;color:#cbd5e1;font-size:13px;line-height:1.7;background:#0b1120;border:1px solid #1e293b;border-radius:10px;padding:16px 18px">{{ docView.content }}</div>
        </div>
      </div>
      <div class="input-area" v-if="chat.currentTab === 'chat'">
        <textarea v-model="question" placeholder="输入问题..." rows="2"
          @keydown.enter.exact.prevent="sendMsg" />
        <button @click="sendMsg" :disabled="chat.sending || !question.trim()">
          <span v-if="chat.sending" class="spin"></span>
          {{ chat.sending ? '思考中' : '发 送' }}
        </button>
      </div>
      <div class="messages" v-if="chat.currentTab === 'docs'" style="display:block">
        <h3 style="color:#94a3b8;font-size:14px;margin-bottom:16px">文档列表</h3>
        <table class="doc-table">
          <thead><tr><th>标题</th><th>类型</th><th>分块</th><th>日期</th><th></th></tr></thead>
          <tbody>
            <tr v-for="d in docs" :key="d.id">
              <td><span class="doc-title" @click="viewDoc(d.id)" style="color:#3b82f6;cursor:pointer">{{ d.title }}</span></td>
              <td><span :class="['doc-type', d.fileType]">{{ d.fileType }}</span></td>
              <td style="color:#64748b">{{ d.chunkCount || 0 }} 分块</td>
              <td style="color:#64748b;font-size:12px">{{ d.createdAt?.substring(0,10) }}</td>
              <td><span @click="removeDoc(d.id)" style="color:#64748b;cursor:pointer">✕</span></td>
            </tr>
            <tr v-if="!docs.length"><td colspan="5" style="color:#64748b;padding:40px;text-align:center">请先在左侧选择一个知识库</td></tr>
          </tbody>
        </table>
      </div>
      <div class="messages" v-if="chat.currentTab === 'search'" style="display:block">
        <div style="display:flex;gap:12px;margin-bottom:16px">
          <input v-model="searchQuery" @keyup.enter="doSearch" placeholder="语义搜索知识库..."
            style="flex:1;padding:12px 16px;background:#0b1120;border:1px solid #1e293b;border-radius:10px;color:#e2e8f0;font-size:14px" />
          <button @click="doSearch" style="padding:12px 24px;background:linear-gradient(135deg,#2563eb,#4f46e5);color:#fff;border:none;border-radius:10px;cursor:pointer;font-weight:600">
            <span v-if="searching" class="spin"></span>{{ searching ? '检索中' : '搜索' }}
          </button>
        </div>
        <div v-for="r in searchResults" :key="r.rank"
          style="background:#111827;border:1px solid #1e293b;border-radius:10px;padding:14px 18px;margin-bottom:10px">
          <div style="display:flex;justify-content:space-between;margin-bottom:6px">
            <span style="color:#3b82f6;font-weight:600;font-size:14px">{{ r.title }}</span>
            <span style="color:#22c55e;font-size:12px;font-weight:600">{{ (r.score * 100).toFixed(1) }}%</span>
          </div>
          <div style="color:#94a3b8;font-size:13px;line-height:1.6">{{ r.content?.substring(0, 300) }}</div>
          <div style="color:#475569;font-size:11px;margin-top:6px">来源: {{ r.kb_name }}</div>
        </div>
        <div v-if="searchDone && !searchResults.length" style="color:#64748b;text-align:center;padding:40px">
          {{ searchQuery ? '未找到相关结果' : '输入关键词搜索知识库' }}
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, watch, nextTick, onMounted } from 'vue'
import { marked } from 'marked'
import DOMPurify from 'dompurify'
import Sidebar from '../components/Sidebar.vue'
import TopBar from '../components/TopBar.vue'
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

async function loadDocs() {
  const sbs = [...kb.selectedKbs]
  if (!sbs.length) { docs.value = []; return }
  docs.value = []
  for (const kbId of sbs) {
    try {
      const { data } = await getDocs(kbId)
      if (data.code === 200) docs.value.push(...data.data)
    } catch (e) { }
  }
}
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
  await kb.load()
  await kb.loadPerms()
  await chat.loadSessions()
  await chat.switchSession(chat.sessionId)
  await chat.refreshTokenStats()
  await chat.refreshNotifCount()
})
</script>
