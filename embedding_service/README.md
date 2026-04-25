# BGE-small-zh-v1.5 本地嵌入服务

使用 HuggingFace `BAAI/bge-small-zh-v1.5` 模型（233MB）为面试助手提供本地语义向量生成，支持中文文本的精准语义检索。

## 模型说明

- **模型**：BAAI/bge-small-zh-v1.5
- **大小**：约 233MB
- **向量维度**：512 维
- **特点**：专为中文语义相似度优化，CPU/GPU 均可运行
- **优势**：完全免费、无 API 限流、无账户欠费风险

## 快速开始

### Windows

```bash
# 首次安装（自动下载模型约 5 分钟）
双击 start.bat

# 以后启动
双击 start.bat
```

### Linux / macOS / WSL

```bash
# 首次安装
bash start.sh --setup

# 以后启动
bash start.sh
```

## 验证服务是否正常运行

服务启动后，访问：`http://localhost:8001/docs`

或直接测试：
```bash
curl http://localhost:8001/health
# 应返回：{"status":"ok","model":"BAAI/bge-small-zh-v1.5","device":"cpu","dim":512}
```

## Java 应用配置

在 `application.yml` 中已配置使用 BGE：

```yaml
spring:
  ai:
    embedding:
      provider: bge        # 使用本地 BGE（不是 minimax）
      bge:
        base-url: http://localhost:8001
```

**注意**：必须先启动 `start.bat`（Windows）或 `bash start.sh`（Linux），Java 应用才能进行知识库检索。

## 故障排除

### `Connection refused` 或 `BGE_SERVICE_UNAVAILABLE`

确保 Python 嵌入服务已启动：
```bash
# Windows: 双击 start.bat
# Linux/WSL: bash start.sh
```

### 模型下载失败（首次安装）

需要网络连接以下载模型（约 233MB）：
```
https://huggingface.co/BAAI/bge-small-zh-v1.5
```

模型会自动缓存到 `~/.cache/huggingface/`。

### CUDA/GPU 加速

脚本会自动检测 GPU（NVIDIA CUDA）。如有 GPU，启动时会显示 `device: cuda`，推理速度大幅提升。
