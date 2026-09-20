@echo off
rem
rem mvnw.cmd —— Maven 启动包装脚本（Windows cmd / PowerShell 用）
rem
rem 与同目录的 mvnw（Unix 版）等价，只是改用批处理语法。
rem 同样绕过 Maven 自带的 mvn.cmd，直接用 java 启动 classworlds Launcher，
rem 避免依赖 shell 环境变量解析。详见 mvnw 文件头注释。
rem
rem 用法：
rem   mvnw.cmd clean package
rem   mvnw.cmd spring-boot:run
rem

setlocal enabledelayedexpansion

if "%MAVEN_HOME%"=="" set "MAVEN_HOME=D:\Maven\apache-maven-3.8.9-bin\apache-maven-3.8.9"

if not exist "%MAVEN_HOME%\boot" (
    echo [mvnw] MAVEN_HOME 无效：%MAVEN_HOME%
    echo [mvnw] 请设置环境变量 MAVEN_HOME 指向 Maven 根目录后重试。
    exit /b 1
)

set "CLASSWORLDS="
for %%J in ("%MAVEN_HOME%\boot\plexus-classworlds-*.jar") do set "CLASSWORLDS=%%~fJ"

if "%CLASSWORLDS%"=="" (
    echo [mvnw] 在 %MAVEN_HOME%\boot 下找不到 plexus-classworlds-*.jar
    exit /b 1
)

java -classpath "%CLASSWORLDS%" ^
     "-Dclassworlds.conf=%MAVEN_HOME%\bin\m2.conf" ^
     "-Dmaven.home=%MAVEN_HOME%" ^
     "-Dmaven.multiModuleProjectDirectory=%CD%" ^
     "-Dlibrary.jansi.path=%MAVEN_HOME%\lib\jansi-native" ^
     org.codehaus.plexus.classworlds.launcher.Launcher %*

exit /b %ERRORLEVEL%
