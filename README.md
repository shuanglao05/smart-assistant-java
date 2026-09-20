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
├── tools/       辅助脚本（本地 mock 服务等）
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

## 技术栈

| 组件 | 版本 | 说明 |
|---|---|---|
| JDK | 21 | Spring Boot 3.5 要求 17+ |
| Spring Boot | 3.5.15 | |
| Spring AI | 1.1.2 | 由 SAA BOM 传递锁定，**不手写** |
| Spring AI Alibaba | 1.1.2.3 | 四段式版本号，前三位对应 Spring AI |
| MySQL | 8.4 | 关系型数据库 |
| React / TS / Vite | — | 前端 |

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
cd backend
./mvnw test        # 或 run.sh 里的系统 Maven 回退
```

`ApiContractTest` 把**与前端之间的接口契约**固化成断言：
JSON 字段命名（snake_case）、错误响应格式（`{"detail": "..."}`）、
状态码（400 / 401 / 404 / 422）、鉴权保护、用户隔离、
以及各模块的关键业务行为（如课表节次倒挂自动校正、删除技能时清理会话引用）。

**不依赖 MySQL**（用 `@MockitoBean` 替换所有仓储），可在任何机器上跑。

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
    │   ├── java/com/ipas/assistant/
    │   │   ├── AssistantApplication.java   启动类
    │   │   ├── config/          AppProperties 配置绑定 / Jackson / Agent 记忆装配
    │   │   ├── common/          错误体 / 业务异常 / 全局异常 / 时间与模型工具
    │   │   ├── security/        JWT / 过滤器 / SecurityConfig / bcrypt
    │   │   ├── entity/          JPA 实体（14 张表）
    │   │   ├── repository/      Spring Data 仓储
    │   │   ├── dto/             请求 / 响应对象（按模块合并成容器类）
    │   │   ├── service/         业务逻辑（含 agent/ llm/ memory/ rag/ 子包）
    │   │   ├── schedule/        定时任务（日程提醒）
    │   │   └── controller/      REST 接口
    │   └── resources/
    │       ├── application.yml                主配置
    │       ├── application-local.yml          本机私有配置（不提交）
    │       ├── application-local.yml.example  模板
    │       └── db/schema-mysql.sql            表结构 DDL
    └── test/                    契约测试
```
