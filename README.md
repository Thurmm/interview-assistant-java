# 面试模拟助手（Java Spring 版本）

AI 驱动的面试模拟工具，采用 **三 Agent + RAG** 多智能体架构，基于 Java Spring Boot 构建，支持多 LLM 模型切换。

## 功能特性

- **三 Agent 协同** — 简历解析 Agent、面试官 Agent、评分 Agent 分工协作
- **RAG 知识库** — 嵌入向量化简历/知识库，针对技能点精准提问
- **AI 动态出题** — 五阶段面试流程（开场 → 技术 → 行为 → 深挖 → 收尾）
- **实时评分** — 每道题从技术深度、表达清晰度、逻辑连贯性、经验相关性四维评分（0-10）
- **流式回答** — SSE 流式接口，实时展示回答内容
- **语音输入** — 支持百度、讯飞语音识别
- **简历解析** — 自动解析 PDF/DOCX/TXT 简历提取候选人画像
- **历史记录** — 保存面试记录，支持 Markdown 报告导出
- **多模型支持** — OpenAI GPT / Anthropic Claude / MiniMax / 自定义兼容 API

## 技术栈

- **框架**: Spring Boot 3.3
- **模板引擎**: Thymeleaf
- **LLM 调用**: OkHttp + Resilience4j（重试/熔断/超时）
- **向量嵌入**: BAAI/bge-small-zh-v1.5（Python FastAPI 侧车服务）
- **构建**: Maven (JDK 17+)
- **数据存储**: JSON 文件（无数据库依赖）

## 快速启动

### 前置条件

- JDK 17+
- Python 3.9+（嵌入服务需要）

### 1. 克隆并编译

```bash
git clone https://github.com/Thurmm/interview-assistant-java.git
cd interview-assistant-java
./mvnw clean package -DskipTests
```

### 2. 启动嵌入服务（可选，用于 RAG 功能）

```bash
bash embedding_service/start.sh
```

嵌入服务运行在 http://localhost:8001，提供文本向量化能力。

### 3. 启动应用

```bash
./mvnw spring-boot:run
```

或直接运行 JAR：

```bash
java -jar target/interview-assistant-1.0.0.jar
```

### 4. 访问

打开 http://localhost:5000

- Swagger 文档：http://localhost:5000/swagger-ui.html

## 配置说明

在 **设置页面** 配置以下内容：

### LLM 模型

| 类型 | 说明 |
|------|------|
| `openai` | OpenAI GPT 系列（GPT-4、GPT-4o-mini 等） |
| `claude` | Anthropic Claude 系列（Sonnet、Opus 等） |
| `minimax` | MiniMax 海螺系列 |
| `custom` | 兼容 OpenAI 格式的自定义 API |

### 语音识别（可选）

- 百度语音识别 — 填入 App ID / API Key / Secret Key
- 讯飞语音识别 — 填入 App ID / API Key / API Secret

### 知识库管理

在知识库页面上传简历或参考资料，系统自动向量化存储，面试时用于精准提问。

## 架构概览

### 三 Agent 编排

```
用户设置 → 上传简历（可选） → 开始面试
                                 ↓
                    ┌─────────────────────────┐
                    │   ResumeAgent           │
                    │   (简历解析，提取画像)      │
                    └─────────┬───────────────┘
                              ↓
                    ┌─────────────────────────┐
                    │   InterviewerAgent      │
                    │   (五阶段出题 + RAG 检索)  │
                    └─────────┬───────────────┘
                              ↓
                    用户回答问题
                              ↓
                    ┌─────────────────────────┐
                    │   EvaluatorAgent        │
                    │   (四维评分 + 反馈 + 参考) │
                    └─────────┬───────────────┘
                              ↓
                     循环 或 结束 → ReportService 生成报告
```

### 核心模块

