面试模拟助手 - Java 源码结构说明

---

## 启动类

- **`InterviewAssistantApplication.java`** — Spring Boot 启动入口

## agent/ — AI Agent

- **`ResumeAgent.java`** — 解析 PDF/DOCX/TXT 简历，提取候选人画像（姓名、技能、工作经历等），调用 LLM 处理
- **`InterviewerAgent.java`** — 面试官 Agent，五阶段出题（OPENING/TECHNICAL/BEHAVIORAL/DEEP_DIVE/WRAP_UP），结合 RAG 知识库检索技能相关问题
- **`EvaluatorAgent.java`** — 评分 Agent，四维度评分（技术深度、表达清晰度、逻辑连贯性、经验相关性），输出 0-10 分 + 反馈 + 参考回答

## config/ — 配置类

- **`WebConfig.java`** — 静态资源映射，CORS 配置
- **`JacksonConfig.java`** — ObjectMapper 配置（JavaTimeModule、日期格式、忽略未知属性）
- **`EmbeddingConfig.java`** — 嵌入服务连接配置

## controller/ — REST 控制器

- **`WebController.java`** — 页面路由：`/`、`/history`、`/settings`、`/resume`、`/knowledge`
- **`InterviewController.java`** — 面试业务 API：开始/回答/流式回答/停止/删除/报告；设置获取/保存；模型测试；简历上传；语音识别；向量引用管理
- **`VoiceController.java`** — 语音识别 API（百度/讯飞）

## dto/ — 数据传输对象

- **`StartConvoResponse.java`** — 开始面试返回值（convoId、欢迎语、首题、全量 Conversation）
- **`AnswerResponse.java`** — 回答提交返回值（评分、反馈、参考回答、下一题、是否结束）

## model/ — 数据模型

- **`AppSettings.java`** — 应用设置（面试官信息、公司、职位、LLM 配置、语音配置）
- **`Conversation.java`** — 面试会话（id、设置、消息列表、状态）
- **`Message.java`** — 单条消息（角色、内容、评分、反馈、参考回答）

## service/ — 业务逻辑层

- **`ConversationService.java`** — 核心编排中枢，管理面试生命周期，线程安全（ReentrantLock），持久化 conversations.json
- **`LlmService.java`** — OkHttp HTTP 客户端，支持同步/SSE 流式 LLM 调用，Resilience4j 重试+熔断+超时
- **`LlmHelper.java`** — LlmService 包装层，提供降级兜底值
- **`BgeEmbeddingService.java`** — Python 嵌入服务 HTTP 客户端（HttpURLConnection）
- **`VectorStoreService.java`** — 内存向量存储（ConcurrentHashMap），余弦相似度搜索，持久化 vector_refs.json
- **`DocumentParserService.java`** — PDF/DOCX/TXT 解析，magic byte 自动检测格式
- **`ReportService.java`** — Markdown 格式面试报告生成
- **`SettingsService.java`** — 设置读写，持久化 settings.json

## util/ — 工具类

- **`JsonFileUtil.java`** — JSON 文件读写工具

---

## 核心工作流程

1. 用户配置 LLM 和语音凭证（settings）
2. 可选上传简历（resume）→ ResumeAgent 解析
3. 开始面试 → InterviewerAgent 出题（五阶段 + RAG 检索）
4. 用户回答（文字/语音）→ EvaluatorAgent 评分（四维 0-10）
5. 循环或结束 → ReportService 生成 Markdown 报告
