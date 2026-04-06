@echo off
chcp 65001 >nul
echo ======================================
echo    AI 面试模拟助手 v1.0
echo ======================================
echo.
echo 正在启动，请稍候...
echo.

java -jar interview-assistant-1.0.0.jar

if errorlevel 1 (
    echo.
    echo [错误] 启动失败！
    echo 请确保已安装 JDK 17 或更高版本
    echo 下载地址: https://adoptium.net/
    echo.
    pause
)
