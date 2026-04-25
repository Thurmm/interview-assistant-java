"""
BGE-small-zh-v1.5 本地嵌入服务
===================================
使用 HuggingFace sentence-transformers 加载 bge-small-zh-v1.5 模型，
通过 FastAPI 对外提供 HTTP 嵌入接口。

依赖：Python 3.10+，transformers, sentence-transformers, fastapi, uvicorn
安装：pip install -r requirements.txt
启动：uvicorn app:app --host 0.0.0.0 --port 8001
"""

from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel
from typing import Union, List
import torch
import logging
import time

from sentence_transformers import SentenceTransformer

# ============ 日志配置 ============
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
)
logger = logging.getLogger(__name__)

# ============ 全局配置 ============
MODEL_NAME = "BAAI/bge-small-zh-v1.5"  # 233MB，支持中文，CPU/GPU 通用
DEVICE = "cuda" if torch.cuda.is_available() else "cpu"
PORT = 8001

# ============ 加载模型 ============
logger.info(f"正在加载模型 {MODEL_NAME}，设备={DEVICE}，首次加载可能需要下载模型（约 5 分钟）...")
start = time.time()
model = SentenceTransformer(MODEL_NAME, device=DEVICE)
model.eval()
logger.info(f"模型加载完成，耗时 {time.time() - start:.1f}s")

# ============ FastAPI 应用 ============
app = FastAPI(
    title="BGE Embedding Service",
    description="bge-small-zh-v1.5 本地语义向量生成服务",
    version="1.0.0",
)

# 允许跨域（Java 前端访问）
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


# ============ 请求/响应模型 ============
class EmbedRequest(BaseModel):
    model: str = MODEL_NAME
    texts: Union[List[str], str]
    normalize: bool = True  # 是否归一化向量


class EmbedResponse(BaseModel):
    model: str
    device: str
    dim: int
    processing_time_ms: float
    embeddings: List[List[float]]


class HealthResponse(BaseModel):
    status: str
    model: str
    device: str
    dim: int


# ============ 接口实现 ============

@app.get("/health", response_model=HealthResponse)
def health():
    """健康检查"""
    return HealthResponse(
        status="ok",
        model=MODEL_NAME,
        device=DEVICE,
        dim=model.get_sentence_embedding_dimension(),
    )


@app.post("/v1/embeddings", response_model=EmbedResponse)
def embed(req: EmbedRequest):
    """嵌入接口（OpenAI-compatible 路径）"""
    t0 = time.time()

    # 统一转换为 list
    if isinstance(req.texts, str):
        texts = [req.texts]
    else:
        texts = req.texts

    if not texts:
        raise HTTPException(status_code=400, detail="texts cannot be empty")

    try:
        # encode 返回 numpy.ndarray (N, dim)
        embeddings = model.encode(
            texts,
            normalize_embeddings=req.normalize,
            convert_to_numpy=True,
            convert_to_tensor=False,
            show_progress_bar=False,
        )
    except Exception as e:
        logger.error(f"模型推理失败: {e}")
        raise HTTPException(status_code=500, detail=f"推理失败: {str(e)}")

    # 转 Python float 列表
    emb_list = [row.tolist() for row in embeddings]

    return EmbedResponse(
        model=MODEL_NAME,
        device=DEVICE,
        dim=model.get_sentence_embedding_dimension(),
        processing_time_ms=round((time.time() - t0) * 1000, 2),
        embeddings=emb_list,
    )


@app.post("/embed", response_model=EmbedResponse)
def embed_simple(req: EmbedRequest):
    """简版嵌入接口（兼容任意客户端）"""
    return embed(req)


if __name__ == "__main__":
    import uvicorn
    logger.info(f"启动 BGE Embedding Service，监听 0.0.0.0:{PORT}")
    uvicorn.run(app, host="0.0.0.0", port=PORT, log_level="info")
