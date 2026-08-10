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
      <button class="btn-ghost" style="margin-top:auto;justify-content:center"
        @click="auth.adminOpen = false; auth.adminTab = 'dash'">← 返回主界面</button>
    </aside>

    <main class="admin-main">
      <!-- ── 概览 ── -->
      <div v-if="tab === 'dash'">
        <h2 class="page-title">概览</h2>
        <div class="stat-cards">
          <div class="stat-card" title="进入用户管理" @click="tab = 'users'"><div class="sc-num">{{ stats.userCount ?? '—' }}</div><div class="sc-label">注册用户</div></div>
          <div class="stat-card" title="进入知识库管理" @click="tab = 'kb'"><div class="sc-num">{{ stats.kbCount ?? '—' }}</div><div class="sc-label">知识库</div></div>
          <div class="stat-card" title="进入文档管理" @click="tab = 'docs'"><div class="sc-num">{{ stats.docCount ?? '—' }}</div><div class="sc-label">文档</div></div>
          <div class="stat-card" title="进入用户反馈" @click="tab = 'fb'"><div class="sc-num">{{ fbGoodRate }}</div><div class="sc-label">反馈好评率</div></div>
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
            <thead><tr><th>ID</th><th>用户名</th><th>角色</th><th>状态</th><th>用量</th><th>操作</th></tr></thead>
            <tbody>
              <tr v-for="u in users" :key="u.id">
                <td>{{ u.id }}</td><td>{{ u.username }}</td><td>{{ u.role }}</td>
                <td><span class="ntf-type" :class="{ up: u.isActive }">{{ u.isActive ? '启用' : '禁用' }}</span></td>
                <td>{{ ((u.storageUsed || 0) / 1048576).toFixed(1) }} MB</td>
                <td style="white-space:nowrap">
                  <span v-if="u.username !== auth.username" class="btn-ghost btn-sm" @click="toggleUserStatus(u)">{{ u.isActive ? '禁用' : '启用' }}</span>
                  <span v-if="u.username !== auth.username" class="btn-ghost btn-sm" @click="resetPw(u)">重置密码</span>
                  <span v-if="u.username !== auth.username" class="btn-ghost btn-sm" @click="forceLogout(u)">强制登出</span>
                  <span v-if="u.username !== auth.username" class="icon-btn danger" title="删除用户" @click="removeUser(u.id)">✕</span>
                </td>
              </tr>
              <tr v-if="!users.length"><td colspan="6"><div class="empty-state">暂无用户</div></td></tr>
            </tbody>
          </table>
        </div>
      </div>

      <!-- ── 知识库管理 ── -->
      <div v-if="tab === 'kb'">
        <h2 class="page-title">知识库管理</h2>
        <div class="panel">
          <div class="panel-title">新建知识库</div>
          <div style="display:flex;gap:9px;flex-wrap:wrap">
            <input v-model="newKbName" class="input" style="flex:1;min-width:180px" placeholder="知识库名称" />
            <button class="btn btn-green" style="padding:10px 20px;font-size:13px" @click="createKb">新建</button>
          </div>
          <div v-if="kbMsg" style="font-size:12px;color:var(--text-2);margin-top:9px">{{ kbMsg }}</div>
        </div>
        <div class="panel" style="padding:10px 0 4px">
          <div class="panel-title" style="padding:0 22px">知识库列表</div>
          <table class="doc-table">
            <thead><tr><th>ID</th><th>名称</th><th>描述</th><th>可见性</th><th>操作</th></tr></thead>
            <tbody>
              <tr v-for="k in adminKbs" :key="k.id">
                <td>{{ k.id }}</td>
                <td>{{ k.name }}</td>
                <td style="max-width:280px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">{{ k.description || '—' }}</td>
                <td>{{ k.isPublic ? '公开' : '私有' }}</td>
                <td>
                  <span class="btn-ghost btn-sm" @click="togglePublic(k)">{{ k.isPublic ? '设为私有' : '设为公开' }}</span>
                  <span class="btn-ghost btn-sm" @click="openDocs(k)">文档</span>
                  <span class="icon-btn danger" title="删除知识库" @click="removeKb(k)">✕</span>
                </td>
              </tr>
              <tr v-if="!adminKbs.length"><td colspan="5"><div class="empty-state">暂无知识库</div></td></tr>
            </tbody>
          </table>
        </div>
      </div>

      <!-- ── 文档管理 ── -->
      <div v-if="tab === 'docs'">
        <h2 class="page-title">文档管理</h2>
        <div class="panel">
          <div class="panel-title">选择知识库</div>
          <select v-model="docsKbId" class="select" style="padding:10px;min-width:220px" @change="loadDocs">
            <option value="" disabled>选择知识库</option>
            <option v-for="k in adminKbs" :key="k.id" :value="String(k.id)">{{ k.name }}</option>
          </select>
        </div>
        <div class="panel" style="padding:10px 0 4px">
          <div class="panel-title" style="padding:0 22px">文档列表</div>
          <table class="doc-table">
            <thead><tr><th>ID</th><th>标题</th><th>类型</th><th>状态</th><th>进度</th><th>创建时间</th><th></th></tr></thead>
            <tbody>
              <tr v-for="d in docList" :key="d.id">
                <td>{{ d.id }}</td>
                <td style="max-width:260px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">{{ d.title }}</td>
                <td>{{ d.fileType }}</td>
                <td>{{ d.docStatus }}</td>
                <td>{{ d.ingestProgress ?? '—' }}</td>
                <td style="font-size:12px">{{ fmtTime(d.createdAt) }}</td>
                <td><span class="icon-btn danger" title="删除文档" @click="removeDoc(d)">✕</span></td>
              </tr>
              <tr v-if="!docList.length"><td colspan="7"><div class="empty-state">该知识库暂无文档</div></td></tr>
            </tbody>
          </table>
        </div>
      </div>

      <!-- ── 模型配置 ── -->
      <div v-if="tab === 'model'">
        <h2 class="page-title">模型配置</h2>
        <div class="panel">
          <div class="panel-title">大模型设置（保存后对后续问答立即生效）</div>
          <div style="display:flex;flex-direction:column;gap:11px;max-width:600px">
            <div style="display:flex;align-items:center;gap:10px">
              <label style="width:110px;font-size:13px;color:var(--text-2)">模型名</label>
              <input v-model="mcModel" class="input" style="flex:1" list="model-suggestions" placeholder="deepseek-v4-flash" />
              <datalist id="model-suggestions">
                <option value="deepseek-v4-flash"></option>
                <option value="deepseek-chat"></option>
                <option value="gpt-4o"></option>
                <option value="gpt-4o-mini"></option>
                <option value="qwen-plus"></option>
                <option value="glm-4-plus"></option>
              </datalist>
            </div>
            <div style="display:flex;align-items:center;gap:10px">
              <label style="width:110px;font-size:13px;color:var(--text-2)">接口地址</label>
              <input v-model="mcBaseUrl" class="input" style="flex:1" placeholder="https://api.deepseek.com" />
            </div>
            <div style="display:flex;align-items:center;gap:10px">
              <label style="width:110px;font-size:13px;color:var(--text-2)">API Key</label>
              <input v-model="mcApiKey" type="password" class="input" style="flex:1" :placeholder="mcKeyPlaceholder" autocomplete="off" />
            </div>
            <div style="display:flex;align-items:center;gap:10px">
              <label style="width:110px;font-size:13px;color:var(--text-2)">Temperature</label>
              <input v-model="mcTemp" type="number" min="0" max="2" step="0.1" class="input" style="width:130px" />
            </div>
            <div style="display:flex;align-items:center;gap:10px">
              <label style="width:110px;font-size:13px;color:var(--text-2)">Max Tokens</label>
              <input v-model="mcMaxTokens" type="number" min="1" step="1" class="input" style="width:150px" />
            </div>
            <div style="display:flex;align-items:center;gap:12px;margin-top:4px">
              <button class="btn btn-green" style="padding:10px 24px;font-size:13px" @click="saveModelConfig">保存</button>
              <span v-if="mcMsg" style="font-size:12px" :style="{ color: mcOk ? 'var(--green)' : 'var(--red)' }">{{ mcMsg }}</span>
            </div>
            <div style="font-size:12px;color:var(--text-3)">
              留空字段沿用 Agent 环境变量；API Key 留空表示不修改。保存后立即对新的问答生效，无需重启。
            </div>
          </div>
        </div>
      </div>

      <!-- ── 技能管理 ── -->
      <div v-if="tab === 'skills'">
        <h2 class="page-title">技能管理</h2>
        <div class="panel" style="padding:10px 0 4px">
          <div class="panel-title" style="padding:0 22px">内置技能（停用后 LLM 将不再获得该工具）</div>
          <table class="doc-table">
            <thead><tr><th>技能名</th><th>说明</th><th>状态</th><th></th></tr></thead>
            <tbody>
              <tr v-for="s in skills" :key="s.name">
                <td><code>{{ s.name }}</code></td>
                <td style="max-width:360px">{{ s.description }}</td>
                <td>
                  <span class="ntf-type" :class="{ up: s.enabled }">{{ s.enabled ? '已启用' : '已停用' }}</span>
                </td>
                <td>
                  <button class="btn-ghost btn-sm" @click="toggleSkill(s)">
                    {{ s.enabled ? '停用' : '启用' }}
                  </button>
                </td>
              </tr>
              <tr v-if="!skills.length"><td colspan="4"><div class="empty-state">暂无技能</div></td></tr>
            </tbody>
          </table>
        </div>
      </div>

      <!-- ── MCP 管理 ── -->
      <div v-if="tab === 'mcp'">
        <h2 class="page-title">MCP 管理</h2>
        <div class="panel">
          <div class="panel-title">添加 MCP 服务器</div>
          <div style="display:flex;gap:9px;flex-wrap:wrap">
            <input v-model="mcpName" class="input" style="flex:1;min-width:130px" placeholder="名称" />
            <input v-model="mcpUrl" class="input" style="flex:2;min-width:240px" placeholder="Streamable HTTP / SSE 地址，如 http://localhost:9000/mcp" />
            <input v-model="mcpDesc" class="input" style="flex:1;min-width:140px" placeholder="描述（可选）" />
            <button class="btn btn-green" style="padding:10px 20px;font-size:13px" @click="addMcp">添加</button>
          </div>
          <div v-if="mcpMsg" style="font-size:12px;margin-top:9px" :style="{ color: mcpOk ? 'var(--green)' : 'var(--red)' }">{{ mcpMsg }}</div>
        </div>
        <div class="panel" style="padding:10px 0 4px">
          <div class="panel-title" style="padding:0 22px">服务器列表</div>
          <table class="doc-table">
            <thead><tr><th>ID</th><th>名称</th><th>地址</th><th>状态</th><th></th></tr></thead>
            <tbody>
              <tr v-for="m in mcps" :key="m.id">
                <td>{{ m.id }}</td>
                <td>{{ m.name }}</td>
                <td style="max-width:320px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">{{ m.url }}</td>
                <td>
                  <span class="ntf-type" :class="{ up: m.enabled }">{{ m.enabled ? '已启用' : '已停用' }}</span>
                </td>
                <td>
                  <button class="btn-ghost btn-sm" @click="toggleMcp(m)">{{ m.enabled ? '停用' : '启用' }}</button>
                  <span class="icon-btn danger" title="删除" @click="removeMcp(m)">✕</span>
                </td>
              </tr>
              <tr v-if="!mcps.length"><td colspan="5"><div class="empty-state">暂无 MCP 服务器</div></td></tr>
            </tbody>
          </table>
          <div style="font-size:12px;color:var(--text-3);padding:0 22px 12px">
            说明：需安装 mcp SDK（requirements.txt 已包含）。工具名以「服务器名__工具名」暴露给 LLM；连接或调用失败不会阻塞问答。
          </div>
        </div>
      </div>

      <!-- ── 权限管理 ── -->
      <div v-if="tab === 'perm'">
        <h2 class="page-title">权限管理</h2>
        <div class="panel" style="display:flex;gap:10px;align-items:center;flex-wrap:wrap">
          <span class="btn-ghost btn-sm" :style="permMode === 'user' ? 'outline:1px solid var(--primary);color:var(--primary)' : ''"
            @click="permMode = 'user'">按用户</span>
          <span class="btn-ghost btn-sm" :style="permMode === 'kb' ? 'outline:1px solid var(--primary);color:var(--primary)' : ''"
            @click="permMode = 'kb'">按知识库</span>
        </div>

        <!-- 按用户视图 -->
        <div v-if="permMode === 'user'" class="panel" style="padding:10px 0 4px">
          <div class="panel-title" style="padding:0 22px;display:flex;align-items:center;flex-wrap:wrap;gap:10px">
            按用户授权
            <select v-model="permSelUser" class="select" style="padding:6px 10px;min-width:160px">
              <option value="" disabled>选择用户</option>
              <option v-for="u in users" :key="u.id" :value="u.id">{{ u.username }}{{ u.isActive ? '' : '（已禁用）' }}</option>
            </select>
          </div>
          <table class="doc-table">
            <thead><tr><th>知识库</th><th>可见性</th><th>权限</th><th></th></tr></thead>
            <tbody>
              <tr v-for="k in adminKbs" :key="k.id">
                <td>{{ k.name }}</td>
                <td>{{ k.isPublic ? '公开' : '私有' }}</td>
                <td>
                  <select :value="permTypeOf(permSelUser, k.id)" class="select" style="padding:6px 10px;min-width:130px"
                    @change="changePerm(userById(permSelUser), k, $event.target.value)">
                    <option value="">无权限</option>
                    <option value="READ">只读 READ</option>
                    <option value="WRITE">读写 WRITE</option>
                    <option value="ADMIN">管理 ADMIN</option>
                  </select>
                </td>
                <td>
                  <span v-if="permTypeOf(permSelUser, k.id)" class="icon-btn danger" title="回收权限"
                    @click="changePerm(userById(permSelUser), k, '')">✕</span>
                </td>
              </tr>
              <tr v-if="!adminKbs.length"><td colspan="4"><div class="empty-state">暂无知识库</div></td></tr>
            </tbody>
          </table>
        </div>

        <!-- 按知识库视图 -->
        <div v-if="permMode === 'kb'" class="panel" style="padding:10px 0 4px">
          <div class="panel-title" style="padding:0 22px;display:flex;align-items:center;flex-wrap:wrap;gap:10px">
            按知识库授权
            <select v-model="permSelKb" class="select" style="padding:6px 10px;min-width:160px">
              <option value="" disabled>选择知识库</option>
              <option v-for="k in adminKbs" :key="k.id" :value="k.id">{{ k.name }}</option>
            </select>
          </div>
          <table class="doc-table">
            <thead><tr><th>用户</th><th>状态</th><th>权限</th><th></th></tr></thead>
            <tbody>
              <tr v-for="u in users" :key="u.id">
                <td>{{ u.username }}</td>
                <td>{{ u.isActive ? '启用' : '禁用' }}</td>
                <td>
                  <select :value="permTypeOf(u.id, permSelKb)" class="select" style="padding:6px 10px;min-width:130px"
                    @change="changePerm(u, kbById(permSelKb), $event.target.value)">
                    <option value="">无权限</option>
                    <option value="READ">只读 READ</option>
                    <option value="WRITE">读写 WRITE</option>
                    <option value="ADMIN">管理 ADMIN</option>
                  </select>
                </td>
                <td>
                  <span v-if="permTypeOf(u.id, permSelKb)" class="icon-btn danger" title="回收权限"
                    @click="changePerm(u, kbById(permSelKb), '')">✕</span>
                </td>
              </tr>
              <tr v-if="!users.length"><td colspan="4"><div class="empty-state">暂无用户</div></td></tr>
            </tbody>
          </table>
        </div>

        <div class="panel">
          <div class="panel-title">快捷授权 / 回收</div>
          <div style="display:flex;gap:9px;flex-wrap:wrap">
            <input v-model="permUser" class="input" style="flex:1;min-width:130px" placeholder="用户名" />
            <select v-model="permKb" class="select" style="flex:1;min-width:150px;padding:10px">
              <option value="" disabled>选择知识库</option>
              <option v-for="k in adminKbs" :key="k.id" :value="String(k.id)">{{ k.name }}</option>
            </select>
            <select v-model="permType" class="select" style="padding:10px">
              <option value="READ">只读 READ</option>
              <option value="WRITE">读写 WRITE</option>
              <option value="ADMIN">管理 ADMIN</option>
            </select>
            <button class="btn" style="padding:10px 20px;font-size:13px" @click="doGrant">授权</button>
            <button class="btn-ghost danger" style="padding:10px 16px" @click="doRevoke">回收权限</button>
          </div>
          <div v-if="permMsg" style="font-size:12px;margin-top:10px"
            :style="{ color: permOk ? 'var(--green)' : 'var(--red)' }">{{ permMsg }}</div>
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
  getAdminUsers, createUser, deleteUser, updateUserStatus, resetUserPassword, forceLogoutUser,
  batchUsers, getAdminStats, getAuditLog, getAdminKbs, getAdminPerms,
  grantPermAdmin, revokePermAdmin,
  listFeedback, getNotifications, markNotifsRead,
  createKB, updateKB, deleteKB, getDocs, deleteDoc,
  getModelConfig, updateModelConfig,
  getSkills, updateSkill,
  getMcpServers, createMcpServer, updateMcpServer, deleteMcpServer
} from '../api'

