#!/bin/bash
# ============================================================
# BGE Embedding Service 启动脚本（Linux / macOS / WSL）
# ============================================================
# 首次运行：bash start.sh --setup
# 正常运行：bash start.sh

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"
VENV_DIR="$SCRIPT_DIR/venv"

# ---------- 首次安装 ----------
setup() {
    echo "[*] 创建 Python 虚拟环境..."
    python3 -m venv "$VENV_DIR"

    echo "[*] 激活虚拟环境..."
    source "$VENV_DIR/bin/activate"

    echo "[*] 安装依赖（首次需要下载模型约 5 分钟）..."
    "$VENV_DIR/bin/pip" install --upgrade pip
    "$VENV_DIR/bin/pip" install -r requirements.txt

    echo ""
    echo "[✅] 安装完成！首次运行会自动下载 bge-small-zh-v1.5 模型（约 233MB）"
    echo "     模型会缓存到 ~/.cache/huggingface/，以后启动无需重复下载"
    echo ""
}

# ---------- 启动服务 ----------
start() {
    source "$VENV_DIR/bin/activate"
    echo "[*] 启动 BGE Embedding Service on http://localhost:8001 ..."
    "$VENV_DIR/bin/python" -m uvicorn app:app --host 0.0.0.0 --port 8001 --reload
}

# ---------- 主逻辑 ----------
if [[ "$1" == "--setup" || "$1" == "-s" ]]; then
    setup
    exit 0
fi

if [[ ! -d "$VENV_DIR" ]]; then
    echo "[!] 虚拟环境不存在，先运行: bash start.sh --setup"
    exit 1
fi

start
