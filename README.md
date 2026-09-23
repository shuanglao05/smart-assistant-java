# IPAS 智能助手

一个**智能个人助理**：网页上能和 AI 聊天（支持上传文件、挂"技能"、检索"知识库"），
另有待办 / 笔记 / 日程 / 课表 / 日历 / 天气 / 计时器等常用功能。

技术栈：**Java 21 + Spring Boot 3.5 + Spring AI Alibaba**（后端）、
**React 18 + TypeScript + Vite**（前端）、**MySQL 8.4**（数据库）。

---

## 目录结构

```
smart-assistant-java/
├── backend/     Spring Boot 后端（Maven 工程根目录在这里）
├── frontend/    React + TypeScript + Vite 前端
├── docs/        学习文档、项目结构清单、优化记录、答辩面试手册
├── tools/       辅助脚本（本地 mock 服务、start-minio.bat、start-pgvector.bat）
├── .editorconfig  统一缩进与换行规则（IDEA 默认读取）
├── start.sh     一键启动前后端
└── start.bat
```

---

## 快速开始

### 1. 建库

```sql
CREATE DATABASE IF NOT EXISTS ipas_assistant
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
```

表结构会在后端启动时自动创建（`backend/src/main/resources/db/schema-mysql.sql`，
全部语句带 `IF NOT EXISTS`，可重复执行）。

### 2. 配置数据库账号

密码**不要**写进 `application.yml`（该文件要进版本库），放到
`backend/src/main/resources/application-local.yml`（已被 gitignore 排除）：

```bash
cd backend
cp src/main/resources/application-local.yml.example \
   src/main/resources/application-local.yml
# 然后编辑它，填上你的 MySQL 密码
```

该文件由 `application.yml` 里的
`spring.config.import: optional:classpath:application-local.yml` 自动导入，
**不存在时也不会报错**（`optional:` 前缀），所以队友拿到仓库可以直接启动。

### 3. 装前端依赖（只需一次）

```bash
cd frontend
npm install
```

### 4. 启动

**一键启动前后端：**

```bash
./start.sh          # Git Bash
start.bat           # cmd / PowerShell
```

**或分别启动：**

```bash
cd backend && ./run.sh          # 后端 → http://127.0.0.1:8002
cd frontend && npm run dev      # 前端 → http://localhost:5174
```

### 5. 验证

```bash
curl http://127.0.0.1:8002/api/health
# {"status":"ok","version":"1.5.0","llm_provider":"ollama","llm_model":"qwen3:8b"}
```

浏览器打开 <http://localhost:5174>，用演示账号登录：**`demo` / `demo123456`**。

---

## 可选后端（**不配也能跑**，默认全关）

两个"可插拔后端"都通过**一个配置值**切换，业务代码不需要改一行。

### 可选一：上传文件存到 MinIO（对象存储）

默认放本地磁盘（`data/uploads/`）。

```bash
tools\start-minio.bat                 # 启动本地 MinIO（9000 API / 9001 控制台）
set STORAGE_BACKEND=minio             # PowerShell：$env:STORAGE_BACKEND = "minio"
set MINIO_ACCESS_KEY=<your-key>       # 凭据走环境变量，别写进配置文件
set MINIO_SECRET_KEY=<your-secret>
```

- **对象键与本地布局同一语义**（`uploads/u{userId}/{uuid}{ext}`）→ 切换后端**不必改数据库记录**。
- ⚠️ 用本地后端上传过的**老文件在 MinIO 里没有对应对象**，重索引会报"读取失败"；需要一次性迁移（或后续的"双读"）。
- 桶不存在会自动创建。

### 可选二：向量检索交给 pgvector

默认在 MySQL 里加载候选、于内存算余弦 —— **不依赖任何外部组件**。

```bash
# 1) 启动 PostgreSQL（WSL 里那个；脚本会启动服务并等 5432 就绪）
tools\start-pgvector.bat

# 2) 首次搬迁：先补齐二进制向量，再搬进 pgvector（各跑一次，跑完把变量去掉）
set RAG_BACKFILL_EMBEDDINGS=true      # MySQL 内：旧 JSON 向量 → float32 二进制
set VECTOR_STORE=pgvector
set PGVECTOR_PASSWORD=<your-password>
set RAG_BACKFILL_VECTORS=true         # MySQL → pgvector（搬已算好的向量，不重新嵌入）
```

