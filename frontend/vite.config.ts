import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

export default defineConfig({
 plugins: [react()],

 // ------------------------------------------------------------------ 单元测试
 // 用 vitest；环境选 jsdom —— 因为要测的模块（主题、全局模型偏好）直接操作
 // localStorage / documentElement，node 环境里没有这些 API。
 // 测试文件与被测模块放在同一目录，统一命名 *.test.ts，便于就近维护。
 test: {
 environment: 'jsdom',
 include: ['src/**/*.test.{ts,tsx}'],
 // 串行跑测试文件、不并行。
 // 原因（实测）：本机是 Windows + 受限文件沙箱，多个 worker 并发写临时文件会报
 // EPERM（operation not permitted），导致整个测试进程以非 0 退出。
 // 本项目用例只有几十个、单文件毫秒级，并行本来也没什么收益，串行更稳。
 fileParallelism: false,
 },

 server: {
 port: 5174,
 proxy: {
 // 前端所有 /api 请求转发到后端，避免跨域
 // 注意：必须写 127.0.0.1 而不是 localhost——Windows 下 localhost 会解析到 ::1，
 // 而后端默认只监听 127.0.0.1，代理会报 "upstream connect failed"
 //
 // ★★★ 为什么这里不能再用简写 '/api': 'http://127.0.0.1:8002'（必须写成对象）★★★
 // 因为 /api/chat/stream 是 SSE（Server-Sent Events 流式响应）。
 // 实测对比同一段流：
 // 直连 8002 ：收到 done 后 1.1~1.4 秒，连接正常关闭 ✅
 // 走 Vite 代理 ：done 事件能收到，但【连接永远不会结束】❌（curl 一直挂到超时）
 // 后果：浏览器读不到"流结束"（fetch 的 reader 一直不返回 done），
 // ChatWindow 里清除 streamingSids 的 finally 就永远不执行 ——
 // 表现为"回答都生成完了，输入框右侧却一直停在「停止」按钮不更新"（用户真实反馈）。
 //
 // 修法：写成对象配置 + configure 钩子 ——
 // ① 关掉中间层缓冲（Cache-Control no-transform / X-Accel-Buffering no）
 // ② 上游响应结束时显式结束下游（http-proxy 在 "chunked + 无 Content-Length"
 // 的 SSE 场景下不会自动透传 end，这是本 bug 的真正根因）
 '/api': {
 target: 'http://127.0.0.1:8002',
 changeOrigin: true,
 configure: (proxy) => {
 proxy.on('proxyRes', (proxyRes, _req, res) => {
 res.setHeader('Cache-Control', 'no-cache, no-transform')
 res.setHeader('X-Accel-Buffering', 'no')
 proxyRes.on('end', () => {
 try {
 res.end()
 } catch {
 /* 已经结束过，忽略即可 */
 }
 })
 })
 },
 },
 },
 },
})
