@echo off
rem ============================================================================
rem  start-pgvector.bat - Start PostgreSQL (with pgvector) inside WSL
rem
rem  Why a script: a WSL distribution does NOT auto-start at boot. So after a
rem  reboot you must wake the distro and start PostgreSQL before the backend can
rem  use the vector store. Double-click this file instead of remembering
rem  commands.
rem
rem  It starts the service as root, then waits for port 5432 to become visible
rem  on the Windows side (WSL2 forwards localhost, so the app connects to
rem  127.0.0.1:5432 - never hard-code the distro's own IP, it changes).
rem
rem  Reboot-to-ready cheat sheet printed at the end.
rem
rem  NOTE: keep this file ASCII-only (no Chinese). cmd.exe reads .bat files in
rem  the system ANSI codepage (GBK on zh-CN Windows); UTF-8 Chinese comments get
rem  mangled and can break the script.
rem ============================================================================
setlocal
set "DISTRO=Ubuntu-22.04"

echo [start-pgvector.bat] distro = %DISTRO%
echo [start-pgvector.bat] starting PostgreSQL...

rem 先试 sysvinit 风格的 service；WSL 默认不启用 systemd，偶尔不灵。
wsl.exe -d %DISTRO% -u root service postgresql start
if not errorlevel 1 goto started

rem 兜底：Debian/Ubuntu 原生的集群管理命令，不依赖 systemd（最可靠）。
echo [start-pgvector.bat] "service" failed, trying pg_ctlcluster...
wsl.exe -d %DISTRO% -u root pg_ctlcluster 16 main start
if errorlevel 1 (
  echo.
  echo [start-pgvector.bat] FAILED to start the service.
  echo   - Is the distro installed?   wsl --list --verbose
  echo   - Is PostgreSQL installed?   wsl -d %DISTRO% -u root pg_lsclusters
  echo.
  pause
  exit /b 1
)

:started

echo [start-pgvector.bat] waiting for port 5432 on the Windows side...
set /a tries=0
:wait
set /a tries+=1
netstat -ano | findstr ":5432" >nul
if not errorlevel 1 goto ready
if %tries% GEQ 20 goto nodetect
timeout /t 1 /nobreak >nul
goto wait

:ready
echo.
echo [start-pgvector.bat] READY
echo   host     : 127.0.0.1
echo   port     : 5432
echo   database : ipas_vectors
echo   (user/password: see your own setup notes)
echo.
echo Backend side, switch the vector store with:
echo   set VECTOR_STORE=pgvector
echo   set PGVECTOR_URL=jdbc:postgresql://127.0.0.1:5432/ipas_vectors
echo   set PGVECTOR_USER=ipas
echo   set PGVECTOR_PASSWORD=your-password
exit /b 0

:nodetect
echo.
echo [start-pgvector.bat] Service started, but port 5432 is not visible on Windows yet.
echo   This is often harmless - WSL's localhost relay may bind slightly later.
echo   Verify manually from the distro:
echo     wsl -d %DISTRO% -u root pg_lsclusters
echo     wsl -d %DISTRO% -u root ss -lntp ^| findstr 5432
exit /b 0
