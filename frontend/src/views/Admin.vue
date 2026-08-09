<template>
  <div class="admin-page">
    <aside class="admin-side">
      <div class="brand" style="padding:0 8px;margin-bottom:20px">
        <div class="logo-mark sm">✦</div>
        <h1 style="font-size:14px">管理后台</h1>
      </div>
      <div v-for="s in sections" :key="s.key"
        :class="['anav-item', { active: tab === s.key }]" @click="tab = s.key">
        <span class="anav-icon">{{ s.icon }}</span>{{ s.name }}
        <span v-if="s.key === 'notif' && chat.notifCount" class="notif-badge" style="margin-left:auto">{{ chat.notifCount }}</span>
      </div>
      <button class="btn-ghost" style="margin-top:auto;justify-content:center" @click="auth.adminOpen = false">← 返回主界面</button>
    </aside>

    <main class="admin-main">
      <!-- ── 概览 ── -->
      <div v-if="tab === 'dash'">
        <h2 class="page-title">概览</h2>
        <div class="stat-cards">
          <div class="stat-card"><div class="sc-num">{{ stats.userCount ?? '—' }}</div><div class="sc-label">注册用户</div></div>
          <div class="stat-card"><div class="sc-num">{{ stats.kbCount ?? '—' }}</div><div class="sc-label">知识库</div></div>
          <div class="stat-card"><div class="sc-num">{{ stats.docCount ?? '—' }}</div><div class="sc-label">文档</div></div>
          <div class="stat-card"><div class="sc-num">{{ fbGoodRate }}</div><div class="sc-label">反馈好评率</div></div>
        </div>
        <div class="panel">
          <div class="panel-title">服务状态</div>
          <div class="status" style="font-size:13px">
            <span><span class="dot" :class="{ on: health.agent }"></span>Agent (8000)</span>
            <span><span class="dot" :class="{ on: health.backend }"></span>Backend (8080)</span>
          </div>
        </div>
      </div>

      <!-- ── 用户管理 ── -->
      <div v-if="tab === 'users'">
        <h2 class="page-title">用户管理</h2>
        <div class="panel">
          <div class="panel-title">新建用户</div>
          <div style="display:flex;gap:9px;flex-wrap:wrap">
            <input v-model="nu" class="input" style="flex:1;min-width:140px" placeholder="用户名" />
            <input v-model="np" type="password" class="input" style="flex:1;min-width:140px" placeholder="密码" />
            <button class="btn btn-green" style="padding:10px 20px;font-size:13px" @click="createOne">新建</button>
          </div>
          <div v-if="userMsg" style="font-size:12px;color:var(--text-2);margin-top:9px">{{ userMsg }}</div>
        </div>
        <div class="panel">
          <div class="panel-title">批量导入（每行一个：用户名,密码）</div>
          <textarea v-model="csv" class="input" rows="4" style="width:100%;resize:vertical"
            placeholder="zhangsan,123456&#10;lisi,123456"></textarea>
          <div style="display:flex;align-items:center;gap:12px;margin-top:10px">
            <button class="btn" style="padding:9px 20px;font-size:13px" @click="batchCreate">导入</button>
            <span v-if="batchResult" style="font-size:12px;color:var(--text-2)">{{ batchResult }}</span>
          </div>
        </div>
        <div class="panel" style="padding:10px 0 4px">
          <div class="panel-title" style="padding:0 22px">用户列表</div>
          <table class="doc-table">
            <thead><tr><th>ID</th><th>用户名</th><th>角色</th><th>用量</th><th></th></tr></thead>
            <tbody>
              <tr v-for="u in users" :key="u.id">
                <td>{{ u.id }}</td><td>{{ u.username }}</td><td>{{ u.role }}</td>
                <td>{{ ((u.storageUsed || 0) / 1048576).toFixed(1) }} MB</td>
                <td><span v-if="u.username !== 'admin'" class="icon-btn danger" @click="removeUser(u.id)">✕</span></td>
              </tr>
              <tr v-if="!users.length"><td colspan="5"><div class="empty-state">暂无用户</div></td></tr>
            </tbody>
          </table>
        </div>
      </div>

      <!-- ── 权限管理 ── -->
      <div v-if="tab === 'perm'">
        <h2 class="page-title">权限管理</h2>
        <div class="panel">
          <div class="panel-title">授权 / 变更权限</div>
          <div style="display:flex;gap:9px;flex-wrap:wrap">
            <input v-model="permUser" class="input" style="flex:1;min-width:130px" placeholder="用户名" />
            <select v-model="permKb" class="select" style="flex:1;min-width:150px;padding:10px">
              <option value="" disabled>选择知识库</option>
              <option v-for="k in kb.kbs" :key="k.id" :value="String(k.id)">{{ k.name }}</option>
            </select>
            <select v-model="permType" class="select" style="padding:10px">
              <option>READ</option><option>WRITE</option><option>ADMIN</option>
            </select>
            <button class="btn" style="padding:10px 20px;font-size:13px" @click="doGrant">授权</button>
            <button class="btn-ghost danger" style="padding:10px 16px" @click="doRevoke">回收权限</button>
          </div>
          <div v-if="permMsg" style="font-size:12px;margin-top:10px"
            :style="{ color: permOk ? 'var(--green)' : 'var(--red)' }">{{ permMsg }}</div>
          <div style="font-size:12px;color:var(--text-3);margin-top:10px">
            仅知识库管理员可执行；已有权限时「授权」会升级为新的权限类型，「回收」移除该用户在此知识库的全部权限。
          </div>
        </div>
      </div>

      <!-- ── 用户反馈 ── -->
      <div v-if="tab === 'fb'">
        <h2 class="page-title">用户反馈</h2>
        <div v-if="!feedbacks.length" class="empty-state">暂无反馈</div>
        <div v-for="f in feedbacks" :key="f.id" class="fb-card">
          <div class="fb-head">
            <span :class="['rating-badge', f.rating === 1 ? 'up' : f.rating === -1 ? 'down' : '']">
              {{ f.rating === 1 ? '👍 有帮助' : f.rating === -1 ? '👎 没帮助' : '未评分' }}
            </span>
            <span class="fb-time">{{ fmtTime(f.createdAt) }}</span>
            <span class="fb-meta">用户 #{{ f.userId }} · 会话 {{ (f.sessionId || '').substring(0, 8) }}</span>
          </div>
          <div class="fb-q">Q：{{ f.question }}</div>
          <div class="fb-a" :class="{ expanded: expanded.has(f.id) }" @click="toggleExpand(f.id)">A：{{ f.answer }}</div>
          <div v-if="f.comment" class="fb-comment">💬 {{ f.comment }}</div>
        </div>
      </div>

      <!-- ── 系统通知 ── -->
      <div v-if="tab === 'notif'">
        <h2 class="page-title" style="display:flex;justify-content:space-between;align-items:center">
          系统通知
          <button class="btn-ghost btn-sm" @click="markAllRead">全部已读</button>
        </h2>
        <div v-if="!notifs.length" class="empty-state">暂无通知</div>
        <div v-for="n in notifs" :key="n.id" :class="['ntf-item', { unread: !n.isRead }]">
          <span class="ntf-type">{{ n.type }}</span>
          <span class="ntf-msg">{{ n.message }}</span>
          <span class="ntf-time">{{ fmtTime(n.createdAt) }}</span>
        </div>
      </div>

      <!-- ── 审计日志 ── -->
      <div v-if="tab === 'audit'">
        <h2 class="page-title">审计日志</h2>
        <div class="panel" style="padding:10px 0 4px">
          <table class="doc-table">
            <thead><tr><th>时间</th><th>用户</th><th>操作</th><th>对象</th><th>IP</th><th>详情</th></tr></thead>
            <tbody>
              <tr v-for="a in audits" :key="a.id">
                <td style="white-space:nowrap;font-size:12px">{{ fmtTime(a.createdAt) }}</td>
                <td>{{ a.username }}</td>
                <td><span class="ntf-type">{{ a.action }}</span></td>
                <td>{{ a.target }}</td>
                <td style="font-size:12px">{{ a.ip }}</td>
                <td style="max-width:260px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap" :title="a.detail">{{ a.detail }}</td>
              </tr>
              <tr v-if="!audits.length"><td colspan="6"><div class="empty-state">暂无日志</div></td></tr>
            </tbody>
          </table>
        </div>
      </div>
    </main>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, watch } from 'vue'
