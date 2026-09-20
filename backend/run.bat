@echo off
rem ============================================================================
rem  run.bat - Start the IPAS Java backend (Windows cmd / PowerShell)
rem
rem  Why not just call "mvnw.cmd spring-boot:run" directly? Two gotchas:
rem    1. Some terminals inject SERVER_PORT / SERVER__PORT env vars that override
rem       server.port in application.yml, so the app "starts" but you cannot
rem       reach it on port 8002. We clear those vars here.
rem    2. We also pass --server.port=8002 explicitly as a second safety net
rem       (the frontend vite proxy is hardcoded to http://127.0.0.1:8002).
rem
rem  Maven selection: the repo's Maven Wrapper (mvnw.cmd) needs the .mvn/wrapper
rem  dir (wrapper jar + properties). If that dir is missing, mvnw fails. We then
rem  fall back to the system Maven install. Resolution order:
rem    1) .mvn/wrapper present        -> mvnw.cmd  (portable default)
rem    2) MAVEN_HOME env is set       -> %MAVEN_HOME%\bin\mvn.cmd
rem    3) known local Maven path      -> use it directly (verified on dev box)
rem    4) none of the above           -> mvn.cmd   (relies on PATH)
rem
rem  NOTE: keep this file ASCII-only (no Chinese). cmd.exe reads .bat files in
rem  the system ANSI codepage (GBK on zh-CN Windows); UTF-8 Chinese comments get
rem  mangled and can break the script.
rem ============================================================================
cd /d "%~dp0"

set SERVER_PORT=
set SERVER__PORT=

set "MVN_CMD="
if exist "%~dp0.mvn\wrapper\maven-wrapper.jar" (
  set "MVN_CMD=mvnw.cmd"
) else if defined MAVEN_HOME (
  set "MVN_CMD=%MAVEN_HOME%\bin\mvn.cmd"
) else if exist "D:\Maven\apache-maven-3.8.9-bin\apache-maven-3.8.9\bin\mvn.cmd" (
  set "MVN_CMD=D:\Maven\apache-maven-3.8.9-bin\apache-maven-3.8.9\bin\mvn.cmd"
) else (
  set "MVN_CMD=mvn.cmd"
)

echo [run.bat] Starting IPAS backend on port 8002, Maven=%MVN_CMD%
echo [run.bat] Health check: http://127.0.0.1:8002/api/health
echo.

%MVN_CMD% -B spring-boot:run -Dspring-boot.run.arguments=--server.port=8002
