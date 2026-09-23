@echo off
rem ============================================================================
rem  start-minio.bat - Start a local MinIO server (Windows)
rem
rem  Why: the app can keep uploaded raw files in MinIO instead of on local disk.
rem  Switch by setting STORAGE_BACKEND=minio for the backend process
rem  (see application.yml -> app.storage). Default is still "local".
rem
rem  Ports:  9000 = S3 API   (the app talks to this)
rem          9001 = console  (http://127.0.0.1:9001)
rem
rem  Credentials: override via MINIO_ROOT_USER / MINIO_ROOT_PASSWORD env vars.
rem  The values below are LOCAL DEFAULTS for development only.
rem  Never commit real credentials.
rem
rem  Data:  D:\MinIO\data - objects live here (MinIO stores each object as a
rem         folder containing xl.meta). Back it up if the data matters.
rem
rem  NOTE: keep this file ASCII-only (no Chinese). cmd.exe reads .bat files in
rem  the system ANSI codepage (GBK on zh-CN Windows); UTF-8 Chinese comments
rem  get mangled and can break the script.
rem ============================================================================
set "MINIO_EXE=D:\MinIO\minio.exe"
set "MINIO_DATA=D:\MinIO\data"

if not exist "%MINIO_EXE%" (
  echo [start-minio.bat] MinIO not found: %MINIO_EXE%
  echo   Download: https://dl.min.io/server/minio/release/windows-amd64/minio.exe
  pause
  exit /b 1
)

rem  The defaults below are MinIO's own well-known DEVELOPMENT credentials - deliberately
rem  obvious, so nobody mistakes them for a real secret. This file is committed to the
rem  repository, so no real credential belongs here; override via the env vars above.
if "%MINIO_ROOT_USER%"=="" set "MINIO_ROOT_USER=minioadmin"
if "%MINIO_ROOT_PASSWORD%"=="" set "MINIO_ROOT_PASSWORD=minioadmin"

echo [start-minio.bat] API     : http://127.0.0.1:9000
echo [start-minio.bat] Console : http://127.0.0.1:9001  (user: %MINIO_ROOT_USER%)
echo [start-minio.bat] Data    : %MINIO_DATA%
echo.
echo Backend side, start the app with these env vars:
echo   set STORAGE_BACKEND=minio
echo   set MINIO_ACCESS_KEY=%MINIO_ROOT_USER%
echo   set MINIO_SECRET_KEY=%MINIO_ROOT_PASSWORD%
echo.

"%MINIO_EXE%" server "%MINIO_DATA%" --address ":9000" --console-address ":9001"
