/**
 * MessageBubble.tsx —— 单条消息气泡（纯展示组件）
 *
 * 职责：
 * 渲染一轮对话中的一条消息：头像 + 气泡 + 可选删除按钮。
 * 用户消息右对齐、助手消息左对齐；正文一律交给 Markdown 组件渲染，
 * 本组件不处理 Markdown 解析，也不持有任何状态。
 *
 * 组件属性（props）：
 * role —— 'user' | 'assistant'，决定左右布局与头像文字（我 / AI）
 * content —— 消息正文（Markdown 源文本）
 * streaming —— 是否正在流式输出中，透传给 Markdown 以显示打字光标
 * onDelete —— 可选；传入才渲染删除按钮（删除该条，连同同一轮问答）
 * aboveBubble —— 可选；渲染在气泡【上方】的内容（当前用于「执行过程」入口）。
 *   传了它才会多包一层 .msg-body 列容器：这样按钮能紧贴头像（同一行），
 *   展开时把气泡整体往下推。不传时 DOM 与改动前完全一致 —— 用户消息不受影响。
 *
 * 组件事件：
 * onDelete 由 ChatWindow 提供，本组件只负责在点击时调用。
 */
import { Trash2 } from 'lucide-react'
import type { ReactNode } from 'react'
import Markdown from './Markdown'

export default function MessageBubble({
 role,
 content,
 streaming,
 onDelete,
 aboveBubble,
}: {
 role: string
 content: string
 streaming?: boolean
 onDelete?: () => void
 aboveBubble?: ReactNode
}) {
 const isUser = role === 'user'
 const bubble = (
 <div className={`bubble ${isUser ? 'bubble-user' : 'bubble-assistant'}`}>
 <Markdown content={content} streaming={streaming} />
 </div>
 )
 return (
 <div className={`bubble-row ${isUser ? 'right' : 'left'}`}>
 <div className="avatar">{isUser ? '我' : 'AI'}</div>
 {aboveBubble ? (
 <div className="msg-body">
 {aboveBubble}
 {bubble}
 </div>
 ) : (
 bubble
 )}
 {onDelete && (
 <button className="msg-del" title="删除这条消息（连同同一轮问答）" onClick={onDelete}>
 <Trash2 size={13} />
 </button>
 )}
 </div>
 )
}
