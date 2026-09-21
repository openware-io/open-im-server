@echo off
setlocal

set "PY_VER=%~1"
if "%PY_VER%"=="" set "PY_VER=3.12.10"

set "ROOT=%~dp0"
set "RM_DIR=%ROOT%release_manager"
set "PY_DIR=%RM_DIR%\python-%PY_VER%-embed-amd64"
set "PY_EXE=%PY_DIR%\python.exe"
set "RM_DIR_F=%RM_DIR:\=/%"
set "PY_DIR_F=%PY_DIR:\=/%"

if not exist "%PY_EXE%" (
 echo [ERROR] Embedded Python not found: "%PY_EXE%"
 echo     Please extract python-%PY_VER%-embed-amd64 under: "%RM_DIR%"
 exit /b 1
)

if not exist "%RM_DIR%\runtime" (
 mkdir "%RM_DIR%\runtime" >nul 2>nul
)

powershell -NoProfile -ExecutionPolicy Bypass -Command ^
 "$pyDir='%PY_DIR_F%';" ^
 "$pth=Get-ChildItem -Path $pyDir -Filter 'python*._pth' | Select-Object -First 1;" ^
 "if($pth){" ^
 " $c=Get-Content -Path $pth.FullName -Raw;" ^
 " if($c -match '(?m)^\s*#\s*import\s+site\s*$'){" ^
 "  $c=[regex]::Replace($c,'(?m)^\s*#\s*import\s+site\s*$','import site');" ^
 "  Set-Content -Path $pth.FullName -Value $c -Encoding ASCII;" ^
 " } elseif($c -notmatch '(?m)^\s*import\s+site\s*$'){" ^
 "  Add-Content -Path $pth.FullName -Value 'import site' -Encoding ASCII;" ^
 " }" ^
 "}"
if errorlevel 1 exit /b 1

"%PY_EXE%" -c "import PySide6, qdarkstyle, lxml, rich" >nul 2>nul
if errorlevel 1 (
 echo [ERROR] Bundled Python dependencies are incomplete.
 echo     Ensure PySide6, qdarkstyle, lxml, and rich are preinstalled in:
 echo     %PY_DIR%\Lib\site-packages
 exit /b 1
)

pushd "%RM_DIR%" || exit /b 1
"%PY_EXE%" -c "import sys,runpy; sys.path.insert(0,'.'); runpy.run_path('main.py', run_name='__main__')"
set "RC=%ERRORLEVEL%"
popd
if not "%RC%"=="0" exit /b %RC%

endlocal
