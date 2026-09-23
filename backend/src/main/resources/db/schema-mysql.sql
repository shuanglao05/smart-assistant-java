-- =============================================================================
-- IPAS 智能助手 —— MySQL 表结构（13 张表）
--
-- 本脚本是全项目唯一的数据表定义来源：启动时由 spring.sql.init 自动执行，
-- 全部语句均带 IF NOT EXISTS，可重复执行、不破坏已有数据。
--
-- 【设计说明】
--
-- 1. 不加外键约束（FOREIGN KEY）。
-- 原因：本项目历史数据从未真正启用外键强制，若这里补上真外键，
-- 删除会话 / 删除文件等操作的失败行为会与既有数据不一致（可能出现删不掉）。
-- 故这里只建索引、不建外键。多用户数据隔离靠每个查询都带 user_id 条件来保证。
--
-- 2. 两处列名避开 MySQL 关键字（其余列名与实体字段一一对应）：
-- kb_chunks.text -> kb_chunks.chunk_text
-- notifications.type -> notifications.notify_type
-- `TEXT` / `TYPE` 在 MySQL 里做列名需要反引号包裹，容易在写 SQL 时漏掉，
-- 故改成更安全的名字。
--
-- 3. 文本类型按「实际最大长度」选，不是一律 TEXT：
-- TEXT（最多 65535 字节）只够几十 KB 中文；本项目的 messages.content、
-- files.content（上限 50 万字符）、kb_chunks.embedding（1024 维向量 JSON）
-- 都会超过它，故用 MEDIUMTEXT（16MB）。若统一用 TEXT，
-- 长回答 / 大文件正文会在写入时被 MySQL 静默截断 —— 这是最隐蔽的坑。
--
-- 4. 时间列统一用 DATETIME(6)，存【UTC 时间】。
-- Java 侧对应 LocalDateTime（UTC 语义、不带时区），
-- 前端按「无时区字符串」解析，两端一致，避免显示错 8 小时。
--
-- ⚠️ 括号里的 6（= 微秒精度）不能省，这是实测踩出来的坑：
-- MySQL 的 DATETIME 默认是 DATETIME(0)，会把小数秒【直接丢弃】。
-- 后果是同一条记录「创建接口的响应」带着毫秒（来自内存里的实体），
-- 而「查询接口的响应」只剩整数秒（来自数据库），两处对不上。
-- 补齐到 6 位后，落库精度与实体里的微秒一致，两处也就一致了。
--
-- 5. 金额/浮点：schedules.start_at 用 DOUBLE，存 epoch 秒。
-- =============================================================================

SET NAMES utf8mb4;

