<template>
  <div class="sidebar">
    <h2>知识库 <button @click="createKb">＋ 新建</button></h2>
    <input type="text" v-model="kb.searchText" placeholder="搜索知识库..." />
    <div class="kb-list" style="max-height:30vh;overflow-y:auto;margin-bottom:4px;flex-shrink:0">
      <div v-for="k in kb.filteredKbs" :key="k.id"
        :class="['kb-item', { active: kb.selectedKbs.has(k.id) }]"
        @click="kb.toggle(k.id)">
        <span><input type="checkbox" :checked="kb.selectedKbs.has(k.id)" @click.prevent />{{ k.name }}</span>
        <span class="kb-actions" style="display:flex;gap:2px">
          <span style="font-size:12px;color:#64748b"
            @click.stop="editKb(k)" title="设置">✎</span>
          <span class="del" style="font-size:14px;color:#475569"
            @click.stop="confirmRemoveKb(k.id)" title="删除">×</span>
        </span>
      </div>
    </div>

    <div class="conv-section" style="flex:1;overflow-y:auto;min-height:0">
      <div class="title-row">
        <span>对话记录</span>
        <button @click="chat.newSession()">新建</button>
      </div>
      <div v-for="s in chat.sessions" :key="s.sessionId"
        :class="['conv-item', { active: s.sessionId === chat.sessionId }]">
        <span class="conv-title" @click="chat.switchSession(s.sessionId)" :title="s.title">
          {{ s.title || s.sessionId?.substring(0,16)+'…' }}
        </span>
        <span class="conv-actions">
          <span class="rename" @click.stop="doRename(s.sessionId)" title="重命名">✎</span>
          <span class="del" @click.stop="doDelete(s.sessionId)" title="删除">×</span>
        </span>
      </div>
    </div>

    <div class="upload-area">
      <div class="section-label" style="font-size:11px;color:#64748b;margin-bottom:6px">上传文档</div>
      <div class="upload-dropzone" :class="{ dragging: isDragging }"
        @click="fileInput.click()"
        @dragenter.prevent="isDragging = true"
        @dragover.prevent="isDragging = true"
        @dragleave.prevent="isDragging = false"
        @drop.prevent="onDrop">
        <template v-if="file">
          📄 {{ file.name }}<br>
          <small style="color:#64748b;font-size:10px">已选择，点击可更换</small>
        </template>
        <template v-else>
          📂 点击或拖拽文件到此处<br>
          <small style="color:#64748b;font-size:10px">支持 TXT / Markdown / PDF / 图片</small>
        </template>
        <input ref="fileInput" type="file" accept=".txt,.md,.pdf,.png,.jpg,.jpeg" @change="handleFile" @click.stop hidden />
      </div>
      <div style="display:flex;align-items:center;gap:6px;margin-top:8px;font-size:11px;color:#64748b">
        解析设备
        <select v-model="parseDevice" style="flex:1;background:#0b1120;color:#e2e8f0;border:1px solid #1e293b;border-radius:6px;padding:4px 6px;font-size:11px">
          <option value="cpu">CPU（兼容性好）</option>
          <option value="cuda">GPU（扫描件更快）</option>
        </select>
      </div>
      <button class="upload-submit-btn" @click="doUpload" :disabled="!file">⬆ 上传并入库</button>
    </div>
  </div>
</template>

<script setup>
import { ref, watch } from 'vue'
import { useKBStore } from '../stores/kb'
import { useChatStore } from '../stores/chat'
import { uploadDoc } from '../api'
const kb = useKBStore()
const chat = useChatStore()
const file = ref(null)
const fileInput = ref(null)
const isDragging = ref(false)
// 解析设备选择，持久化到 localStorage
const parseDevice = ref(localStorage.getItem('parseDevice') || 'cpu')
watch(parseDevice, v => localStorage.setItem('parseDevice', v))

function createKb() {
  const name = prompt('知识库名称:')
  if (name) kb.create(name)
}
function confirmRemoveKb(id) {
  if (confirm('删除该知识库及其所有文档？此操作不可恢复')) kb.remove(id)
}
async function editKb(k) {
  const name = prompt('知识库名称:', k.name)
  if (name && name !== k.name) {
    if (!await kb.update(k.id, { name })) { alert('更新失败'); return }
  }
  // 三态语义：明确展示当前状态，只有点确定才切换，取消保持不变
  const isPublic = !!k.isPublic
  const toggle = confirm(isPublic
    ? '当前为公开知识库，是否切换为私有？（取消则保持不变）'
    : '当前为私有知识库，是否切换为公开？（取消则保持不变）')
  if (toggle) {
    if (!await kb.update(k.id, { isPublic: !isPublic })) alert('更新失败')
  }
}

function handleFile(e) { file.value = e.target.files[0] }
function onDrop(e) {
  isDragging.value = false
  const dt = e.dataTransfer
  const dropped = dt?.files?.[0]
  if (dropped) { file.value = dropped; return }
  // 某些来源（企业微信/QQ 等）文件不在 files 里而在 items 里
  const item = [...(dt?.items || [])].find(i => i.kind === 'file')
  const f = item?.getAsFile()
  if (f) file.value = f
}
async function doUpload() {
  if (!file.value) return
  const sbs = [...kb.selectedKbs]
  if (!sbs.length) { alert('请先选择一个知识库'); return }
  // 逐 KB 上传并收集结果，任一失败不影响其余
  const okNames = [], failNames = []
  for (const kbId of sbs) {
    const name = kb.kbs.find(k => k.id === kbId)?.name || kbId
    try {
      const fd = new FormData()
      fd.append('file', file.value)
      fd.append('kbId', kbId)
      fd.append('device', parseDevice.value)
      const { data } = await uploadDoc(fd)
      if (data.code === 200) okNames.push(name)
      else failNames.push(name)
    } catch (e) { failNames.push(name) }
  }
  await kb.load()
  // 通知文档列表刷新（Chat.vue 监听；新文档处于解析中，会自动轮询落定）
  if (okNames.length) window.dispatchEvent(new Event('docs-updated'))
  if (failNames.length) {
    alert(`成功 ${okNames.length} 个，失败 ${failNames.length} 个：${failNames.join('、')}`)
    return  // 有失败时保留文件，便于重试
  }
  file.value = null
  if (fileInput.value) fileInput.value.value = ''
}
function doRename(sid) {
  const name = prompt('重命名对话:')
  if (name) chat.renameSid(sid, name)
}
async function doDelete(sid) {
  if (!confirm('删除此对话？')) return
  await chat.removeSession(sid)
}
</script>
