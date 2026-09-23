/**
 * sse.ts —— SSE（Server-Sent Events）帧解析（纯函数）
 *
 * 职责：
 * 把网络读到的字节流缓冲区，切成一个个【已经完整】的事件对象。
 *
 * 为什么单独抽成一个模块：
 * 流式对话最容易出错的不是"发请求"，而是"把字节切成事件"——一个事件可能被拆成
 * 多个网络包（半截帧），也可能一次到达好几个事件。这段逻辑原本内联在 ChatWindow
 * 的读流循环里，只能靠真的跑一次对话来碰运气；抽成纯函数后，各种边界
 * （半帧、多帧、空行、坏 JSON）都能用单测逐一钉死。
 *
 * 约定（与后端 SSE 输出一致）：
 * · 事件之间用空行（\n\n）分隔；
 * · 每个事件里带 data: 前缀的那一行才是负载，内容是 JSON 对象；
 * · 遇到解析不了的内容【跳过这一帧】而不是抛错 —— 一帧坏数据不该打断整条流。
 */

/** 一个 SSE 事件（后端固定发 JSON 对象：{token} / {reasoning} / {sources} / {done} / {error}）。 */
export type SseEvent = Record<string, any>

/**
 * 从缓冲区里取出所有已经完整的事件，并返回剩余的不完整尾巴。
 *
 * 用法：把新读到的内容拼进缓冲区再调用；把返回的 `rest` 留到下一次继续拼。
 * 这样"只到了一半的事件"既不会被误解析，也不会被丢掉。
 */
export function takeSseEvents(buffer: string): { events: SseEvent[]; rest: string } {
  const events: SseEvent[] = []
  let rest = buffer
  let idx: number
  while ((idx = rest.indexOf('\n\n')) >= 0) {
    const frame = rest.slice(0, idx)
    rest = rest.slice(idx + 2)

    // 一个事件可能有多行（event: / id: / retry: / data:），只有 data: 那行是负载。
    // 没有 data: 的帧（例如注释心跳 ": keep-alive"）直接跳过。
    const dataLine = frame.split('\n').find((l) => l.startsWith('data:'))
    if (!dataLine) continue

    try {
      events.push(JSON.parse(dataLine.slice(5).trim()))
    } catch {
      /* 坏帧跳过：宁可少一条事件，也不要让整条流因为一帧脏数据中断 */
    }
  }
  return { events, rest }
}
