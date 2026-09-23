/**
 * globalModel.test.ts —— 全局模型偏好的编码/解析测试
 *
 * 这个模块的价值全在"边界"上：存储串是自定义的 `provider|model[|provider_id]`，
 * 一旦解析写得宽松（或严格）一点点，就会出现"模型选择偶尔丢失""切到别的页面模型变了"
 * 这类说不清的问题。所以把各种残缺/异常串都覆盖掉。
 */
import { beforeEach, describe, expect, it } from 'vitest'
import { encodeGlobalModel, getGlobalModel, globalModelKey, setGlobalModel } from './globalModel'

describe('globalModel', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  it('编码：没有 provider_id 时是两段式', () => {
    expect(encodeGlobalModel({ provider: 'ollama', model: 'qwen3:8b' })).toBe('ollama|qwen3:8b')
  })

  it('编码：带 provider_id 时是三段式', () => {
    expect(encodeGlobalModel({ provider: 'cloud', model: 'glm-4', provider_id: 7 }))
      .toBe('cloud|glm-4|7')
  })

  it('未设置过时返回 null（而不是抛异常）', () => {
    expect(getGlobalModel()).toBeNull()
    expect(globalModelKey()).toBe('')
  })

  it('写入后能读回来，并正确解析出 provider_id', () => {
    setGlobalModel('cloud', 'glm-4', 7)

    expect(getGlobalModel()).toEqual({ provider: 'cloud', model: 'glm-4', provider_id: 7 })
    expect(globalModelKey()).toBe('cloud|glm-4|7')
  })

  it('provider_id 传 null / undefined 时不写入第三段', () => {
    setGlobalModel('ollama', 'qwen3:8b', null)

    expect(localStorage.getItem('globalModel')).toBe('ollama|qwen3:8b')
    expect(getGlobalModel()).toEqual({ provider: 'ollama', model: 'qwen3:8b', provider_id: undefined })
  })

  it('残缺串返回 null：只写了个 provider、或字段为空', () => {
    localStorage.setItem('globalModel', 'ollama')
    expect(getGlobalModel()).toBeNull()

    localStorage.setItem('globalModel', '|model')
    expect(getGlobalModel()).toBeNull()

    localStorage.setItem('globalModel', 'provider|')
    expect(getGlobalModel()).toBeNull()
  })

  it('provider_id 不是数字时忽略它，但仍返回 provider/model（不能整体丢掉）', () => {
    localStorage.setItem('globalModel', 'cloud|glm-4|abc')

    expect(getGlobalModel()).toEqual({ provider: 'cloud', model: 'glm-4', provider_id: undefined })
  })

  it('模型名里含竖线时按前两段解析（不会崩）', () => {
    localStorage.setItem('globalModel', 'cloud|vendor/model|12')

    expect(getGlobalModel()).toEqual({ provider: 'cloud', model: 'vendor/model', provider_id: 12 })
  })
})
