import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { getKBList, createKB, updateKB, deleteKB, getPerms } from '../api'
import { useAuthStore } from './auth'

export const useKBStore = defineStore('kb', () => {
  const kbs = ref([])
  const selectedKbs = ref(new Set())
  const permMap = ref({})
  const searchText = ref('')

  const filteredKbs = computed(() => {
    const q = searchText.value.toLowerCase()
    return kbs.value.filter(k => k.name.toLowerCase().includes(q))
  })

  async function load() {
    try { const { data } = await getKBList(); if (data.code === 200) kbs.value = data.data } catch (e) { }
  }
  async function loadPerms() {
    try {
      const { data } = await getPerms()
      if (data.code === 200 && data.data) {
        const m = {}
        data.data.forEach(p => { m[p.kbId] = p.permissionType })
        permMap.value = m
      }
    } catch (e) { }
  }
  async function create(name) {
    try { const { data } = await createKB(name); if (data.code === 200) await load(); return data.code === 200 } catch (e) { return false }
  }
  async function remove(id) {
    try {
      await deleteKB(id)
      selectedKbs.value.delete(id)
      await load()
      return true
    } catch (e) { return false }
  }
  async function update(id, payload) {
    try {
      const { data } = await updateKB(id, payload)
      if (data.code === 200) await load()
      return data.code === 200
    } catch (e) { return false }
  }
  function toggle(id) {
    if (selectedKbs.value.has(id)) selectedKbs.value.delete(id)
    else selectedKbs.value.add(id)
  }
  function canWrite(kbId) {
    const auth = useAuthStore()
    return auth.isAdmin || permMap.value[kbId] === 'WRITE' || permMap.value[kbId] === 'ADMIN'
  }

  return { kbs, selectedKbs, permMap, searchText, filteredKbs, load, loadPerms, create, update, remove, toggle, canWrite }
})