-- -----------------------------------------------------------------------------
-- 账号
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `users` (
 `id` BIGINT NOT NULL AUTO_INCREMENT,
 `username` VARCHAR(50) NOT NULL COMMENT '登录账号，创建后不可改',
 `hashed_password` VARCHAR(255) NOT NULL COMMENT 'bcrypt 哈希，形如 $2b$12$...',
 `nickname` VARCHAR(50) NULL COMMENT '显示昵称，可改',
 `avatar` MEDIUMTEXT NULL COMMENT '表情字符或 data URL（前端已压缩，约几十 KB）',
 `language` VARCHAR(10) NOT NULL DEFAULT 'zh' COMMENT 'zh / en',
 `font_size` VARCHAR(10) NOT NULL DEFAULT 'medium' COMMENT 'fs12..fs22（兼容旧值 small/medium/large）',
 `theme` VARCHAR(10) NOT NULL DEFAULT 'dark' COMMENT 'light / dark / sepia / contrast',
 `created_at` DATETIME(6) NULL,
 PRIMARY KEY (`id`),
 UNIQUE KEY `uk_users_username` (`username`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT '账号';

-- -----------------------------------------------------------------------------
-- 会话：一条会话绑定「用哪个模型 / 启用哪些技能 / 检索哪些知识库」
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `conversations` (
 `id` BIGINT NOT NULL AUTO_INCREMENT,
 `user_id` BIGINT NOT NULL,
 `title` VARCHAR(100) NULL DEFAULT '新对话',
 `provider` VARCHAR(20) NOT NULL DEFAULT 'ollama' COMMENT 'ollama / cloud',
 `model` VARCHAR(80) NOT NULL COMMENT '会话使用的具体模型名',
 `provider_id` BIGINT NULL COMMENT '指向 llm_providers.id；NULL=用默认 CLOUD_* 或本地 Ollama',
 `active_skill_ids` JSON NULL COMMENT '本会话启用的技能 id 列表',
 `active_kb_ids` JSON NULL COMMENT '本会话启用（参与检索）的知识库 id 列表',
 `created_at` DATETIME(6) NULL,
 `updated_at` DATETIME(6) NULL,
 PRIMARY KEY (`id`),
 KEY `idx_conv_user` (`user_id`),
 KEY `idx_conv_updated` (`updated_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT '会话';

-- -----------------------------------------------------------------------------
-- 消息：会话里的每一条问答（AI 的上下文记忆【不】存这里，见下方说明）
--
-- 注意区分两套数据：
-- · 本表 = 给【用户看】的聊天记录（前端左侧历史列表渲染用）
-- · Spring AI 的 chat memory 表 = 给【模型看】的上下文
-- 两者是分开的，本表只管前者。
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `messages` (
 `id` BIGINT NOT NULL AUTO_INCREMENT,
 `conversation_id` BIGINT NOT NULL,
 `role` VARCHAR(20) NOT NULL COMMENT 'user / assistant',
 `content` MEDIUMTEXT NOT NULL COMMENT '正文；AI 长回答可能上万字',
 `created_at` DATETIME(6) NULL,
 `ref_file_ids` TEXT NULL COMMENT '用户消息引用的附件 id（JSON 数组）',
 `provider` VARCHAR(20) NULL COMMENT '产生该回答的模型供应方（仅助手消息有）',
 `model` VARCHAR(120) NULL COMMENT '产生该回答的模型名（仅助手消息有）',
 PRIMARY KEY (`id`),
 KEY `idx_msg_conv` (`conversation_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT '聊天记录';

-- -----------------------------------------------------------------------------
-- 待办
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `todos` (
 `id` BIGINT NOT NULL AUTO_INCREMENT,
 `user_id` BIGINT NOT NULL,
 `task` VARCHAR(500) NOT NULL,
 `done` TINYINT(1) NULL DEFAULT 0,
 `created_at` DATETIME(6) NULL,
 PRIMARY KEY (`id`),
 KEY `idx_todo_user` (`user_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT '待办事项';

-- -----------------------------------------------------------------------------
-- 智能笔记：按天归档，content 为 Markdown
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `notes` (
 `id` BIGINT NOT NULL AUTO_INCREMENT,
 `user_id` BIGINT NOT NULL,
 `title` VARCHAR(200) NOT NULL DEFAULT '',
 `content` MEDIUMTEXT NOT NULL,
 `day` VARCHAR(10) NOT NULL COMMENT 'YYYY-MM-DD，用于按日期分组与「让 AI 归纳某天」',
 `created_at` DATETIME(6) NULL,
 `updated_at` DATETIME(6) NULL,
 PRIMARY KEY (`id`),
 KEY `idx_note_user` (`user_id`),
 KEY `idx_note_day` (`day`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT '智能笔记';

-- -----------------------------------------------------------------------------
-- 日程：开始前 5 分钟由后台定时任务发站内提醒
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `schedules` (
 `id` BIGINT NOT NULL AUTO_INCREMENT,
 `user_id` BIGINT NOT NULL,
 `title` VARCHAR(200) NOT NULL,
 `start_at` DOUBLE NOT NULL COMMENT 'epoch 秒（UTC），避开时区换算的坑',
 `note` TEXT NULL,
 `reminded` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否已发过提醒（防重复推送）',
 `created_at` DATETIME(6) NULL,
 PRIMARY KEY (`id`),
 KEY `idx_sched_user` (`user_id`),
 KEY `idx_sched_start` (`start_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT '日程';

-- -----------------------------------------------------------------------------
-- 课表
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `courses` (
 `id` BIGINT NOT NULL AUTO_INCREMENT,
 `user_id` BIGINT NOT NULL,
 `name` VARCHAR(100) NOT NULL,
 `teacher` VARCHAR(100) NULL,
 `location` VARCHAR(100) NULL,
 `weekday` INT NOT NULL COMMENT '1=周一 … 7=周日',
 `start_section` INT NOT NULL COMMENT '起始节次',
 `end_section` INT NOT NULL COMMENT '结束节次',
 `weeks` VARCHAR(50) NULL COMMENT '如 "1-16"',
 `color` VARCHAR(20) NULL,
 `created_at` DATETIME(6) NULL,
 PRIMARY KEY (`id`),
 KEY `idx_course_user` (`user_id`),
 KEY `idx_course_weekday` (`weekday`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT '课表';

-- -----------------------------------------------------------------------------
-- 技能：一段附加到系统提示词后面的人设指令
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `skills` (
 `id` BIGINT NOT NULL AUTO_INCREMENT,
 `user_id` BIGINT NOT NULL,
 `name` VARCHAR(100) NOT NULL,
 `description` VARCHAR(500) NULL,
 `prompt` TEXT NOT NULL COMMENT '拼入系统提示词的附加指令',
 `is_enabled` TINYINT(1) NOT NULL DEFAULT 1,
 `created_at` DATETIME(6) NULL,
 `updated_at` DATETIME(6) NULL,
 PRIMARY KEY (`id`),
 KEY `idx_skill_user` (`user_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT '技能';

-- -----------------------------------------------------------------------------
-- 上传的文件
-- 注意：knowledge base 相关表要在 files 之前不需要，因为本脚本不建外键。
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `files` (
 `id` BIGINT NOT NULL AUTO_INCREMENT,
 `user_id` BIGINT NOT NULL,
 `collection_id` BIGINT NULL COMMENT '所属知识库',
 `filename` VARCHAR(255) NOT NULL COMMENT '原始文件名',
 `stored_path` VARCHAR(500) NOT NULL COMMENT '相对 backend 的存储路径',
 `size` BIGINT NULL DEFAULT 0 COMMENT '字节数',
 `content` MEDIUMTEXT NULL COMMENT '上传时抽取的正文（chat 直接注入上下文；上限 50 万字符）',
 `index_status` VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT '索引状态：PENDING/INDEXING/READY/FAILED/SKIPPED',
 `index_error` VARCHAR(500) NULL COMMENT '最近一次索引失败原因；成功为 NULL',
 `chunk_count` INT NOT NULL DEFAULT 0 COMMENT '已生成的索引片段数',
 `created_at` DATETIME(6) NULL,
 PRIMARY KEY (`id`),
 KEY `idx_file_user` (`user_id`),
 KEY `idx_file_collection` (`collection_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT '上传文件';

-- -----------------------------------------------------------------------------
-- 知识库（集合）：一个用户可建多个，各自可单独设置 Top-K
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `kb_collections` (
 `id` BIGINT NOT NULL AUTO_INCREMENT,
 `user_id` BIGINT NOT NULL,
 `name` VARCHAR(100) NOT NULL,
 `top_k` INT NULL COMMENT '本库专属检索片段数；NULL=跟随全局默认 app.rag.top-k',
 `created_at` DATETIME(6) NULL,
 `updated_at` DATETIME(6) NULL,
 PRIMARY KEY (`id`),
 KEY `idx_kbcoll_user` (`user_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT '知识库';

-- -----------------------------------------------------------------------------
-- RAG 片段：文档切分后的每一小段及其向量
--
-- ⚠️ `chunk_text` 是片段正文（字段命名见文件头第 2 条）
--
-- 为什么向量存 MEDIUMTEXT 而不是 JSON 列：
-- bge-m3 输出 1024 维 float，序列化成 JSON 约 15~20KB。
-- 前端不查、只在 Java 内存里算余弦，存文本最简单，且与已有数据格式兼容。
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `kb_chunks` (
 `id` BIGINT NOT NULL AUTO_INCREMENT,
 `user_id` BIGINT NOT NULL,
 `collection_id` BIGINT NULL,
 `file_id` BIGINT NOT NULL,
 `chunk_index` INT NOT NULL DEFAULT 0 COMMENT '该文档内的片段序号',
 `chunk_text` MEDIUMTEXT NOT NULL COMMENT '片段正文（原名 text）',
 `embedding` MEDIUMTEXT NULL COMMENT '旧版向量：float 数组的 JSON 文本（已弃用，仅为读取历史数据保留）',
 `embedding_bin` MEDIUMBLOB NULL COMMENT '向量：float32 小端字节序列（1024 维 = 4096 字节）',
 `created_at` DATETIME(6) NULL,
 PRIMARY KEY (`id`),
 KEY `idx_chunk_user` (`user_id`),
 KEY `idx_chunk_collection` (`collection_id`),
 KEY `idx_chunk_file` (`file_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT 'RAG 知识片段';

-- -----------------------------------------------------------------------------
-- 站内通知（右上角铃铛）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `notifications` (
 `id` BIGINT NOT NULL AUTO_INCREMENT,
 `user_id` BIGINT NOT NULL,
 `title` VARCHAR(200) NOT NULL,
 `body` TEXT NULL,
 `notify_type` VARCHAR(20) NULL DEFAULT 'info' COMMENT 'info / remind / alert / system（原名 type）',
 `is_read` TINYINT(1) NOT NULL DEFAULT 0,
 `created_at` DATETIME(6) NULL,
 PRIMARY KEY (`id`),
 KEY `idx_notify_user` (`user_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT '站内通知';

-- -----------------------------------------------------------------------------
-- 云端大模型 API 接入（可多个并存）
--
-- ⚠️ api_key 目前是明文存储，属于本地个人项目的取舍。
-- 若要上生产，应改为加密存储 + 密钥管理服务。
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `llm_providers` (
 `id` BIGINT NOT NULL AUTO_INCREMENT,
 `user_id` BIGINT NOT NULL,
 `name` VARCHAR(50) NOT NULL COMMENT '显示名，如「智谱 GLM」「DeepSeek」',
 `base_url` VARCHAR(300) NOT NULL COMMENT 'OpenAI 兼容端点',
 `api_key` VARCHAR(300) NOT NULL,
 `model` VARCHAR(120) NOT NULL DEFAULT '' COMMENT '默认模型',
 `models` TEXT NULL COMMENT '可选模型清单（逗号分隔），供切换下拉',
 `created_at` DATETIME(6) NULL,
 `updated_at` DATETIME(6) NULL,
 PRIMARY KEY (`id`),
 KEY `idx_llmp_user` (`user_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT '云端模型接入';

-- -----------------------------------------------------------------------------
-- 运行时可改的应用配置（键值对）
--
-- 【为什么需要这张表】
--
-- 「设置」页里能改的几项（云端 Key / Base URL / 模型名 / 代理 / 思考开关 /
-- 本地 num_ctx）需要做到「保存后立即生效 + 重启后仍然保留」。但 Java 侧做不到
-- 直接改配置文件：AppProperties 是启动时绑定的不可变对象，运行时改不了；
-- 配置文件是打包进 jar 的资源，运行时回写既不安全也没有意义。
--
-- 所以采用「配置落库 + 内存缓存」：写入本表后立即生效，重启依然保留。
-- 这样做的三点好处：
-- 1. 不再需要写配置文件，避免并发写同一文件造成内容损坏；
-- 2. 天然按 user_id 隔离，多用户各自一套配置；
-- 3. 配置项变成可查询的数据，便于排查「当前到底用的是哪套配置」。
--
-- setting_value 用 MEDIUMTEXT 而不是 VARCHAR：模型清单可能很长
-- （有的平台返回上百个模型，逗号拼接后上万字符）。
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `app_settings` (
 `id` BIGINT NOT NULL AUTO_INCREMENT,
 `user_id` BIGINT NOT NULL,
 `setting_key` VARCHAR(60) NOT NULL COMMENT '配置项名，如 cloud.api-key / ollama.num-ctx',
 `setting_value` MEDIUMTEXT NULL COMMENT '配置值（统一以文本存，读取时按类型解析）',
 `updated_at` DATETIME(6) NULL,
 PRIMARY KEY (`id`),
 UNIQUE KEY `uk_app_settings_user_key` (`user_id`, `setting_key`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT '运行时可改的配置';

-- =============================================================================
-- 另有 SPRING_AI_CHAT_MEMORY 表（存「给模型看的对话上下文」，按 conversation_id 隔离）
--
-- 这里【故意不手写】它的 DDL：由 Spring AI 自己在启动时创建
-- （配置 spring.ai.chat.memory.repository.jdbc.initialize-schema=always），
-- 手写容易与框架预期结构不一致，反而引发难查的问题。
-- =============================================================================

-- =============================================================================
-- 增量迁移（幂等）：给【已存在】的 `files` 表补上「索引状态」三列
--
-- 【为什么需要这一段】
-- 上面的 CREATE TABLE ... IF NOT EXISTS 只在"表还不存在"时生效。对已经建好的库，
-- 表已存在 → 整条建表语句被跳过 → 新增的三列不会自动出现，实体一读就报"列不存在"。
--
-- 【为什么不用 `ADD COLUMN IF NOT EXISTS`】
-- MySQL 并不支持这个语法（那是 MariaDB 的）。所以这里改用
-- 「先查 information_schema 判断列在不在，再决定是否动态执行 ALTER」，
-- 等价实现幂等：列已存在就执行一句无副作用的 SELECT 1，重复启动也不会报错。
--
-- 【为什么整段放在文件末尾】
-- 下面的回填语句要读 `kb_chunks`，必须等它已经建好之后才能执行。
-- =============================================================================

SET @add_index_status := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `files` ADD COLUMN `index_status` VARCHAR(16) NOT NULL DEFAULT ''PENDING'' COMMENT ''索引状态：PENDING/INDEXING/READY/FAILED/SKIPPED'' AFTER `content`',
    'SELECT 1')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'files' AND COLUMN_NAME = 'index_status');
PREPARE s1 FROM @add_index_status;
EXECUTE s1;
DEALLOCATE PREPARE s1;

SET @add_index_error := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `files` ADD COLUMN `index_error` VARCHAR(500) NULL COMMENT ''最近一次索引失败原因；成功为 NULL'' AFTER `index_status`',
    'SELECT 1')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'files' AND COLUMN_NAME = 'index_error');
PREPARE s2 FROM @add_index_error;
EXECUTE s2;
DEALLOCATE PREPARE s2;

SET @add_chunk_count := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `files` ADD COLUMN `chunk_count` INT NOT NULL DEFAULT 0 COMMENT ''已生成的索引片段数'' AFTER `index_error`',
    'SELECT 1')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'files' AND COLUMN_NAME = 'chunk_count');
PREPARE s3 FROM @add_chunk_count;
EXECUTE s3;
DEALLOCATE PREPARE s3;

-- 回填：把"其实早就建好了索引"的老文件补成 READY，并写入真实的片段数，
-- 否则它们会一直显示成 PENDING（等待索引），与事实不符。
-- WHERE index_status='PENDING' 保证幂等：只补没标记过的，重复执行不会覆盖已有状态。
UPDATE `files` f
SET f.index_status = 'READY',
    f.chunk_count = (SELECT COUNT(*) FROM `kb_chunks` c WHERE c.file_id = f.id)
WHERE f.index_status = 'PENDING'
  AND EXISTS (SELECT 1 FROM `kb_chunks` c WHERE c.file_id = f.id);

-- =============================================================================
-- 增量迁移（幂等）：`kb_chunks` 的向量改存二进制
--
-- 【改了什么】
-- 新增 `embedding_bin MEDIUMBLOB` 存 float32 字节序列；并把旧的 `embedding`
-- 由 NOT NULL 放宽为 NULL（新写入不再填它，只有历史数据还留着）。
--
-- 【为什么值得改】
-- 1024 维向量：JSON 文本约 18KB，float32 二进制只要 4KB。检索要把该用户的全部片段
-- 读进内存，用 JSON 存就得对每一行做一次文本解析 —— 那才是检索慢的主因。
--
-- 【历史数据怎么办】
-- 读取侧做了"双读"（优先二进制、为空回退 JSON），所以老行不会因为这次改版而检索不到。
-- 把它们真正搬成二进制有两条路：跑一次回填（启动参数 RAG_BACKFILL_EMBEDDINGS=true），
-- 或对文档执行「重建索引」。两者都无需停机。
-- =============================================================================

SET @add_embedding_bin := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `kb_chunks` ADD COLUMN `embedding_bin` MEDIUMBLOB NULL COMMENT ''向量：float32 小端字节序列（1024 维 = 4096 字节）'' AFTER `embedding`',
    'SELECT 1')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'kb_chunks' AND COLUMN_NAME = 'embedding_bin');
PREPARE s4 FROM @add_embedding_bin;
EXECUTE s4;
DEALLOCATE PREPARE s4;

-- 把旧列放宽为可空（只在"列存在且当前是 NOT NULL"时才执行，保证幂等）
SET @relax_embedding := (
  SELECT IF(COUNT(*) > 0,
    'ALTER TABLE `kb_chunks` MODIFY COLUMN `embedding` MEDIUMTEXT NULL COMMENT ''旧版向量：float 数组的 JSON 文本（已弃用，仅为读取历史数据保留）''',
    'SELECT 1')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'kb_chunks'
    AND COLUMN_NAME = 'embedding' AND IS_NULLABLE = 'NO');
PREPARE s5 FROM @relax_embedding;
EXECUTE s5;
DEALLOCATE PREPARE s5;

-- =============================================================================
-- 增量迁移（幂等）：给 `kb_chunks.chunk_text` 建全文索引（中文用 ngram 解析器）
--
-- 【用途】混合检索的关键词通道：向量检索擅长语义，但对"精确字面"（配置项名、编号、
-- 专有名词）容易漏召回，全文索引正好补这一路。
--
-- 【为什么必须指定 ngram 解析器】MySQL 默认解析器按空格切词，中文整段会被当成一个词，
-- 等于完全失效。ngram 按 2 字滑窗切分，中文才检索得到。
-- 分词长度由只读系统变量 ngram_token_size 控制（默认 2，无需改动）。
--
-- 【幂等】CREATE INDEX 不支持 IF NOT EXISTS，故先查 information_schema.STATISTICS
-- 判断索引是否已存在；已存在则执行一句无副作用的 SELECT 1。
-- =============================================================================

SET @add_ft_index := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `kb_chunks` ADD FULLTEXT INDEX `ft_chunk_text` (`chunk_text`) WITH PARSER ngram',
    'SELECT 1')
  FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'kb_chunks' AND INDEX_NAME = 'ft_chunk_text');
PREPARE s6 FROM @add_ft_index;
EXECUTE s6;
DEALLOCATE PREPARE s6;

-- -----------------------------------------------------------------------------
-- 一轮回答的「执行阶段」记录（可观测性）
--
-- 用途：回答"这句为什么答得不准 / 为什么慢"—— 记录每一轮里的阶段耗时、
-- 首字延迟、token 估算值与阶段补充信息（路由结果、命中片段数等）。
--
-- 设计要点：
-- · 按"阶段一行"存，而不是一条记录塞一个大 JSON：新增阶段不用改表，
--   还能直接按阶段做 GROUP BY 统计（例如首字延迟分布）；
-- · exchange_id 存助手消息 id —— 前端拿到回答就知道它的 id，可直接查这一轮的过程；
-- · detail 用 VARCHAR 而不是 JSON 列：内容短、要能直接在客户端里读；
-- · 这是"附属数据"，不是业务数据，故有定期清理（见 ChatTraceService#cleanup）。
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `chat_traces` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `conversation_id` BIGINT NOT NULL,
  `exchange_id` BIGINT NOT NULL COMMENT '本轮助手消息 id（前端查时间线的锚点）',
  `stage` VARCHAR(32) NOT NULL COMMENT 'PREPARE/ROUTE/RETRIEVE/AGENT_BUILD/FIRST_TOKEN/DONE',
  `duration_ms` INT NOT NULL DEFAULT 0 COMMENT '阶段耗时；FIRST_TOKEN 为从请求开始到首字，DONE 为总耗时',
  `prompt_tokens` INT NULL COMMENT '提示词侧 token 估算值',
  `completion_tokens` INT NULL COMMENT '回答侧 token 估算值',
  `detail` VARCHAR(1000) NULL COMMENT '阶段补充信息（路由结果/命中片段数/模型名等）',
  `created_at` DATETIME(6) NULL,
  PRIMARY KEY (`id`),
  KEY `idx_trace_conv` (`conversation_id`, `exchange_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT '回答执行阶段记录';
