@echo off
chcp 65001 >nul
cd /d "%~dp0"
set "AGENT_DIR=%~dp0agent"
set "BACKEND_DIR=%~dp0backend"
set "FRONTEND_DIR=%~dp0frontend"
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
start "KA Agent" cmd /c "cd /d %AGENT_DIR% && set KA_EMBEDDING_DEVICE=cuda && %VENV_PYTHON% main.py"
echo   等待 Agent 就绪...
:wait_agent
%CURL% -s http://localhost:8000/api/v1/rag/health >nul 2>&1
if %ERRORLEVEL% NEQ 0 ( timeout /t 1 /nobreak >nul && goto wait_agent )
echo   Agent 已就绪

:: ── 3. Spring Boot ─────────────────────
echo.
echo [3/4] 启动 Spring Boot ...
if exist "%BACKEND_DIR%\target\knowledge-agent-backend-1.0.0.jar" (
    start "KA Backend" cmd /c "cd /d %BACKEND_DIR% && java -jar target\knowledge-agent-backend-1.0.0.jar --spring.profiles.active=dev"
) else (
    echo   未找到 jar，使用 mvn spring-boot:run（首次较慢）...
    start "KA Backend" cmd /c "cd /d %BACKEND_DIR% && mvn spring-boot:run"
)
echo   等待 Spring Boot 就绪...
:wait_boot
%CURL% -s http://localhost:8080/api/health >nul 2>&1
if %ERRORLEVEL% NEQ 0 ( timeout /t 2 /nobreak >nul && goto wait_boot )
echo   Backend 已就绪

:: ── 4. Vue 前端 ────────────────────────
echo.
echo [4/4] 启动 Vue 前端 (Vite) ...
if not exist "%FRONTEND_DIR%\node_modules" (
    echo   首次运行，安装前端依赖（npm install）...
    cd /d "%FRONTEND_DIR%" && call npm install
)
start "KA Frontend" cmd /c "cd /d %FRONTEND_DIR% && npx vite --host"
echo   等待 Vite 就绪...
:wait_vite
%CURL% -s -o nul http://localhost:9888 >nul 2>&1
if %ERRORLEVEL% NEQ 0 ( timeout /t 1 /nobreak >nul && goto wait_vite )

echo.
echo ========================================
echo   启动完成！
echo   前端: http://localhost:9888
echo   Agent: http://localhost:8000/api/v1/rag/health
echo   Backend: http://localhost:8080/api/health
echo ========================================
start http://localhost:9888
pause
