@echo off
rem ============================================================================
rem  start.bat —— 一键启动「后端 + 前端」（Windows cmd / PowerShell）
rem
rem  会开两个独立窗口：
rem    后端  http://127.0.0.1:8002   （Spring Boot）
rem    前端  http://localhost:5174   （Vite dev server）
rem
rem  关掉那两个窗口即可停止。只想启动后端时，直接双击 backend\run.bat。
rem ============================================================================
chcp 65001 >nul
cd /d "%~dp0"

if not exist "frontend\node_modules" (
    echo ⚠️  前端依赖尚未安装（frontend\node_modules 不存在）。
    echo     首次运行请先执行：
    echo         cd frontend
    echo         npm install
    echo.
    echo     本次将只启动后端。
    echo.
    goto :backend_only
)

start "IPAS-Backend" cmd /k "cd /d "%~dp0backend" && call run.bat"
start "IPAS-Frontend" cmd /k "cd /d "%~dp0frontend" && npm run dev"

echo.
echo   后端: http://127.0.0.1:8002/api/health
echo   前端: http://localhost:5174
echo   演示账号: demo / demo123456
echo.
echo   已启动两个窗口，稍等几秒后浏览器打开 http://localhost:5174
pause
exit /b 0

:backend_only
start "IPAS-Backend" cmd /k "cd /d "%~dp0backend" && call run.bat"
pause
exit /b 0
