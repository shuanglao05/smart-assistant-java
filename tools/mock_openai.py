"""
本地 mock 的 OpenAI 兼容服务 —— 仅用于验证「配置保存 / 测连 / 拉模型列表」的成功路径。

为什么要它：验证这几条路径需要一个"能返回 2xx 的云端端点"，
而用真实平台会消耗额度、且需要用户的真实 Key。用一个本地 mock
既能覆盖成功分支，又不碰任何真实凭据。

提供两个端点（形状与 OpenAI 兼容接口一致）：
  GET  /v1/models            -> {"data":[{"id":"mock-model-a"},{"id":"mock-model-b"}]}
  POST /v1/chat/completions  -> 200 最小聊天响应
"""
import json
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer


class Handler(BaseHTTPRequestHandler):
    def _send(self, code, obj):
        body = json.dumps(obj).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path.endswith("/models"):
            self._send(200, {"object": "list", "data": [
                {"id": "mock-model-a", "object": "model"},
                {"id": "mock-model-b", "object": "model"},
                {"object": "model"},  # 故意留一条没有 id 的，验证后端的过滤逻辑
            ]})
        else:
            self._send(404, {"error": "not found"})

    def do_POST(self):
        length = int(self.headers.get("Content-Length") or 0)
        self.rfile.read(length)
        if self.path.endswith("/chat/completions"):
            self._send(200, {
                "id": "mock-1",
                "object": "chat.completion",
                "choices": [{"index": 0, "message": {"role": "assistant", "content": "hi"}}],
            })
        else:
            self._send(404, {"error": "not found"})

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 18080
    HTTPServer(("127.0.0.1", port), Handler).serve_forever()
