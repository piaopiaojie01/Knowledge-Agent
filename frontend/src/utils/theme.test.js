import { describe, it, expect } from 'vitest'
import { resolveTheme } from './theme'

describe('主题自动切换', () => {
  it('手动指定日间/夜间时固定生效', () => {
    expect(resolveTheme('light', new Date(2026, 0, 1, 22))).toBe('light')
    expect(resolveTheme('dark', new Date(2026, 0, 1, 10))).toBe('dark')
  })

  it('auto 白天(10点)为日间', () => {
    expect(resolveTheme('auto', new Date(2026, 0, 1, 10))).toBe('light')
  })

  it('auto 夜晚(22点)为夜间', () => {
    expect(resolveTheme('auto', new Date(2026, 0, 1, 22))).toBe('dark')
  })

  it('未设置或未知值按 auto 处理', () => {
    expect(resolveTheme(undefined, new Date(2026, 0, 1, 10))).toBe('light')
    expect(resolveTheme(null, new Date(2026, 0, 1, 22))).toBe('dark')
  })
})
