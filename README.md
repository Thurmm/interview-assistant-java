# 🎯 面试模拟助手（Java Spring AI 版本）

AI 驱动的面试模拟工具，基于 Java Spring Boot + Spring AI 构建，支持多模型切换。

## ✨ 功能特性

- **AI 动态出题** — 基于岗位信息动态生成面试问题
- **语音输入** — 支持百度、讯飞语音识别
- **实时评分** — 每道题回答后立即打分（0-10），附详细点评和参考回答
- **历史记录** — 保存每次面试记录，随时回顾复盘
- **报告导出** — 一键生成 Markdown 格式面试报告
- **多模型支持** — OpenAI GPT / Claude / MiniMax / 自定义 API

## 🛠️ 技术栈

- **框架**: Spring Boot 3.3 + Spring AI 1.0.0-M4
- **模板引擎**: Thymeleaf
- **JSON**: Jackson
- **构建**: Maven
- **JDK**: 17+

## 🚀 快速启动

### 1. 克隆项目

```bash
git clone https://github.com/Thurmm/interview-assistant.git
cd interview-assistant-java
```

### 2. 编译

```bash
./mvnw clean package -DskipTests
```

### 3. 运行

```bash
# 设置 API Key（任选一种）
export OPENAI_API_KEY=sk-...

# 或者运行
java -jar target/interview-assistant-1.0.0.jar
```

然后打开 http://localhost:5000

### 4. 开发模式

```bash
./mvnw spring-boot:run
```

## ⚙️ 配置说明

在 **面试设置** 页面配置：

- **大模型 API**：填入 API Key，选择模型类型
  - `openai` — OpenAI GPT 系列
  - `claude` — Anthropic Claude 系列
  - `minimax` — MiniMax 海螺系列
  - `custom` — 兼容 OpenAI 格式的自定义 API
- **语音识别**（可选）：填入百度或讯飞的 App ID 和密钥

## 📁 项目结构

```
src/main/java/com/interview/assistant/
├── InterviewAssistantApplication.java   # 启动类
├── config/                               # 配置类
│   ├── JacksonConfig.java
│   └── SpringAiConfig.java
├── controller/                           # REST 控制器
│   ├── InterviewController.java          # 面试相关 API
│   ├── VoiceController.java               # 语音识别 API
│   └── WebController.java                 # 页面路由
├── dto/                                  # 数据传输对象
├── model/                                # 数据模型
├── service/                              # 业务逻辑
│   ├── ConversationService.java           # 对话管理
│   ├── LlmService.java                    # LLM 调用
│   ├── ReportService.java                 # 报告生成
│   └── SettingsService.java               # 设置管理
└── util/                                 # 工具类
    └── JsonFileUtil.java                  # JSON 文件存储

src/main/resources/
├── application.yml                       # 应用配置
└── templates/                             # HTML 模板（与 Python 版共用）
```

## ⚠️ 注意事项

- 首次运行会在项目根目录创建 `data/` 文件夹存储对话数据
- 语音识别功能需要额外配置百度/讯飞 API
- Spring AI 1.0.0-M4 为里程碑版本，生产环境请评估稳定性

## 📝 与 Python 版本对比

| 特性 | Python Flask 版 | Java Spring AI 版 |
|------|----------------|------------------|
| AI 模型 | ✅ | ✅ |
| 语音识别 | ✅ | ✅（讯飞 WebSocket 需扩展） |
| 报告导出 | ✅ | ✅ |
| 历史记录 | ✅ | ✅ |
| 数据库 | JSON 文件 | JSON 文件 |
