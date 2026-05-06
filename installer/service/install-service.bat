@echo off
REM
REM PoS Agent service installer.
REM
REM Run as Administrator after extracting the posagent-service-vX.Y.Z.zip.
REM Verifies prerequisites, then registers + starts the service via WinSW.
REM
REM After this script succeeds:
REM   services.msc shows "PoS Agent" as Running, Automatic (Delayed Start)
REM   sc start PoSAgent / sc stop PoSAgent / sc query PoSAgent
REM   Browser at http://localhost:8080 hits the running service
REM
REM Updates: the launcher's update4j check fires on every service start
REM (and hourly while running). It SHA-256 compares local files against
REM the latest config.xml on GitHub Releases and downloads only what
REM changed. The zip you ran this from is only needed for the FIRST
REM install; subsequent updates land in %ProgramData%\PoS Agent\.
REM

setlocal
set "BASE_DIR=%~dp0"
REM Strip the trailing backslash from BASE_DIR for cleaner messages.
if "%BASE_DIR:~-1%"=="\" set "BASE_DIR=%BASE_DIR:~0,-1%"
set "WINSW=%BASE_DIR%\PoSAgent.exe"
set "XML=%BASE_DIR%\PoSAgent.xml"
set "LAUNCHER=%BASE_DIR%\launcher.jar"

echo === PoS Agent service installer ===
echo Install dir: %BASE_DIR%
echo.

REM Admin check. WinSW would fail with ACCESS_DENIED otherwise; we want
REM a clean error message instead.
net session >nul 2>&1
if errorlevel 1 (
  echo ERROR: This script must be run as Administrator.
  echo Right-click this .bat ^-^> "Run as administrator".
  exit /b 1
)

REM Required files in the zip extract.
if not exist "%WINSW%" (
  echo ERROR: %WINSW% not found.
  echo The zip extract is incomplete.
  exit /b 1
)
if not exist "%XML%" (
  echo ERROR: %XML% not found.
  exit /b 1
)
if not exist "%LAUNCHER%" (
  echo ERROR: %LAUNCHER% not found.
  exit /b 1
)

REM Java check. The service runs as LocalSystem, which inherits ONLY
REM machine-wide environment variables. A user-only JAVA_HOME won't be
REM visible to the service. Verify a system-level JAVA_HOME is set and
REM points at a valid JDK before registering the service.
if "%JAVA_HOME%"=="" (
  echo ERROR: JAVA_HOME is not set.
  echo Install Java 21+ and set JAVA_HOME at the SYSTEM level
  echo ^(System Properties ^-^> Environment Variables ^-^> System variables^),
  echo not at the User level. The service runs as LocalSystem and only
  echo sees machine-wide variables.
  exit /b 1
)
if not exist "%JAVA_HOME%\bin\java.exe" (
  echo ERROR: %JAVA_HOME%\bin\java.exe not found.
  echo JAVA_HOME=%JAVA_HOME% but no java.exe under it. Check the path.
  exit /b 1
)

echo Found Java at %JAVA_HOME%\bin\java.exe
echo.

REM If the service already exists from a previous install, stop and
REM remove it so we can re-register cleanly. WinSW returns non-zero
REM on "service does not exist" which is fine here.
sc query PoSAgent >nul 2>&1
if not errorlevel 1 (
  echo Existing PoS Agent service detected. Stopping and removing it...
  "%WINSW%" stop 2>nul
  "%WINSW%" uninstall
  if errorlevel 1 (
    echo WARN: existing service uninstall returned non-zero. Continuing.
  )
)

echo Registering PoS Agent service...
"%WINSW%" install
if errorlevel 1 (
  echo ERROR: WinSW install failed with errorlevel %errorlevel%.
  exit /b %errorlevel%
)

echo Starting PoS Agent service...
"%WINSW%" start
if errorlevel 1 (
  echo ERROR: WinSW start failed with errorlevel %errorlevel%.
  echo Check %ProgramData%\PoS Agent\logs\ for diagnostics.
  exit /b %errorlevel%
)

echo.
echo === SUCCESS ===
echo PoS Agent is installed and running.
echo Open http://localhost:8080 in a browser to use it.
echo Manage the service from services.msc, or with:
echo   sc query PoSAgent
echo   sc stop PoSAgent
echo   sc start PoSAgent
echo.
echo State and logs:        %ProgramData%\PoS Agent\
echo Updates auto-check:    every hour, applied on next restart
endlocal
