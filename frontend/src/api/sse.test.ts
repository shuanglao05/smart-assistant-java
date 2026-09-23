/**
 * sse.test.ts —— SSE 帧解析的单元测试
 *
 * 这里守的是"流式对话到底能不能稳定收完"：
 * 半截帧被误解析、多帧粘在一起只处理第一帧、心跳帧被当成数据 ——
 * 这些错误都不会抛异常，只会表现为"偶尔少几个字""偶尔卡住"，极难复现。
 * 所以下面把每种帧形态都写死。
 */
import { describe, expect, it } from 'vitest'
import { takeSseEvents } from './sse'

describe('takeSseEvents', () => {
  it('解析单个完整事件（token）', () => {
    const { events, rest } = takeSseEvents('data: {"token":"你"}\n\n')

    expect(events).toEqual([{ token: '你' }])
    expect(rest).toBe('')
  })

  it('一次到达多个事件时全部解析出来（粘包）', () => {
    const buf = 'data: {"token":"你"}\n\ndata: {"token":"好"}\n\ndata: {"done":true}\n\n'

    const { events, rest } = takeSseEvents(buf)

    expect(events).toHaveLength(3)
    expect(events[0].token).toBe('你')
    expect(events[1].token).toBe('好')
    expect(events[2].done).toBe(true)
    expect(rest).toBe('')
  })

  it('半截事件留在 rest 里，等下一块再拼（不丢、不误解析）', () => {
    const first = takeSseEvents('data: {"token":"你"}\n\ndata: {"tok')
    expect(first.events).toEqual([{ token: '你' }])
    expect(first.rest).toBe('data: {"tok')

    // 补齐后半截后，完整事件才被解析出来
    const second = takeSseEvents(first.rest + 'en":"好"}\n\n')
    expect(second.events).toEqual([{ token: '好' }])
    expect(second.rest).toBe('')
  })

  it('逐字符喂入也能正确切分（最苛刻的跨包情形）', () => {
    const full = 'data: {"token":"A"}\n\ndata: {"token":"B"}\n\n'
    let buf = ''
    const seen: string[] = []

    for (const ch of full) {
      buf += ch
      const { events, rest } = takeSseEvents(buf)
      buf = rest
      for (const e of events) seen.push(e.token)
    }

    expect(seen).toEqual(['A', 'B'])
    expect(buf).toBe('')
  })

  it('没有 data: 前缀的帧被忽略（心跳 / 注释）', () => {
    const { events } = takeSseEvents(': keep-alive\n\ndata: {"token":"x"}\n\n')

    expect(events).toEqual([{ token: 'x' }])
  })

  it('坏 JSON 只跳过该帧，不影响后面的事件', () => {
    const { events } = takeSseEvents('data: {坏掉的\n\ndata: {"error":"真实错误"}\n\n')

    expect(events).toEqual([{ error: '真实错误' }])
  })

  it('五种事件都能原样解析出来', () => {
    const buf =
      'data: {"reasoning":"想"}\n\n' +
      'data: {"token":"答"}\n\n' +
      'data: {"sources":["a.md"]}\n\n' +
      'data: {"error":"出错了"}\n\n' +
      'data: {"done":true,"provider":"ollama","model":"qwen3:8b","user_id":1,"assistant_id":2}\n\n'

    const { events } = takeSseEvents(buf)

    expect(events).toHaveLength(5)
    expect(events[0].reasoning).toBe('想')
    expect(events[1].token).toBe('答')
    expect(events[2].sources).toEqual(['a.md'])
    expect(events[3].error).toBe('出错了')
    expect(events[4]).toMatchObject({ done: true, provider: 'ollama', model: 'qwen3:8b' })
  })

  it('空缓冲区返回空结果（不抛异常）', () => {
    expect(takeSseEvents('')).toEqual({ events: [], rest: '' })
  })
})
