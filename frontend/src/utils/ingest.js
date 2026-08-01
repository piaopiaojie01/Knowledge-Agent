/**
 * 入库落定检测：对比上一轮状态，找出 PROCESSING → ACTIVE/FAILED 的跳变，
 * 生成通知文案。首次加载（prev 无记录）不通知。
 *
 * @param {Map<number, string>} prevStatus 上一轮 docId → docStatus
 * @param {Array} docs 本轮文档列表
 * @returns {Array<{id: number, text: string, type: 'success'|'error'}>}
 */
export function detectSettlements(prevStatus, docs) {
  const out = []
  for (const d of docs) {
    if (prevStatus.get(d.id) !== 'PROCESSING') continue
    if (d.docStatus === 'ACTIVE') {
      out.push({ id: d.id, text: `《${d.title}》入库成功（${d.chunkCount || 0} 分块）`, type: 'success' })
    } else if (d.docStatus === 'FAILED') {
      out.push({ id: d.id, text: `《${d.title}》入库失败：${d.ingestMessage || '未知原因'}`, type: 'error' })
    }
  }
  return out
}
