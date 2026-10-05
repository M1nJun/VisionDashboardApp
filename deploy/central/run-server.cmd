@echo off
REM ===========================================================================
REM  Vision Dashboard - central server
REM
REM  EDIT THE SETTINGS BELOW, then run this file (or Install-Service.ps1 to have
REM  Windows start it at boot).
REM ===========================================================================

REM ---- database -------------------------------------------------------------
set DB_HOST=127.0.0.1
set DB_PORT=3306
set DB_NAME=visiondash
set DB_USER=vision_app
set DB_PASSWORD=CHANGE_ME

REM ---- contracts ------------------------------------------------------------
REM  The jar carries the example catalog committed to the repository. This plant's
REM  own catalog is the one Package-Central.ps1 put in contracts\ beside this file,
REM  and CATALOG_DIR is what makes the server read that one instead. Without it the
REM  server would score real inspectors against example vision keys and show nothing.
set CATALOG_DIR=%~dp0contracts

REM ---- ports ---------------------------------------------------------------
REM  8080 must be reachable from operator browsers AND from every inspection PC
REM  (the agents POST their events to it). 6002/UDP receives the heartbeats.
set SERVER_PORT=8080
set HEARTBEAT_PORT=6002

REM ---------------------------------------------------------------------------

setlocal enabledelayedexpansion
cd /d "%~dp0"

if not exist "logs" mkdir "logs"

REM Find a Java 21 runtime: JAVA_HOME first, then the usual install locations.
set "JAVA_EXE="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
if not defined JAVA_EXE (
  for /d %%D in ("%ProgramFiles%\Eclipse Adoptium\jdk-21*" "%ProgramFiles%\Eclipse Adoptium\jre-21*" "%ProgramFiles%\Java\jdk-21*" "%ProgramFiles%\Java\jre-21*") do (
    if exist "%%D\bin\java.exe" set "JAVA_EXE=%%D\bin\java.exe"
  )
)
if not defined JAVA_EXE (
  where java.exe >nul 2>&1 && set "JAVA_EXE=java.exe"
)
if not defined JAVA_EXE (
  echo.
  echo   No Java 21 runtime found.
  echo   Install the bundled JRE ^(OpenJDK21U-jre_x64_windows_hotspot.msi^) and run this again.
  echo.
  pause
  exit /b 2
)

echo Starting Vision Dashboard server...
echo   java     : %JAVA_EXE%
echo   database : %DB_USER%@%DB_HOST%:%DB_PORT%/%DB_NAME%
echo   dashboard: http://localhost:%SERVER_PORT%/dashboard/
echo   log      : %~dp0logs\server.log
echo.

"%JAVA_EXE%" -jar "server.jar" ^
  --logging.file.name=logs/server.log ^
  --logging.logback.rollingpolicy.max-file-size=20MB ^
  --logging.logback.rollingpolicy.max-history=14

echo.
echo Server stopped with exit code %ERRORLEVEL%.
if "%1"=="" pause
