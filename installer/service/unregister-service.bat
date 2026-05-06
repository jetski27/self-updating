@echo off
REM
REM Manual / fallback service unregistration for PoS Agent.
REM
REM The MSI's <ServiceControl Stop="both" Remove="uninstall"> handles this
REM during normal uninstall. Use this script if you need to remove the
REM service registration without uninstalling the application (e.g. when
REM iterating on the WinSW XML during development).
REM

setlocal
set "APP_DIR=%~dp0"
set "WINSW=%APP_DIR%PoSAgent.exe"

if not exist "%WINSW%" (
  echo ERROR: %WINSW% not found.
  exit /b 1
)

net session >nul 2>&1
if errorlevel 1 (
  echo This script must be run as Administrator.
  exit /b 1
)

echo Stopping PoS Agent service...
"%WINSW%" stop

echo Uninstalling PoS Agent service...
"%WINSW%" uninstall
if errorlevel 1 (
  echo Service uninstall failed.
  exit /b %errorlevel%
)

echo PoS Agent service has been removed.
endlocal
