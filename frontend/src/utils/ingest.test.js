import { describe, it, expect } from 'vitest'
import { detectSettlements } from './ingest'

describe('detectSettlements 入库落定检测', () => {
  it('PROCESSING → ACTIVE 生成成功通知（含分块数）', () => {
    const prev = new Map([[1, 'PROCESSING']])
    const docs = [{ id: 1, title: '法律.pdf', docStatus: 'ACTIVE', chunkCount: 42 }]
    const out = detectSettlements(prev, docs)
    expect(out).toHaveLength(1)
    expect(out[0].type).toBe('success')
    expect(out[0].text).toBe('《法律.pdf》入库成功（42 分块）')
  })

  it('PROCESSING → FAILED 生成失败通知（含失败原因）', () => {
    const prev = new Map([[1, 'PROCESSING']])
    const docs = [{ id: 1, title: '扫描件.pdf', docStatus: 'FAILED', ingestMessage: '未识别到文字' }]
    const out = detectSettlements(prev, docs)
    expect(out).toHaveLength(1)
    expect(out[0].type).toBe('error')
    expect(out[0].text).toContain('未识别到文字')
  })

  it('FAILED 无失败原因时兜底「未知原因」', () => {
    const prev = new Map([[1, 'PROCESSING']])
    const docs = [{ id: 1, title: 'a.pdf', docStatus: 'FAILED' }]
    expect(detectSettlements(prev, docs)[0].text).toContain('未知原因')
  })

  it('首次加载（prev 无记录）不通知', () => {
    const docs = [{ id: 1, title: 'a.pdf', docStatus: 'ACTIVE' }]
    expect(detectSettlements(new Map(), docs)).toHaveLength(0)
  })

  it('仍在 PROCESSING 不通知', () => {
    const prev = new Map([[1, 'PROCESSING']])
    const docs = [{ id: 1, title: 'a.pdf', docStatus: 'PROCESSING', ingestProgress: 30 }]
    expect(detectSettlements(prev, docs)).toHaveLength(0)
  })

  it('ACTIVE → ACTIVE 不重复通知', () => {
    const prev = new Map([[1, 'ACTIVE']])
    const docs = [{ id: 1, title: 'a.pdf', docStatus: 'ACTIVE' }]
    expect(detectSettlements(prev, docs)).toHaveLength(0)
  })

  it('chunkCount 缺失时兜底 0', () => {
    const prev = new Map([[1, 'PROCESSING']])
    const docs = [{ id: 1, title: 'a.pdf', docStatus: 'ACTIVE' }]
    expect(detectSettlements(prev, docs)[0].text).toContain('0 分块')
  })
})
