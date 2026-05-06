@echo off
REM
REM Manual / fallback service registration for PoS Agent.
REM
REM The MSI's WiX overrides should register the service automatically. This
REM script is here for two cases:
REM   1) The WiX overrides file mismatched a future jpackage template change
REM      and the service didn't get registered during install.
REM   2) Local development / testing of WinSW config without rebuilding the MSI.
REM
REM Run as Administrator. Expected to live at:
REM   C:\Program Files\PoS Agent\app\register-service.bat
REM and operate on its sibling PoSAgent.exe (the renamed WinSW binary) and
REM PoSAgent.xml.
REM

setlocal
set "APP_DIR=%~dp0"
set "WINSW=%APP_DIR%PoSAgent.exe"
set "XML=%APP_DIR%PoSAgent.xml"

if not exist "%WINSW%" (
  echo ERROR: %WINSW% not found.
  echo Run the PoS Agent installer first, or copy winsw.exe to that path.
  exit /b 1
)
if not exist "%XML%" (
  echo ERROR: %XML% not found.
  exit /b 1
)

REM Confirm we're elevated. WinSW returns ACCESS_DENIED otherwise; we'd
REM rather give a clear message.
net session >nul 2>&1
if errorlevel 1 (
  echo This script must be run as Administrator.
  echo Right-click ^-^> "Run as administrator".
  exit /b 1
)

echo Installing PoS Agent service...
"%WINSW%" install
if errorlevel 1 (
  echo Service install failed.
  exit /b %errorlevel%
)

echo Starting PoS Agent service...
"%WINSW%" start
if errorlevel 1 (
  echo Service start failed. Check %ProgramData%\PoS Agent\logs\.
  exit /b %errorlevel%
)

echo.
echo PoS Agent service is installed and running.
echo Manage it from services.msc, or with:
echo   sc start PoSAgent
echo   sc stop PoSAgent
echo   sc query PoSAgent
endlocal
