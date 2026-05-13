# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Test Commands

```bash
# Build (skip tests)
./mvnw clean package -DskipTests

# Run all tests
./mvnw test

# Run a single test class
./mvnw test -Dtest=EvaluatorAgentTest

# Run a single test method
./mvnw test -Dtest=EvaluatorAgentTest#testEvaluateAnswer

# Run in dev mode (hot reload)
./mvnw spring-boot:run
```

**Prerequisites:** JDK 17+. The Python embedding service (`embedding_service/`) must run on port 8001 for RAG features — start with `bash embedding_service/start.sh`.

**Startup:** `http://localhost:5000` | Swagger: `http://localhost:5000/swagger-ui.html`

**Sensitive-data masking:** API keys in logs are automatically redacted by `ApiKeyMaskConverter` and `MaskingLogbackEncoder`. No manual credential cleanup needed.

## Architecture Overview

### Three-Agent + RAG interview system

The app simulates technical interviews using a multi-agent architecture orchestrated by `ConversationService`:

1. **ResumeAgent** (`agent/ResumeAgent.java`) — Parses uploaded PDF/DOCX/TXT resumes via LLM, extracting structured candidate profiles (name, skills, work history, etc.).

2. **InterviewerAgent** (`agent/InterviewerAgent.java`) — Generates questions across five phases (OPENING, TECHNICAL, BEHAVIORAL, DEEP_DIVE, WRAP_UP). Retrieves RAG context from the vector store for skill-specific questions.

3. **EvaluatorAgent** (`agent/EvaluatorAgent.java`) — Scores each answer 0-10 across four dimensions (technical depth, expression clarity, logic coherence, experience relevance). Returns feedback and a reference answer.

**Data flow:** Settings → Resume upload (optional) → Start interview → InterviewerAgent asks question → User answers → EvaluatorAgent scores → InterviewerAgent decides next question or wrap-up → ReportService generates Markdown report.

### Key services

- **ConversationService** — Central orchestrator, manages conversation lifecycle, thread-safe via per-conversation `ReentrantLock`, persists to `data/conversations.json`
- **LlmService** — OkHttp-based HTTP client for LLM calls (sync + SSE streaming), with Resilience4j retry (3 attempts, exponential backoff)
- **LlmHelper** — Wrapper around LlmService with fallback values for graceful degradation
- **VectorStoreService** — In-memory vector store (`ConcurrentHashMap`) with cosine-similarity search and JSON persistence to `data/vector_refs.json`
- **DocumentParserService** — PDF/DOCX/TXT parsing with magic-byte fallback detection
- **SettingsService** — Settings CRUD backed by `data/settings.json`
- **ReportService** — Generates Markdown interview reports for download

### Embedding service (Python sidecar)

A FastAPI server in `embedding_service/` serves `BAAI/bge-small-zh-v1.5` for text vectorization (512-dimensional). Endpoints: `GET /health`, `POST /v1/embeddings`, `POST /embed`. The Java side connects via `BgeEmbeddingService` (pure Java `HttpURLConnection`) or `EmbeddingService` (Spring `RestClient`).

### External integrations

- **LLM providers:** OpenAI GPT, Anthropic Claude, MiniMax, custom OpenAI-compatible APIs — configured via settings page, stored in `data/settings.json`
- **Voice recognition:** Baidu and iFlytek — optional, configured via settings page
- **Vector store:** In-memory by default (Qdrant config exists but is unused — `QdrantConfig` is repurposed as a generic LLM HTTP client with retry)

### Configuration

- `src/main/resources/application.yml` — Server port (5000), file upload limits, default embedding provider, Resilience4j settings
- `logback-spring.xml` — Console + rolling file logging with API key masking
- `data/*.json` — Runtime data files (all gitignored): conversations, settings, resumes, vector references

### REST API

Key endpoints under `/api/`: `/conversation/start`, `/conversation/{id}/answer`, `/conversation/{id}/stream-answer` (SSE), `/conversation/{id}/report`, `/settings`, `/resume/upload`, `/voice/recognize`, `/vector/reference`. Page routes (`/`, `/history`, `/settings`, `/resume`, `/knowledge`) served via Thymeleaf templates in `src/main/resources/templates/`.

### Key design decisions

- **No database** — All state persisted as JSON files in `data/`
- **Graceful degradation** — Every LLM call has a fallback value via `LlmHelper`; individual agent failures don't crash the app
- **Thread safety** — `ConversationService` uses `ReentrantLock` per conversation ID for safe concurrent access
- **Resilience** — Resilience4j retry (3 attempts, exponential backoff), circuit breaker (50% threshold), and 30s timeout on all LLM calls
- **No current test for LlmService** — `LlmService` is not covered by tests; add tests if modifying HTTP/streaming logic
