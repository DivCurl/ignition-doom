@echo off
REM ============================================================================
REM Turn the Ignition gateway's "allow unsigned modules" setting on or off in an
REM existing container, or show its current state.
REM
REM Usage:  scripts\set-unsigned-modules.bat <container-name> on^|off^|status [--no-restart]
REM
REM   on / off   edit ignition.conf, then restart the container so it takes effect
REM              (--no-restart skips the restart; the change applies on next start)
REM   status     print the current setting
REM
REM Signed modules (see ignition-module\signing\) install with this setting off.
REM ============================================================================

setlocal
cd /d "%~dp0.."

set "CONTAINER_NAME=%~1"
set "ACTION=%~2"
set "OPT=%~3"
if "%CONTAINER_NAME%"=="" goto :usage_error
if "%CONTAINER_NAME:~0,1%"=="-" goto :usage_error
if /i not "%ACTION%"=="on" if /i not "%ACTION%"=="off" if /i not "%ACTION%"=="status" goto :usage_error
if not "%OPT%"=="" if /i not "%OPT%"=="--no-restart" goto :usage_error

set RUNNING=
for /f %%S in ('docker inspect -f "{{.State.Running}}" "%CONTAINER_NAME%" 2^>nul') do set RUNNING=%%S
if not "%RUNNING%"=="true" goto :not_running

REM The ignition.conf logic lives in one shared script, piped into the container.
set RESULT=
for /f "delims=" %%R in ('docker exec -i "%CONTAINER_NAME%" bash -s -- %ACTION% ^< scripts\lib\unsigned-modules.sh') do set "RESULT=%%R"
if "%RESULT%"=="" goto :update_failed
echo Unsigned modules: %RESULT%
if /i "%ACTION%"=="status" goto :done
if "%RESULT:~0,7%"=="already" goto :done
if /i "%OPT%"=="--no-restart" (
    echo Restart the container for the change to take effect.
    goto :done
)
echo Restarting container to apply...
docker restart "%CONTAINER_NAME%" >nul
if errorlevel 1 goto :restart_failed
echo Done.

:done
endlocal & exit /b 0

:usage_error
echo Usage: scripts\set-unsigned-modules.bat ^<container-name^> on^|off^|status [--no-restart]
endlocal & exit /b 1

:not_running
echo ERROR: Container '%CONTAINER_NAME%' is not running.
endlocal & exit /b 1

:update_failed
echo ERROR: Failed to update ignition.conf.
endlocal & exit /b 1

:restart_failed
echo ERROR: Failed to restart container.
endlocal & exit /b 1
