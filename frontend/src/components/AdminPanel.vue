<template>
  <div class="admin-overlay" @click.self="$emit('close')">
    <div class="admin-box">
      <h3><span>用户管理</span><span @click="$emit('close')" style="cursor:pointer;font-size:20px">&times;</span></h3>
      <div style="display:flex;gap:8px;margin-bottom:14px">
        <input v-model="newUser" placeholder="用户名" style="flex:1;padding:8px 12px;background:#0b1120;border:1px solid #1e293b;border-radius:6px;color:#e2e8f0;font-size:13px" />
        <input v-model="newPass" type="password" placeholder="密码" style="flex:1;padding:8px 12px;background:#0b1120;border:1px solid #1e293b;border-radius:6px;color:#e2e8f0;font-size:13px" />
        <button @click="doCreate" style="padding:8px 16px;background:#22c55e;color:#fff;border:none;border-radius:6px;cursor:pointer;font-weight:600;font-size:13px">新建</button>
      </div>
      <table class="doc-table">
        <thead><tr><th>ID</th><th>用户名</th><th>角色</th><th>用量</th><th></th></tr></thead>
        <tbody>
          <tr v-for="u in users" :key="u.id">
            <td>{{ u.id }}</td><td>{{ u.username }}</td><td>{{ u.role }}</td>
            <td>{{ (u.storageUsed/1048576).toFixed(1) }} MB</td>
            <td><span v-if="u.username!=='admin'" @click="doDelete(u.id)" style="color:#ef4444;cursor:pointer">✕</span></td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { getAdminUsers, createUser, deleteUser } from '../api'
const emit = defineEmits(['close'])
const users = ref([])
const newUser = ref('')
const newPass = ref('')

async function load() {
  try { const { data } = await getAdminUsers(); if (data.code === 200) users.value = data.data } catch (e) { }
}
async function doCreate() {
  if (!newUser.value || !newPass.value) return
  try { await createUser(newUser.value, newPass.value); newUser.value = ''; newPass.value = ''; await load() } catch (e) { }
}
async function doDelete(id) {
  if (!confirm('确定删除？')) return
  try { await deleteUser(id); await load() } catch (e) { }
}
onMounted(load)
</script>
