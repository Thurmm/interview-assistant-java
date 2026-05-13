@echo off
chcp 65001 >nul
title BGE Embedding Service

set "SCRIPT_DIR=%~dp0"
set "VENV_DIR=%SCRIPT_DIR%venv"
set "PYTHON=%VENV_DIR%\Scripts\python.exe"
set "PIP=%VENV_DIR%\Scripts\pip.exe"
set "UVICORN=%VENV_DIR%\Scripts\uvicorn.exe"

if not exist "%PYTHON%" (
    echo [*] Creating Python virtual environment...
    python -m venv "%VENV_DIR%"
)

echo [*] Activating virtual environment...
call "%VENV_DIR%\Scripts\activate.bat"

%PIP% show sentence-transformers >nul 2>&1
if errorlevel 1 (
    echo [*] Installing dependencies...
    call pip install --upgrade pip
    call pip install -r requirements.txt
    echo.
    echo [+] Installation complete! Model will download on first run.
    echo.
)

echo [*] Starting BGE Embedding Service on http://localhost:8001 ...
echo [*] Press Ctrl+C to stop
echo.
call uvicorn app:app --host 0.0.0.0 --port 8001 --reload

pause