- **只把 `chunk_id` + 向量放进 PG**，正文仍留在 MySQL —— 因为关键词检索、上下文扩窗、来源标注都读 MySQL 的文本。
- `kb_vectors` 表与 **HNSW 索引**在首次使用时自动创建（维度须与嵌入模型一致，默认 1024）。
- **PG 不可用会自动回落 MySQL**（`available()=false`），检索不会整体失效 —— 这是硬性要求。
- 规模小（几千片段）时 MySQL 那条路已经够快；这一层的价值是"**涨到十万级也不用改代码**"。

> ⚠️ 环境变量只有在 `application.yml` 里声明过（`xxx: ${ENV_NAME:default}`）才会生效 ——
> Spring **不会**把任意环境变量自动映射到自定义配置键上。新增开关时两边都要改。

---

## 技术栈

| 组件 | 版本 | 说明 |
|---|---|---|
| JDK | 21 | Spring Boot 3.5 要求 17+；已启用**虚拟线程**（`spring.threads.virtual.enabled`） |
| Spring Boot | 3.5.15 | |
| Spring AI | 1.1.2 | 由 SAA BOM 传递锁定，**不手写** |
| Spring AI Alibaba | 1.1.2.3 | 四段式版本号，前三位对应 Spring AI |
| MySQL | 8.4 | 关系型数据库（15 张表） |
| Caffeine | — | 两处有界缓存：Agent 缓存、运行时配置快照 |
| springdoc-openapi | 2.8.6 | 接口文档 `/swagger-ui.html`（含 bearerAuth；`SWAGGER_ENABLED=false` 可关） |
| MinIO SDK | 8.5.17 | **可选**对象存储后端（`app.storage.backend=minio`） |
| PostgreSQL JDBC | 42.7.4 | **可选** pgvector 向量检索后端（`app.rag.vector-store=pgvector`） |
| React / TS / Vite | — | 前端 |
| vitest + jsdom | 2.1.9 / 25 | 前端单测（`npm test`） |

---

## ⚠️ 必读：本项目踩过的坑

这些都在代码注释里详细记录了「为什么」，这里只列结论：

| 坑 | 症状 | 正确做法 |
|---|---|---|
| `mvn` 命令不可用 | 报「找不到或无法加载主类 ...Launcher」 | 用 `backend/run.sh` / `run.bat`（已自动回退到系统 Maven），**不要裸用 `mvn`** |
| 端口被环境变量顶掉 | yml 写 8002，实际去抢别的端口并报占用 | 用 `backend/run.sh` / `run.bat`（会清环境变量并显式指定端口） |
| JDBC 字符集参数 | 启动崩：`Unsupported character encoding 'utf8mb4'` | 写 `characterEncoding=UTF-8`（Java 字符集名），**不是** MySQL 的 `utf8mb4` |
| JDBC 时区参数 | **接口读回来是对的，只有查库才发现差 8 小时** | 用 `connectionTimeZone=LOCAL&preserveInstants=false` |
| `DATETIME` 精度 | 创建响应带纳秒、查询响应只剩整秒 | 建表用 `DATETIME(6)` + 生成时间截到微秒 |
| 代理作用于回环地址 | 本机 Ollama 连不上 | 代理选择器里对 `localhost`/`127.x`/`::1` 返回 `NO_PROXY` |
| **环境变量驱动的开关不生效** | 设了 `XXX=true` 却什么都没发生，**日志里连一行提示都没有** | `.yml` 里必须写成 `xxx: ${XXX:default}` —— Spring **不会**把任意环境变量自动映射到自定义配置键；**漏了不报错，只静默失效** |
| **查询参数名不一致** | 接口返回 **422**，前端 `.catch` 把错误吞掉 → **页面一片空白/误报"没有数据"** | `@RequestParam` **一律显式写** `value = "snake_case"`；裸 `@RequestParam` 取的是编译后的驼峰参数名 |
| **Maven 换镜像后离线构建失败** | `Cannot access <mirror> in offline mode ...` | Maven 会记录"构件来自哪个仓库"；换镜像后**跑一次在线构建**重新盖章即可（或不加 `-o`） |
| **配置文件被写成别的格式** | `settings.xml` 里躺着一段 `pom.xml` → 只一句 WARNING，**所有设置被静默忽略** | 外部配置改完要验证"它**真的生效了**"，而不是"没报错" |

