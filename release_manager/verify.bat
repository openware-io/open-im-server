@echo off
setlocal

set "PY_VER=%~1"
if "%PY_VER%"=="" set "PY_VER=3.12.10"

set "RM_DIR=%~dp0"
if "%RM_DIR:~-1%"=="\" set "RM_DIR=%RM_DIR:~0,-1%"
set "ROOT=%RM_DIR%\.."

powershell -NoProfile -ExecutionPolicy Bypass -File "%ROOT%\scripts\validate\validate-text-encoding.ps1" -Strict
if errorlevel 1 exit /b 1

if not exist "%ROOT%\scripts\validate\validate-java-logging-conventions.ps1" (
 echo [ERROR] Missing Java logging validation script.
 exit /b 1
)

if not exist "%ROOT%\scripts\validate\validate-flyway-module-boundaries.ps1" (
 echo [ERROR] Missing Flyway module boundary validation script.
 exit /b 1
)

powershell -NoProfile -ExecutionPolicy Bypass -File "%ROOT%\scripts\validate\validate-java-comment-quality.ps1"
if errorlevel 1 exit /b 1

powershell -NoProfile -ExecutionPolicy Bypass -File "%ROOT%\scripts\validate\validate-java-deprecated-api-usage.ps1"
if errorlevel 1 exit /b 1

powershell -NoProfile -ExecutionPolicy Bypass -File "%ROOT%\scripts\validate\validate-powershell-utf8-safety.ps1"
if errorlevel 1 exit /b 1

powershell -NoProfile -ExecutionPolicy Bypass -File "%ROOT%\scripts\validate\validate-java-logging-conventions.ps1"
if errorlevel 1 exit /b 1

powershell -NoProfile -ExecutionPolicy Bypass -File "%ROOT%\scripts\validate\validate-java-exception-logging.ps1"
if errorlevel 1 exit /b 1

powershell -NoProfile -ExecutionPolicy Bypass -File "%ROOT%\scripts\validate\validate-flyway-module-boundaries.ps1"
if errorlevel 1 exit /b 1

powershell -NoProfile -ExecutionPolicy Bypass -File "%ROOT%\scripts\validate\validate-security-and-flyway-configuration.ps1"
if errorlevel 1 exit /b 1

powershell -NoProfile -ExecutionPolicy Bypass -File "%ROOT%\scripts\validate\validate-secret-exposure.ps1" -ReportOnly
if errorlevel 1 exit /b 1

powershell -NoProfile -ExecutionPolicy Bypass -File "%ROOT%\scripts\validate\validate-service-api-boundaries.ps1"
if errorlevel 1 exit /b 1

powershell -NoProfile -ExecutionPolicy Bypass -File "%ROOT%\scripts\validate\validate-service-dependency-graph.ps1"
if errorlevel 1 exit /b 1

powershell -NoProfile -ExecutionPolicy Bypass -File "%ROOT%\scripts\validate\validate-persistence-dependency-boundaries.ps1"
if errorlevel 1 exit /b 1

powershell -NoProfile -ExecutionPolicy Bypass -File "%ROOT%\scripts\validate\validate-maven-version-ownership.ps1"
if errorlevel 1 exit /b 1

powershell -NoProfile -ExecutionPolicy Bypass -File "%ROOT%\scripts\validate\validate-rocketmq-contracts.ps1"
if errorlevel 1 exit /b 1

pushd "%ROOT%" || exit /b 1
call "%ROOT%\mvnw.cmd" -B -ntp clean verify
if errorlevel 1 (
 popd
 exit /b 1
)
popd

set "PY_EXE=%RM_DIR%\python-%PY_VER%-embed-amd64\python.exe"
if not exist "%PY_EXE%" set "PY_EXE=%RM_DIR%\runtime\python\python.exe"
if not exist "%PY_EXE%" (
 echo [ERROR] Embedded Python not found.
 exit /b 1
)

pushd "%RM_DIR%" || exit /b 1
"%PY_EXE%" ".\smoke_test.py"
set "RC=%ERRORLEVEL%"
popd
if not "%RC%"=="0" exit /b %RC%

endlocal
