@echo off
chcp 65001 >nul
cd /d "%~dp0"
set "AGENT_DIR=%~dp0agent"
set "BACKEND_DIR=%~dp0backend"
set "VENV_PYTHON=%AGENT_DIR%\.venv\Scripts\python.exe"
set "CURL=%SystemRoot%\System32\curl.exe"

echo ========================================
echo   Knowledge Agent 一键启动
echo ========================================
echo.

:: ── 1. Docker ──────────────────────────
echo [1/4] 检测 Docker...
docker info >nul 2>&1
if %ERRORLEVEL% NEQ 0 (
    echo   [错误] Docker Desktop 未运行，请先启动再运行此脚本。
    pause && exit /b 1
)
docker-compose up -d 2>nul
echo   等待 MySQL 就绪...
:wait_mysql
docker exec ka-mysql mysqladmin ping -h localhost --silent >nul 2>&1
if %ERRORLEVEL% NEQ 0 ( timeout /t 2 /nobreak >nul && goto wait_mysql )
echo   MySQL / Redis / Milvus 已就绪

:: ── 2. Agent ───────────────────────────
echo.
echo [2/4] 启动 Agent (GPU) ...
start "Knowledge Agent" cmd /c "cd /d %AGENT_DIR% && set KA_EMBEDDING_DEVICE=cuda && %VENV_PYTHON% main.py"
echo   等待 Agent 就绪...
:wait_agent
%CURL% -s http://localhost:8000/api/v1/rag/health >nul 2>&1
if %ERRORLEVEL% NEQ 0 ( timeout /t 1 /nobreak >nul && goto wait_agent )
echo   Agent 已就绪

:: ── 3. 入库 ────────────────────────────
echo.
echo [3/4] 文档入库 ...
cd /d "%AGENT_DIR%"
set HF_HUB_OFFLINE=1
%VENV_PYTHON% scripts\quick_ingest.py
echo   入库完成

:: ── 4. Spring Boot ─────────────────────
echo.
echo [4/4] 启动 Spring Boot ...
start "Spring Boot" cmd /c "cd /d %BACKEND_DIR% && mvn spring-boot:run"
echo   等待 Spring Boot 就绪...
:wait_boot
%CURL% -s -o nul -w "%%{http_code}" http://localhost:8080/ | findstr "200" >nul 2>&1
if %ERRORLEVEL% NEQ 0 ( timeout /t 2 /nobreak >nul && goto wait_boot )

echo.
echo ========================================
echo   启动完成！
echo   前端: http://localhost:8080
echo ========================================
start http://localhost:8080
pause
