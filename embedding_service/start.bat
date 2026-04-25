@echo off
:: ============================================================
:: BGE Embedding Service 启动脚本（Windows）
:: ============================================================
:: 首次运行：双击 start.bat
:: ============================================================

setlocal enabledelayedexpansion

set "SCRIPT_DIR=%~dp0"
set "VENV_DIR=%SCRIPT_DIR%venv"
set "PYTHON=%SCRIPT_DIR%venv\Scripts\python.exe"
set "UVICORN=%SCRIPT_DIR%venv\Scripts\uvicorn.exe"

:: ---------- 检查 Python ----------
if not exist "%PYTHON%" (
    echo [*] 创建 Python 虚拟环境...
    python -m venv "%VENV_DIR%"
)

echo [*] 激活虚拟环境...
call "%VENV_DIR%\Scripts\activate.bat"

:: ---------- 检查依赖 ----------
%PYTHON% -c "import sentence_transformers" 2>nul
if errorlevel 1 (
    echo [*] 安装依赖（首次需要下载模型约 5 分钟）...
    pip install --upgrade pip
    pip install -r requirements.txt
    echo.
    echo [+] 安装完成！首次运行会自动下载 bge-small-zh-v1.5 模型
    echo.
)

:: ---------- 启动服务 ----------
echo [*] 启动 BGE Embedding Service on http://localhost:8001 ...
echo [*] 按 Ctrl+C 停止服务
echo.
uvicorn app:app --host 0.0.0.0 --port 8001 --reload

pause
