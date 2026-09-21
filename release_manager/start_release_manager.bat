@echo off
setlocal

set "PY_VER=%~1"
if "%PY_VER%"=="" set "PY_VER=3.12.10"

call "%~dp0..\start_release_manager.bat" "%PY_VER%"
if errorlevel 1 exit /b 1

endlocal