- **ConversationService** — 对话编排中枢，线程安全（ReentrantLock），持久化到 `data/conversations.json`
- **LlmService** — OkHttp HTTP 客户端，支持同步/SSE 流式调用，Resilience4j 重试（3 次指数退避）+ 熔断（50% 阈值）+ 30s 超时
- **LlmHelper** — LlmService 的包装层，提供降级兜底值，单 Agent 故障不影响整体
- **VectorStoreService** — 内存向量存储（ConcurrentHashMap），余弦相似度搜索，持久化到 `data/vector_refs.json`
- **DocumentParserService** — PDF/DOCX/TXT 解析，magic byte 自动检测格式
- **EmbeddingService** — Python 侧车嵌入服务，使用 BAAI/bge-small-zh-v1.5 生成 512 维向量

### 设计原则

- **零数据库** — 所有状态以 JSON 文件存储于 `data/` 目录
- **优雅降级** — 每个 LLM 调用都有兜底值，单 Agent 失败不影响全局
- **线程安全** — 按 conversation ID 加 ReentrantLock，支持并发访问
- **敏感信息脱敏** — 日志中的 API Key 自动用 `ApiKeyMaskConverter` 掩码

## 项目结构

```
src/main/java/com/interview/assistant/
├── InterviewAssistantApplication.java   # 启动类
├── agent/
│   ├── ResumeAgent.java                  # 简历解析 Agent
│   ├── InterviewerAgent.java             # 面试官 Agent
│   └── EvaluatorAgent.java              # 评分 Agent
├── config/                               # 配置类
│   ├── WebConfig.java
│   ├── JacksonConfig.java
│   └── EmbeddingConfig.java
├── controller/                           # REST 控制器
│   ├── InterviewController.java          # 面试 API（含 SSE 流式接口）
│   ├── VoiceController.java              # 语音识别 API
│   └── WebController.java                # 页面路由
├── dto/                                  # 数据传输对象
├── model/                                # 数据模型
├── service/                              # 业务逻辑
│   ├── ConversationService.java          # 对话编排
│   ├── LlmService.java                   # LLM 调用（含重试/熔断）
│   ├── LlmHelper.java                    # LLM 降级包装
│   ├── BgeEmbeddingService.java          # 向量嵌入调用
│   ├── VectorStoreService.java           # 向量存储与检索
│   ├── DocumentParserService.java        # 文档解析
│   ├── ReportService.java                # 报告生成
│   └── SettingsService.java              # 设置管理
└── util/                                 # 工具类
    └── JsonFileUtil.java                 # JSON 文件存储

embedding_service/                        # Python 嵌入侧车服务
├── main.py                               # FastAPI 服务（bge-small-zh-v1.5）
└── start.sh                              # 启动脚本

src/main/resources/
├── application.yml                       # 应用配置
├── logback-spring.xml                    # 日志配置（API Key 掩码）
├── static/css/style.css                  # 前端样式
└── templates/                            # Thymeleaf 模板
    ├── index.html                        # 面试主页
    ├── history.html                      # 历史记录
    ├── settings.html                     # 设置页
    ├── resume.html                       # 简历管理
    └── knowledge.html                    # 知识库管理
```

## REST API

| 端点 | 方法 | 说明 |
|------|------|------|
| `/api/conversation/start` | POST | 开启新面试 |
| `/api/conversation/{id}/answer` | POST | 提交回答 |
| `/api/conversation/{id}/stream-answer` | POST | SSE 流式回答 |
| `/api/conversation/{id}/stop` | POST | 停止面试 |
| `/api/conversation/{id}/delete` | DELETE | 删除对话 |
| `/api/conversation/{id}/report` | GET | 下载 Markdown 报告 |
| `/api/settings` | GET/POST | 获取/保存设置 |
| `/api/model/test` | POST | 测试 LLM 连接 |
| `/api/resume/upload` | POST | 上传简历 |
| `/api/voice/recognize` | POST | 语音识别 |
| `/api/vector/reference` | POST | 添加知识库引用 |

## 注意事项

- 首次运行会在项目根目录创建 `data/` 文件夹存储对话数据
- Python 嵌入服务为可选，不启动时 RAG 功能不可用，但不影响基础面试流程
- 日志中的 API Key 已自动脱敏，无需手动清理
- 语音识别功能需要额外配置百度/讯飞 API
