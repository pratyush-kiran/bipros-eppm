@echo off
REM Windows counterpart of psql-wrapper.sh.
REM Forwards `psql ...` to `docker exec -i bipros-postgres psql ...`,
REM dropping -h/-p args (in-container psql uses the local socket).
setlocal enabledelayedexpansion

set "CONTAINER=bipros-postgres"
if not "%BIPROS_PG_CONTAINER%"=="" set "CONTAINER=%BIPROS_PG_CONTAINER%"

set "ARGS="
:loop
if "%~1"=="" goto done
if /I "%~1"=="-h" ( shift & shift & goto loop )
if /I "%~1"=="-p" ( shift & shift & goto loop )
set "ARGS=%ARGS% %1"
shift
goto loop
:done

if "%PGPASSWORD%"=="" (
  docker exec -i %CONTAINER% psql%ARGS%
) else (
  docker exec -i -e PGPASSWORD=%PGPASSWORD% %CONTAINER% psql%ARGS%
)
endlocal
