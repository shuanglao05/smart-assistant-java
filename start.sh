#!/bin/sh
#
# start.sh —— 一键启动「后端 + 前端」（Git Bash / Linux / macOS）
#
# 会开两个窗口/进程：
#   后端  http://127.0.0.1:8002   （Spring Boot，见 backend/run.sh）
#   前端  http://localhost:5174   （Vite dev server）
#
# 关掉本终端时两个进程都会停。
#
# 只想启动其中一个时，用 backend/run.sh（后端）或手动进 frontend/ 跑 npm run dev。

PATH="/usr/bin:/bin:$PATH"
export PATH

cd "$(CDPATH= cd -- "${0%/*}" && pwd)" || exit 1
ROOT="$(pwd)"

# ---------------------------------------------------------------------------
# 前置检查：前端依赖装了吗
# ---------------------------------------------------------------------------
if [ ! -d "frontend/node_modules" ]; then
    echo "⚠️  前端依赖尚未安装（frontend/node_modules 不存在）。"
    echo "    首次运行请先执行："
    echo "        cd frontend && npm install"
    echo
    echo "    本次将只启动后端。"
    echo
    START_FRONTEND=0
else
    START_FRONTEND=1
fi

# ---------------------------------------------------------------------------
# 启动后端
# ---------------------------------------------------------------------------
echo "[start.sh] 启动后端 ..."
( cd "$ROOT/backend" && sh ./run.sh ) &
BACKEND_PID=$!

if [ "$START_FRONTEND" = "1" ]; then
    echo "[start.sh] 启动前端 ..."
    ( cd "$ROOT/frontend" && npm run dev ) &
    FRONTEND_PID=$!
fi

echo
echo "  后端: http://127.0.0.1:8002/api/health"
[ "$START_FRONTEND" = "1" ] && echo "  前端: http://localhost:5174"
echo "  演示账号: demo / demo123456"
echo
echo "  按 Ctrl+C 停止。"
echo

# 任一进程退出即整体退出，避免留下孤立的半启动状态
trap 'kill $BACKEND_PID 2>/dev/null; kill $FRONTEND_PID 2>/dev/null; exit 0' INT TERM
wait