import { useAuthStore } from '../stores/auth'
import { useKBStore } from '../stores/kb'
import { useChatStore } from '../stores/chat'
import {
  getAdminUsers, createUser, deleteUser, batchUsers, getAdminStats, getAuditLog,
  listFeedback, getNotifications, markNotifsRead, grantPerm, revokePerm
} from '../api'

const auth = useAuthStore()
const kb = useKBStore()
const chat = useChatStore()
const tab = ref('dash')
const sections = [
  { key: 'dash', name: '概览', icon: '📊' },
  { key: 'users', name: '用户管理', icon: '👤' },
  { key: 'perm', name: '权限管理', icon: '🔑' },
  { key: 'fb', name: '用户反馈', icon: '💬' },
  { key: 'notif', name: '系统通知', icon: '🔔' },
  { key: 'audit', name: '审计日志', icon: '📜' },
]

// ── 概览 ──
const stats = ref({})
const health = ref({ agent: false, backend: false })
const feedbacks = ref([])
const fbGoodRate = computed(() => {
  const rated = feedbacks.value.filter(f => f.rating === 1 || f.rating === -1)
  if (!rated.length) return '—'
  return Math.round(rated.filter(f => f.rating === 1).length / rated.length * 100) + '%'
})
async function loadDash() {
  try { const { data } = await getAdminStats(); if (data.code === 200) stats.value = data.data } catch (e) { }
  try { const r = await fetch('/api/health'); const { data } = await r.json(); health.value = data } catch (e) { }
  loadFeedback()
}

