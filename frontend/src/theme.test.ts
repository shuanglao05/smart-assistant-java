/**
 * theme.test.ts —— 主题 / 字号 / 强调色的归一化测试
 *
 * 这几个函数直接改根元素属性与 CSS 变量，写错的后果是"界面整体颜色或字号不对",
 * 而且往往只在特定取值下才出现（例如用户表里存着旧版本的字号名）。
 * 这里重点覆盖"非法值要能回落到默认"，避免脏数据把界面搞坏。
 */
import { beforeEach, describe, expect, it } from 'vitest'
import {
  applyAccent,
  applyFontSize,
  applyTheme,
  getAccent,
  getSavedFontSize,
  getSavedTheme,
} from './theme'

const root = () => document.documentElement

describe('theme', () => {
  beforeEach(() => {
    localStorage.clear()
    document.documentElement.removeAttribute('data-theme')
    document.documentElement.removeAttribute('data-fs')
  })

  it('合法主题写到 data-theme 并记住', () => {
    applyTheme('sepia')

    expect(root().getAttribute('data-theme')).toBe('sepia')
    expect(getSavedTheme()).toBe('sepia')
  })

  it('非法主题回落到 dark（脏数据不该把界面搞成没样式的白板）', () => {
    applyTheme('不存在的主题')

    expect(root().getAttribute('data-theme')).toBe('dark')
    expect(getSavedTheme()).toBe('dark')
  })

  it('合法字号写到 data-fs', () => {
    applyFontSize('fs20')

    expect(root().getAttribute('data-fs')).toBe('fs20')
    expect(getSavedFontSize()).toBe('fs20')
  })

  it('旧版字号名要能映射（small/medium/large → fs12/fs14/fs18）', () => {
    applyFontSize('small')
    expect(root().getAttribute('data-fs')).toBe('fs12')

    applyFontSize('medium')
    expect(root().getAttribute('data-fs')).toBe('fs14')

    applyFontSize('large')
    expect(root().getAttribute('data-fs')).toBe('fs18')
  })

  it('非法字号回落到 fs14', () => {
    applyFontSize('超大')

    expect(root().getAttribute('data-fs')).toBe('fs14')
  })

  it('强调色：合法 6 位十六进制被采用', () => {
    applyAccent('#12A594')

    expect(getAccent()).toBe('#12A594')
    expect(root().style.getPropertyValue('--brand')).toBe('#12A594')
  })

  it('强调色：非法值回落到默认品牌蓝（不把 --brand 设成垃圾值）', () => {
    applyAccent('red')
    expect(getAccent()).toBe('#4d6bfe')

    applyAccent('#12345')
    expect(getAccent()).toBe('#4d6bfe')

    expect(root().style.getPropertyValue('--brand')).toBe('#4d6bfe')
  })

  it('未设置过时读取返回默认值（不返回 null）', () => {
    expect(getSavedTheme()).toBe('dark')
    expect(getSavedFontSize()).toBe('fs14')
    expect(getAccent()).toBe('#4d6bfe')
  })
})
