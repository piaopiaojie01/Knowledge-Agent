import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import DocStatus from './DocStatus.vue'

describe('DocStatus 入库状态渲染', () => {
  it('ACTIVE 显示已完成', () => {
    const w = mount(DocStatus, { props: { doc: { docStatus: 'ACTIVE' } } })
    expect(w.text()).toContain('已完成')
    expect(w.find('.progress-bar').exists()).toBe(false)
  })

  it('PROCESSING 显示阶段提示 + 进度条 + 百分比', () => {
    const w = mount(DocStatus, {
      props: { doc: { docStatus: 'PROCESSING', ingestMessage: '向量化 + 入库...', ingestProgress: 30 } }
    })
    expect(w.find('.stage').text()).toBe('向量化 + 入库...')
    expect(w.find('.progress-bar').attributes('style')).toContain('width: 30%')
    expect(w.find('.percent').text()).toBe('30%')
  })

  it('PROCESSING 无消息时兜底「解析中…」，无进度时显示 0%', () => {
    const w = mount(DocStatus, { props: { doc: { docStatus: 'PROCESSING' } } })
    expect(w.find('.stage').text()).toBe('解析中…')
    expect(w.find('.percent').text()).toBe('0%')
  })

  it('进度超过 100 时封顶显示 100%', () => {
    const w = mount(DocStatus, { props: { doc: { docStatus: 'PROCESSING', ingestProgress: 120 } } })
    expect(w.find('.percent').text()).toBe('100%')
  })

  it('FAILED 显示失败原因', () => {
    const w = mount(DocStatus, {
      props: { doc: { docStatus: 'FAILED', ingestMessage: 'PDF 解析失败' } }
    })
    expect(w.text()).toContain('入库失败')
    expect(w.find('.fail-reason').text()).toBe('PDF 解析失败')
  })

  it('FAILED 无原因时不渲染原因行', () => {
    const w = mount(DocStatus, { props: { doc: { docStatus: 'FAILED' } } })
    expect(w.text()).toContain('入库失败')
    expect(w.find('.fail-reason').exists()).toBe(false)
  })
})
