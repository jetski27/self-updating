@echo off
REM
REM PoS Agent service uninstaller.
REM
REM Stops and removes the Windows service registration. Does NOT delete
REM %ProgramData%\PoS Agent\ — that holds your downloaded payload, logs,
REM and update4j state. Remove it manually if you want a hard reset:
REM   rmdir /s /q "%ProgramData%\PoS Agent"
REM
REM Run as Administrator from the same directory the install script
REM lived in (or anywhere alongside PoSAgent.exe).
REM

setlocal
set "BASE_DIR=%~dp0"
if "%BASE_DIR:~-1%"=="\" set "BASE_DIR=%BASE_DIR:~0,-1%"
set "WINSW=%BASE_DIR%\PoSAgent.exe"

echo === PoS Agent service uninstaller ===

net session >nul 2>&1
if errorlevel 1 (
  echo ERROR: This script must be run as Administrator.
  exit /b 1
)

if not exist "%WINSW%" (
  echo ERROR: %WINSW% not found next to this script.
  echo If you've already deleted the install dir, use:
  echo   sc stop PoSAgent
  echo   sc delete PoSAgent
  exit /b 1
)

echo Stopping PoS Agent service...
"%WINSW%" stop 2>nul

echo Removing PoS Agent service...
"%WINSW%" uninstall
if errorlevel 1 (
  echo ERROR: WinSW uninstall returned errorlevel %errorlevel%.
  exit /b %errorlevel%
)

echo.
echo PoS Agent service has been removed.
echo State at %ProgramData%\PoS Agent\ was preserved. Delete it manually
echo if you want a clean slate.
endlocal
