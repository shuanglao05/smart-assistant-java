/**
 * TracePanel.tsx —— 一轮回答的「执行过程」折叠面板
 *
 * 职责：
 * 展示某条助手回答在服务端的执行时间线：各阶段耗时、首字延迟、总耗时与 token 估算。
 * 数据来自 GET /sessions/{sid}/messages/{mid}/trace（后端 chat_traces 表）。
 *
 * 为什么做成"折叠 + 懒加载"：
 * 这个面板是给排查问题、演示用的，绝大多数用户不会点开。若每轮回答都去请求一次，
 * 就是白白多打一次接口。所以默认收起，**第一次点开才拉数据**，拉到的结果缓存在组件内。
 *
 * 展示上的两个诚实之处：
 * 1. 条形长度是"相对本组最长阶段"的比例，不是绝对时间轴 —— 所以它是"横向对比各阶段"，
 * 不是一张甘特图。真正的绝对量纲由右侧的毫秒数给出。
 * 2. 首字延迟与总耗时是【累计值】（从请求开始算），与其它阶段的"独立耗时"不同口径，
 * 因此单独打了「累计」标签，避免误读成"这两步特别慢"。
 */
import { useState } from 'react'
import { Activity, ChevronDown } from 'lucide-react'
import { sessionApi } from '../api'
import type { TraceStage, TraceTimeline } from '../types'

/** 阶段名 → 中文标签（后端返回的是英文常量，展示时翻译） */
const STAGE_LABEL: Record<string, string> = {
  PREPARE: '准备',
  ROUTE: '路由',
  RETRIEVE: '检索',
  AGENT_BUILD: '构建 Agent',
  FIRST_TOKEN: '首字延迟',
  DONE: '总耗时',
}

/** 累计口径的阶段：duration_ms 是"从请求开始算"，不是该阶段的独立耗时 */
const CUMULATIVE_STAGES = new Set(['FIRST_TOKEN', 'DONE'])

/** 毫秒 → 易读文本（小于 1 秒显示毫秒，否则显示秒） */
function fmtMs(ms: number): string {
  if (ms < 1000) return `${ms}ms`
  return `${(ms / 1000).toFixed(2)}s`
}

export default function TracePanel({
  sessionId,
  messageId,
}: {
  sessionId: number
  messageId: number
}) {
  const [open, setOpen] = useState(false)
  const [data, setData] = useState<TraceTimeline | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)

  const toggle = async () => {
    const next = !open
    setOpen(next)
    // 懒加载：只在第一次展开且还没数据时请求一次
    if (next && data == null && !loading) {
      setLoading(true)
      try {
        const resp = await sessionApi.trace(sessionId, messageId)
        setData(resp.data)
        setError(null)
      } catch (e: any) {
        setError(e?.response?.data?.detail || e?.message || '加载失败')
      } finally {
        setLoading(false)
      }
    }
  }

  // 条形按"本组最长阶段"铺满；用 1 兜底避免除零
  const maxMs = data && data.stages.length > 0
    ? Math.max(1, ...data.stages.map((s) => s.duration_ms))
    : 1
  const doneStage = data?.stages.find((s) => s.stage === 'DONE')
  const tokenText = doneStage
    ? `提示 ${doneStage.prompt_tokens ?? '—'} / 回答 ${doneStage.completion_tokens ?? '—'}`
    : null

  return (
    <div className={`trace-wrap ${open ? 'open' : ''}`}>
      <button
        className="trace-head"
        onClick={toggle}
        title="查看这轮回答的执行过程（检索、首字延迟、总耗时）"
      >
        <Activity size={12} />
        <span>执行过程</span>
        <ChevronDown size={12} className="trace-caret" />
      </button>

      {open && (
        <div className="trace-body">
          {loading && <div className="trace-hint">加载中…</div>}
          {!loading && error && <div className="trace-hint">加载失败：{error}</div>}

          {!loading && !error && data && data.stages.length === 0 && (
            <div className="trace-hint">这一轮没有执行记录（例如服务重启前的历史消息）</div>
          )}

          {!loading && !error && data && data.stages.length > 0 && (
            <>
              <div className="trace-metrics">
                <span className="trace-metric">
                  {/* 为 0 说明该阶段没记录（例如回答被中途停止，收尾阶段没跑到），
                      显示 "—" 而不是 "0ms" —— 后者容易被误读成"这一步没耗时"。 */}
                  <b>{data.first_token_ms > 0 ? fmtMs(data.first_token_ms) : '—'}</b>
                  <em>首字</em>
                </span>
                <span className="trace-metric">
                  <b>{data.total_ms > 0 ? fmtMs(data.total_ms) : '—'}</b>
                  <em>总耗时</em>
                </span>
                {tokenText && (
                  <span className="trace-metric">
                    <b>{tokenText}</b>
                    <em>token 估算</em>
                  </span>
                )}
              </div>

              <div className="trace-rows">
                {data.stages.map((s: TraceStage, i: number) => (
                  <div className="trace-row" key={`${s.stage}-${i}`}>
                    <span
                      className="trace-name"
                      title={
                        CUMULATIVE_STAGES.has(s.stage)
                          ? '累计值：从请求开始算起'
                          : '该阶段的独立耗时'
                      }
                    >
                      {STAGE_LABEL[s.stage] || s.stage}
                      {CUMULATIVE_STAGES.has(s.stage) && <i className="trace-tag">累计</i>}
                    </span>
                    <span className="trace-bar">
                      <i
                        style={{
                          width: `${Math.max(2, Math.round((s.duration_ms / maxMs) * 100))}%`,
                        }}
                      />
                    </span>
                    <span className="trace-ms">{fmtMs(s.duration_ms)}</span>
                    {s.detail && (
                      <span className="trace-detail" title={s.detail}>
                        {s.detail}
                      </span>
                    )}
                  </div>
                ))}
              </div>

              <div className="trace-note">
                首字 / 总耗时为累计值；token 数按字符估算（流式接口拿不到精确用量）
              </div>
            </>
          )}
        </div>
      )}
    </div>
  )
}