const auth = useAuthStore()
const kb = useKBStore()
const chat = useChatStore()
// 从顶部模型徽标进入时，直接定位到「模型配置」分区
const tab = ref(auth.adminTab || 'dash')
const sections = [
  { key: 'dash', name: '概览', icon: '📊' },
  { key: 'users', name: '用户管理', icon: '👤' },
  { key: 'kb', name: '知识库管理', icon: '🗂️' },
  { key: 'docs', name: '文档管理', icon: '📄' },
  { key: 'model', name: '模型配置', icon: '🤖' },
  { key: 'skills', name: '技能管理', icon: '🧩' },
  { key: 'mcp', name: 'MCP 管理', icon: '🔌' },
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

// ── 知识库管理 ──
const newKbName = ref(''); const kbMsg = ref('')
const adminKbs = ref([])
async function createKb() {
  if (!newKbName.value.trim()) return
  try {
    const { data } = await createKB(newKbName.value.trim())
    kbMsg.value = data.code === 200 ? '创建成功' : (data.message || '创建失败')
    if (data.code === 200) { newKbName.value = ''; await loadAdminKbs() }
  } catch (e) { kbMsg.value = '创建失败' }
}
async function removeKb(k) {
  if (!confirm(`确定删除知识库「${k.name}」及其全部文档？`)) return
  try {
    const { data } = await deleteKB(k.id)
    if (data.code !== 200) alert(data.message || '删除失败')
    await loadAdminKbs()
    if (docsKbId.value === String(k.id)) { docsKbId.value = ''; docList.value = [] }
  } catch (e) { }
}
function openDocs(k) {
  docsKbId.value = String(k.id)
  tab.value = 'docs'
  loadDocs()
}
async function loadAdminKbs() {
  try {
    const { data } = await getAdminKbs()
    if (data.code === 200) adminKbs.value = data.data
  } catch (e) { }
}
async function togglePublic(k) {
  try {
    const { data } = await updateKB(k.id, { isPublic: !k.isPublic })
    if (data.code !== 200) alert(data.message || '操作失败')
    await loadAdminKbs()
  } catch (e) { }
}

// ── 文档管理 ──
const docsKbId = ref(''); const docList = ref([])
async function loadDocs() {
  if (!docsKbId.value) { docList.value = []; return }
  try {
    const { data } = await getDocs(docsKbId.value)
    if (data.code === 200) docList.value = data.data
  } catch (e) { }
}
async function removeDoc(d) {
  if (!confirm(`确定删除文档「${d.title}」？`)) return
  try {
    const { data } = await deleteDoc(d.id)
    if (data.code !== 200) alert(data.message || '删除失败')
    await loadDocs()
  } catch (e) { }
}

// ── 模型配置 ──
const mcModel = ref(''); const mcBaseUrl = ref(''); const mcApiKey = ref('')
const mcTemp = ref(0.3); const mcMaxTokens = ref(8192); const mcKeyPlaceholder = ref('')
const mcMsg = ref(''); const mcOk = ref(true)
async function loadModelConfig() {
  try {
    const { data } = await getModelConfig()
    if (data.code === 200) {
      mcModel.value = data.data.modelName || ''
      mcBaseUrl.value = data.data.baseUrl || ''
      mcApiKey.value = ''
      mcTemp.value = data.data.temperature ?? 0.3
      mcMaxTokens.value = data.data.maxTokens ?? 8192
      mcKeyPlaceholder.value = data.data.apiKey
        ? `已配置（${data.data.apiKey}），留空保持不变`
        : '未配置（使用 Agent 环境变量）'
    }
  } catch (e) { }
}
async function saveModelConfig() {
  try {
    const payload = {
      modelName: mcModel.value.trim(),
      baseUrl: mcBaseUrl.value.trim(),
      apiKey: mcApiKey.value.trim(),
      temperature: mcTemp.value === '' ? null : Number(mcTemp.value),
      maxTokens: mcMaxTokens.value === '' ? null : Number(mcMaxTokens.value)
    }
    const { data } = await updateModelConfig(payload)
    mcOk.value = data.code === 200
    mcMsg.value = data.message || (data.code === 200 ? '已保存' : '保存失败')
    if (data.code === 200) await loadModelConfig()
  } catch (e) { mcOk.value = false; mcMsg.value = '保存失败' }
}

// ── 技能管理 ──
const skills = ref([])
async function loadSkills() {
  try { const { data } = await getSkills(); if (data.code === 200) skills.value = data.data } catch (e) { }
}
async function toggleSkill(s) {
  try {
    const { data } = await updateSkill(s.name, { enabled: !s.enabled })
    if (data.code === 200) await loadSkills()
    else alert(data.message || '操作失败')
  } catch (e) { }
}

// ── MCP 管理 ──
const mcps = ref([])
const mcpName = ref(''); const mcpUrl = ref(''); const mcpDesc = ref('')
const mcpMsg = ref(''); const mcpOk = ref(true)
async function loadMcps() {
  try { const { data } = await getMcpServers(); if (data.code === 200) mcps.value = data.data } catch (e) { }
}
async function addMcp() {
  if (!mcpName.value.trim() || !mcpUrl.value.trim()) {
    mcpOk.value = false; mcpMsg.value = '名称与地址必填'; return
  }
  try {
    const { data } = await createMcpServer({
      name: mcpName.value.trim(), url: mcpUrl.value.trim(),
      description: mcpDesc.value.trim() || null
    })
    mcpOk.value = data.code === 200
    mcpMsg.value = data.message || (data.code === 200 ? '已添加' : '添加失败')
    if (data.code === 200) { mcpName.value = ''; mcpUrl.value = ''; mcpDesc.value = ''; await loadMcps() }
  } catch (e) { mcpOk.value = false; mcpMsg.value = '添加失败' }
}
async function toggleMcp(m) {
  try {
    const { data } = await updateMcpServer(m.id, { enabled: !m.enabled })
    if (data.code === 200) await loadMcps()
    else alert(data.message || '操作失败')
  } catch (e) { }
}
async function removeMcp(m) {
  if (!confirm(`确定删除 MCP 服务器「${m.name}」？`)) return
  try {
    const { data } = await deleteMcpServer(m.id)
    if (data.code !== 200) alert(data.message || '删除失败')
    await loadMcps()
  } catch (e) { }
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
async function toggleUserStatus(u) {
  const disabling = !!u.isActive
  const tip = disabling ? '禁用后该用户全部会话将立即登出，且无法登录。' : '启用后该用户可以正常登录。'
  if (!confirm(`确定${disabling ? '禁用' : '启用'}用户「${u.username}」？${tip}`)) return
  try {
    const { data } = await updateUserStatus(u.id, !u.isActive)
    if (data.code !== 200) alert(data.message || '操作失败')
    await loadUsers()
  } catch (e) { alert('操作失败') }
}
async function resetPw(u) {
  const pw = prompt(`请输入「${u.username}」的新密码（8位以上，需包含大小写字母、数字和特殊字符）`)
  if (!pw) return
  try {
    const { data } = await resetUserPassword(u.id, pw)
    if (data.code !== 200) alert(data.message || '重置失败')
    else alert('密码已重置，该用户已强制登出')
  } catch (e) { alert('操作失败') }
}
async function forceLogout(u) {
  if (!confirm(`确定强制登出用户「${u.username}」的全部会话？`)) return
  try {
    const { data } = await forceLogoutUser(u.id)
    if (data.code !== 200) alert(data.message || '操作失败')
    else alert('已强制该用户全部会话登出')
  } catch (e) { alert('操作失败') }
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

// ── 权限矩阵 ──
const permUser = ref(''); const permKb = ref(''); const permType = ref('READ')
const permMsg = ref(''); const permOk = ref(true)
const permMode = ref('user')
const permSelUser = ref('')
const permSelKb = ref('')
const permRows = ref([])
const permMap = computed(() => {
  const m = {}
  permRows.value.forEach(p => { m[`${p.userId}:${p.kbId}`] = p.permissionType })
  return m
})
async function loadPerms() {
  try {
    const { data } = await getAdminPerms()
    if (data.code === 200) permRows.value = data.data
  } catch (e) { }
}
async function loadPermData() {
  await Promise.all([loadUsers(), loadAdminKbs(), loadPerms()])
}
function permTypeOf(userId, kbId) {
  if (!userId || !kbId) return ''
  return permMap.value[`${userId}:${kbId}`] || ''
}
function userById(id) {
  return users.value.find(u => u.id === id) || null
}
function kbById(id) {
  return adminKbs.value.find(k => k.id === id) || null
}
async function changePerm(user, kb, type) {
  if (!user || !kb) return
  const label = `用户「${user.username}」在知识库「${kb.name}」`
  if (type === '') {
    if (!permTypeOf(user.id, kb.id)) return
    if (!confirm(`确定回收${label}的全部权限？`)) return
    try {
      const { data } = await revokePermAdmin(user.username, String(kb.id))
      if (data.code !== 200) alert(data.message || '回收失败')
    } catch (e) { alert('操作失败') }
  } else {
    try {
      const { data } = await grantPermAdmin(user.username, String(kb.id), type)
      if (data.code !== 200) alert(data.message || '授权失败')
    } catch (e) { alert('操作失败') }
  }
  await loadPerms()
}
async function doGrant() {
  if (!permUser.value || !permKb.value) { permOk.value = false; permMsg.value = '请填写用户名并选择知识库'; return }
  try {
    const { data } = await grantPermAdmin(permUser.value, permKb.value, permType.value)
    permOk.value = data.code === 200
    permMsg.value = data.message || (data.code === 200 ? '授权成功' : '授权失败')
    if (data.code === 200) await loadPerms()
  } catch (e) { permOk.value = false; permMsg.value = '操作失败' }
}
async function doRevoke() {
  if (!permUser.value || !permKb.value) { permOk.value = false; permMsg.value = '请填写用户名并选择知识库'; return }
  if (!confirm(`回收 ${permUser.value} 在该知识库的全部权限？`)) return
  try {
    const { data } = await revokePermAdmin(permUser.value, permKb.value)
    permOk.value = data.code === 200
    permMsg.value = data.message || (data.code === 200 ? '权限已回收' : '回收失败')
    if (data.code === 200) await loadPerms()
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
  else if (t === 'kb') loadAdminKbs()
  else if (t === 'docs') { if (!adminKbs.length) loadAdminKbs(); loadDocs() }
  else if (t === 'model') loadModelConfig()
  else if (t === 'skills') loadSkills()
  else if (t === 'mcp') loadMcps()
  else if (t === 'perm') loadPermData()
  else if (t === 'fb') loadFeedback()
  else if (t === 'notif') loadNotifs()
  else if (t === 'audit') loadAudit()
})

onMounted(async () => {
  if (!adminKbs.length) await loadAdminKbs()
  loadDash()
})
</script>