// ── 用户 ──
const users = ref([])
const nu = ref(''); const np = ref(''); const userMsg = ref('')
const csv = ref(''); const batchResult = ref('')
async function loadUsers() {
  try { const { data } = await getAdminUsers(); if (data.code === 200) users.value = data.data } catch (e) { }
}
async function createOne() {
  if (!nu.value || !np.value) return
  try {
    const { data } = await createUser(nu.value, np.value)
    if (data.code === 200) { nu.value = ''; np.value = ''; userMsg.value = '创建成功'; await loadUsers() }
    else userMsg.value = data.message || '创建失败'
  } catch (e) { userMsg.value = '创建失败' }
}
async function removeUser(id) {
  if (!confirm('确定删除该用户？')) return
  try {
    const { data } = await deleteUser(id)
    if (data.code !== 200) alert(data.message || '删除失败')
    await loadUsers()
  } catch (e) { }
}
async function batchCreate() {
  if (!csv.value.trim()) return
  try {
    const { data } = await batchUsers(csv.value)
    if (data.code === 200) {
      batchResult.value = `成功 ${data.data.ok} 个，失败 ${data.data.failed} 个`
      csv.value = ''; await loadUsers()
    } else batchResult.value = data.message || '导入失败'
  } catch (e) { batchResult.value = '导入失败' }
}

// ── 权限 ──
const permUser = ref(''); const permKb = ref(''); const permType = ref('READ')
const permMsg = ref(''); const permOk = ref(true)
async function doGrant() {
  if (!permUser.value || !permKb.value) { permOk.value = false; permMsg.value = '请填写用户名并选择知识库'; return }
  try {
    const { data } = await grantPerm(permUser.value, permKb.value, permType.value)
    permOk.value = data.code === 200
    permMsg.value = data.message || (data.code === 200 ? '授权成功' : '授权失败')
  } catch (e) { permOk.value = false; permMsg.value = '操作失败' }
}
async function doRevoke() {
  if (!permUser.value || !permKb.value) { permOk.value = false; permMsg.value = '请填写用户名并选择知识库'; return }
  if (!confirm(`回收 ${permUser.value} 在该知识库的全部权限？`)) return
  try {
    const { data } = await revokePerm(permUser.value, permKb.value)
    permOk.value = data.code === 200
    permMsg.value = data.message || (data.code === 200 ? '权限已回收' : '回收失败')
  } catch (e) { permOk.value = false; permMsg.value = '操作失败' }
}

// ── 反馈 ──
const expanded = ref(new Set())
function toggleExpand(id) {
  const s = new Set(expanded.value)
  s.has(id) ? s.delete(id) : s.add(id)
  expanded.value = s
}
async function loadFeedback() {
  try { const { data } = await listFeedback(); if (data.code === 200) feedbacks.value = data.data } catch (e) { }
}

// ── 通知 ──
const notifs = ref([])
async function loadNotifs() {
  try { const { data } = await getNotifications(); if (data.code === 200) notifs.value = data.data } catch (e) { }
}
async function markAllRead() {
  try { await markNotifsRead(); await loadNotifs(); await chat.refreshNotifCount() } catch (e) { }
}

// ── 审计 ──
const audits = ref([])
async function loadAudit() {
  try { const { data } = await getAuditLog(); if (data.code === 200) audits.value = data.data } catch (e) { }
}

const fmtTime = (t) => t ? String(t).replace('T', ' ').substring(0, 19) : ''

// 各分区懒加载，每次切入都刷新保证数据实时
watch(tab, (t) => {
  if (t === 'dash') loadDash()
  else if (t === 'users') loadUsers()
  else if (t === 'fb') loadFeedback()
  else if (t === 'notif') loadNotifs()
  else if (t === 'audit') loadAudit()
})

onMounted(async () => {
  if (!kb.kbs.length) await kb.load()
  loadDash()
})
</script>