完整记录见代码注释与 `docs/优化记录.md`。

---

## 文档索引

| 文档 | 内容 |
|---|---|
| [`docs/学习文档.md`](docs/学习文档.md) | 面向 Java 初学者的完整入门：技术栈、架构、调用链路、核心模块、常见坑 |
| [`docs/项目结构清单.md`](docs/项目结构清单.md) | 目录逐项说明：每个包 / 类 / 配置的职责 |
| [`docs/优化记录.md`](docs/优化记录.md) | 历次优化与踩坑修复记录 |
| [`docs/答辩面试手册.md`](docs/答辩面试手册.md) | Java 后端八股文 + 结合本项目的实战答法 |

---

## 测试

```bash
cd backend && mvn test     # 后端：196 个用例
cd frontend && npm test    # 前端：24 个用例（vitest + jsdom）
```

`ApiContractTest` 把**与前端之间的接口契约**固化成断言：
JSON 字段命名（snake_case）、错误响应格式（`{"detail": "..."}`）、
状态码（400 / 401 / 404 / 422）、**查询参数名**、鉴权保护、用户隔离、
以及各模块的关键业务行为（如课表节次倒挂自动校正、删除技能时清理会话引用）。

**不依赖 MySQL / Ollama / PostgreSQL**（仓储用 `@MockitoBean` 替换），可在任何机器上跑。

- `PgVectorIndexTest` 是**真机集成测试**：本机 5432 可达且提供了 `PGVECTOR_PASSWORD` 才跑，否则**自动跳过**（凭据不写进代码）。
- 前端单测覆盖 SSE 帧解析（半帧/粘包/坏帧）、主题与字号归一化、全局模型偏好的编解码。
- ⚠️ `vite.config.ts` 里 **`fileParallelism: false` 是必须的**：本机 Windows + 受限沙箱下多 worker 并发写临时文件会 EPERM，导致 vitest 非 0 退出。
- ⚠️ 跑前端的 `node_modules/.bin/*` 工具**必须切到 `frontend/` 目录**，否则读不到 `vite.config.ts`（jsdom 不生效 → `localStorage is not defined`）。

> ⚠️ 每新增一个 Controller，必须在该测试里补上它依赖的仓储替身，
> 否则容器会因缺 bean 启动失败（报 `NoSuchBeanDefinitionException`，容易懵）。

---

## 后端目录结构

```
backend/
├── run.sh / run.bat             只启动后端（清端口环境变量 + 显式指定 8002）
├── pom.xml                      依赖与版本锁定
└── src/
    ├── main/
    │   ├── java/com/ipas/assistant/         共 157 个源文件
    │   │   ├── AssistantApplication.java   启动类
    │   │   ├── config/          AppProperties 配置绑定 / Jackson / Agent 记忆 / 异步线程池 / 接口文档
    │   │   ├── common/          错误体 / 业务异常 / 全局异常 / 时间与模型工具 / 系统提示词 / 提示注入防护
    │   │   ├── security/        JWT / 过滤器 / SecurityConfig / bcrypt / 只读演示模式
    │   │   ├── entity/          JPA 实体（15 张表）
    │   │   ├── repository/      Spring Data 仓储（15 个）
    │   │   ├── dto/             请求 / 响应对象（按模块合并成容器类）
    │   │   ├── service/         业务逻辑
    │   │   │   ├── agent/       智能体装配 / 模式路由 / 确定性知识问答 / 思考拆分
    │   │   │   ├── llm/         模型工厂 + 调用韧性（限流/熔断/退避重试）
    │   │   │   ├── rag/         检索（混合检索 + RRF + 扩窗 + 预算）/ 切分 / 向量编解码 / 向量库端口
    │   │   │   ├── storage/     文件存储端口（本地 / MinIO）
    │   │   │   ├── trace/       全链路追踪记录
    │   │   │   └── memory/      对话记忆端口（LangGraph Saver）
    │   │   ├── schedule/        定时任务（日程提醒、追踪清理）
    │   │   └── controller/      REST 接口（20 个）
    │   └── resources/
    │       ├── application.yml                主配置
    │       ├── application-local.yml          本机私有配置（不提交）
    │       ├── application-local.yml.example  模板
    │       └── db/schema-mysql.sql            表结构 DDL（幂等）
    └── test/java/               18 个测试类（契约测试 + 单测 + 真机集成测试）
```
