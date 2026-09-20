# ============================================================================
#  IPAS 智能助手 —— 后端运行镜像
#
#  采用「本地先打 jar，再 COPY 进 JRE 镜像」的精简方案：
#  不在 Docker 里跑 Maven（避免 Docker 构建需要联网拉依赖，也绕开本机 Maven 环境
#  的各类坑），由开发机执行 `mvn clean package -DskipTests` 产出 fat jar 后，
#  这里只做「运行」。
#
#  构建前必须先在本机执行（产物约定见 docker-compose.yml 注释）：
#    cd backend && mvn -B clean package -DskipTests
#  产出：backend/target/smart-assistant-java-1.5.0.jar
#        （Spring Boot 已 repackage 为可执行 jar；原始 jar 被改名为 *.jar.original）
# ============================================================================
FROM eclipse-temurin:21-jre

WORKDIR /app

# 只拷可执行 jar。Spring Boot repackage 会把原始 jar 改名为 *.jar.original，
# 所以这里用 smart-assistant-java-*.jar 通配——天然排除 .original，
# 且与版本号解耦（升级版本也不需要改 Dockerfile）。
COPY target/smart-assistant-java-*.jar app.jar

# 运行时数据（上传文件、缓存等）落到卷里，容器重建不丢。
# 对应 application.yml 的 app.data-dir，容器内统一用 /app/data。
VOLUME /app/data

# ⚠️ 关键：application.yml 把 server.address 写死成 127.0.0.1，
# 容器内必须改成 0.0.0.0 才能被其它容器 / 宿主机访问，
# 否则「容器显示启动成功，外部却一直连不上 8002」。
ENV SERVER_ADDRESS=0.0.0.0
ENV SERVER_PORT=8002

EXPOSE 8002

ENTRYPOINT ["java", "-jar", "app.jar"]
