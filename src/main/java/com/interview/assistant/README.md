好了，下面是 `interview_assistant_java` 目录中每个文件的作用介绍：

---

## 📁 源码目录结构

### 🔧 启动类
- **`InterviewAssistantApplication.java`** — Spring Boot 启动入口，`main()` 方法加载应用。

### ⚙️ config/ — 配置类
- **`JacksonConfig.java`** — 配置 Jackson ObjectMapper，注册 JavaTimeModule 支持时间序列化，关闭日期时间戳格式，忽略未知属性。
- **`SpringAiConfig.java`** — 配置 Spring AI 的 `OpenAiApi` / `OpenAiChatModel` / `ChatClient` Bean，连接外部 LLM 服务。
- **`WebConfig.java`** — 配置静态资源映射，将 `/static/**` 路由到 `classpath:/static/` 和 `/public/`。

### 🎮 controller/ — 控制器（REST API）
- **`WebController.java`** — 页面路由控制器，负责返回 `/`、`/history`、`/settings` 三个 Thymeleaf 模板页面。
- **`InterviewController.java`** — 核心面试业务 API：
  - `GET /api/settings` — 获取设置
  - `POST /api/settings` — 保存设置
  - `POST /api/model/test` — 测试模型连接
  - `GET /api/conversations` — 获取所有对话
  - `POST /api/conversation/start` — 开启新面试
  - `POST /api/conversation/{id}/answer` — 提交回答
  - `POST /api/conversation/{id}/stop` — 停止面试
  - `DELETE /api/conversation/{id}/delete` — 删除对话
  - `GET /api/conversation/{id}/report` — 下载 Markdown 报告
- **`VoiceController.java`** — 语音识别 API：
  - `POST /api/voice/test` — 测试语音配置（支持百度/讯飞）
  - `POST /api/voice/recognize` — 接收音频文件，调用百度或讯飞 WebSocket 进行语音转文字（ASR）

### 📦 dto/ — 数据传输对象
- **`StartConvoResponse.java`** — 开始面试的返回值，包含 convoId、欢迎语、第一个问题、完整 Conversation 对象。
- **`AnswerResponse.java`** — 回答提交后的返回值，包含评分结果 `EvaluationResult`（score/feedback/modelAnswer）、下一题、是否结束、全部消息列表。

### 🗃️ model/ — 数据模型
- **`AppSettings.java`** — 应用全局设置（ Lombok Builder 模式），包含面试官姓名、公司、职位、年限、面试类型、语音提供商（百度/讯飞）、各提供商的凭证、`ModelConfig`（模型类型/API Key/Base URL/模型名）。
- **`Conversation.java`** — 一次面试会话，包含 id、创建/更新时间、设置、消息列表、当前题目索引、状态（in_progress/completed/stopped）。
- **`Message.java`** —  单条消息，包含 role（interviewer/user）、内容、时间戳、是否面试题、评分、反馈、参考回答。

### 🧠 service/ — 业务逻辑层
- **`SettingsService.java`** — 负责从 `data/settings.json` 读写应用设置，提供默认配置模板。
- **`LlmService.java`** — 调用 Spring AI `ChatClient` 与 LLM 交互，`callLlm()` 传入消息列表+模型配置+温度+最大token数，`testConnection()` 验证连接是否成功。
- **`ConversationService.java`** — 核心业务逻辑：
  - `startConversation()` — 初始化面试，创建 Conversation，调用 LLM 生成第一道题
  - `answer()` — 评估用户回答（LLM 生成评分+点评+参考回答），判断是否结束面试（最多10题，或由 LLM 判断），生成下一题
  - `stopConversation()` / `deleteConversation()` — 停止/删除会话
  - `generateInterviewQuestion()` — 用 prompt 让 LLM 扮演面试官出题
  - `evaluateAnswer()` — 用 prompt 让 LLM 评估回答（JSON 解析评分/反馈/参考回答）
  - `shouldEndInterview()` — 3题后由 LLM 判断面试是否该结束
  - `generateClosingMessage()` — 结束时生成告别语
- **`ReportService.java`** — 生成 Markdown 格式面试报告，包含面试概况（总分/最高/最低/平均）、每道题问答详情（🟢🟡🔴 颜色标记分数）、分类建议（重点强化/可提升/掌握良好）、总体建议。
- **`JsonFileUtil.java`** — JSON 文件存储工具，读写 `data/` 目录下的 JSON 文件（conversations.json / settings.json）。

---

## 🌐 resources/ — 前端资源
- **`application.yml`** — Spring Boot 配置（端口5000、文件上传限制、Jackson序列化设置、Thymeleaf缓存关闭、Spring AI API Key占位）
- **`templates/index.html`** — 面试主页（开始面试、实时对话、语音输入）
- **`templates/history.html`** — 历史记录页面（查看历史面试）
- **`templates/settings.html`** — 设置页面（配置模型、语音凭证）
- **`static/css/style.css`** — 前端样式

---

## 📄 项目配置文件
- **`pom.xml`** — Maven 构建配置，Spring Boot 3.3.5 + Spring AI 1.0.0-M4，依赖：spring-boot-starter-web、spring-ai-openai-spring-boot-starter、thymeleaf、okhttp（语音WebSocket用）、lombok
- **`launch4j-config.xml`** — Launch4j 配置（用于打包成 Windows .exe 启动器）
- **`启动面试助手.bat`** — Windows 快速启动脚本
- **`.gitignore`** — Git 忽略规则（target/、.idea/、data/ 等）
- **`README.md`** — 项目说明文档
- **`data/settings.json`** — 用户实际存储的设置（API Key等敏感信息，**不要提交到 git**）

---

## 🔑 核心工作流程简述

1. 用户在 **settings.html** 配置 LLM（API Key / 模型）和语音凭证
2. 用户在 **index.html** 点击"开始面试" → `POST /api/conversation/start`
3. `ConversationService` 调用 LLM 生成第一道面试题，显示给用户
4. 用户口述或打字回答 → `POST /api/conversation/{id}/answer`
5. `ConversationService` 调用 LLM 评分（0-10）+ 给反馈 + 给参考回答
6. 循环第 4-5 步（最多 10 题，或 LLM 判断该结束时停止）
7. 用户可在 **history.html** 查看历史记录，点击"导出报告" → `ReportService` 生成 Markdown 报告下载

|      |      |      |
|------|----------------|------------------|
|      |      |      |
|      |      |      |
|      |      |      |
|      |      |      |
|      |      |      |
