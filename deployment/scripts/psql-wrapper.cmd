@echo off
REM Debug-instrumented version - logs to a temp file what it received.
setlocal enabledelayedexpansion
set "DBGF=%TEMP%\psql-wrapper-debug.log"
echo --- invocation %TIME% --- > "%DBGF%"
echo %%0 = %0 >> "%DBGF%"
echo %%* = %* >> "%DBGF%"
echo dp0 = %~dp0 >> "%DBGF%"
set PSQL_ARGC=0
:loop
if "%~1"=="" goto done
set /a PSQL_ARGC+=1
set "PSQL_ARG_!PSQL_ARGC!=%~1"
echo arg[!PSQL_ARGC!] = [%~1] >> "%DBGF%"
shift
goto loop
:done
echo PSQL_ARGC=%PSQL_ARGC% >> "%DBGF%"
echo calling: powershell.exe -File "%~dp0psql-wrapper.ps1" >> "%DBGF%"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0psql-wrapper.ps1"
set RC=%ERRORLEVEL%
echo RC=%RC% >> "%DBGF%"
exit /b %RC%
