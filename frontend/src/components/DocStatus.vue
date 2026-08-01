<template>
  <!-- 文档入库状态：解析中（阶段提示+进度条）/ 入库失败（原因）/ 已完成 -->
  <div v-if="doc.docStatus === 'PROCESSING'" style="min-width:140px">
    <div class="stage" style="color:#fbbf24;font-size:12px;margin-bottom:4px">{{ doc.ingestMessage || '解析中…' }}</div>
    <div style="height:6px;background:#1e293b;border-radius:3px;overflow:hidden">
      <div class="progress-bar" :style="{ width: percent + '%', height: '100%', background: 'linear-gradient(90deg,#2563eb,#4f46e5)', transition: 'width .5s' }"></div>
    </div>
    <div class="percent" style="color:#64748b;font-size:11px;margin-top:2px">{{ percent }}%</div>
  </div>
  <div v-else-if="doc.docStatus === 'FAILED'">
    <div style="color:#ef4444;font-size:12px">入库失败</div>
    <div v-if="doc.ingestMessage" class="fail-reason" style="color:#7f1d1d;font-size:11px;margin-top:2px">{{ doc.ingestMessage }}</div>
  </div>
  <span v-else style="color:#22c55e;font-size:12px">已完成</span>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({ doc: { type: Object, required: true } })
// 进度百分比：兜底 0，上限 100（100 只由后端在 done 时落定）
const percent = computed(() => Math.min(100, Math.max(0, props.doc.ingestProgress || 0)))
</script>
