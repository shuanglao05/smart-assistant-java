#!/bin/sh
#
# run.sh —— 启动 Java 版后端（Git Bash / Linux / macOS）
#
# 只启动后端。若要同时启动前端，用项目根目录的 ../start.sh。
#
# 与直接执行 `./mvnw spring-boot:run` 的区别（这两点都是踩过坑后加的）：
#
#   1. 【清掉可能覆盖端口的同名环境变量】
#      Spring Boot 的配置优先级是：环境变量 > application.yml。
#      某些终端环境会注入 SERVER_PORT（甚至 SERVER__PORT）之类变量，
#      它会被宽松绑定规则识别成 server.port，把 yml 里写的 8002 静默顶掉，
#      于是你去访问 8002 却一直连不上，日志里却显示"启动成功"。
#      这儿先 unset 掉，保证端口一定是 8002（前端 vite 代理写死了这个端口）。
#
#   2. 【显式传 --server.port=8002】
#      命令行参数的优先级最高，作为第二道保险。
#      即使将来又有别的环境变量冒出来，也不会影响端口。
#
# 用 Ctrl+C 停止。

# 本工具环境里 PATH 可能不含 coreutils，先补上（对普通终端无副作用）
PATH="/usr/bin:/bin:$PATH"
export PATH

# 切到脚本所在目录（用参数展开而不是 dirname，避免依赖外部命令）
cd "$(CDPATH= cd -- "${0%/*}" && pwd)" || exit 1

# 1. 清掉会干扰端口的同名环境变量
unset SERVER_PORT
unset SERVER__PORT

echo "[run.sh] 启动 IPAS 后端，端口 8002 ..."
echo "[run.sh] 健康检查： http://127.0.0.1:8002/api/health"
echo

# 2. 启动（显式指定端口作为兜底）
exec sh ./mvnw -B spring-boot:run -Dspring-boot.run.arguments=--server.port=8002
