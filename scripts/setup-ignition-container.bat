@echo off
REM ============================================================================
REM Ignition DOOM Container Setup Script
REM ============================================================================
REM This script automates the setup of an Ignition container with:
REM - DOOM WAD file installation
REM - Unsigned module support enabled
REM - Proper directory structure and permissions
REM
REM Usage:  scripts\setup-ignition-container.bat <container-name> [-y^|--yes]
REM
REM   If a container with that name already exists it is DELETED and recreated
REM   (all gateway data in it is lost). You are asked to confirm unless -y is given.
REM   Set IGNITION_PORT to publish the gateway on a host port other than 8088.
REM ============================================================================

setlocal enabledelayedexpansion
cd /d "%~dp0.."

REM ── Arguments ────────────────────────────────────────────────────────────────
if "%~1"=="" goto :usage_error
set "CONTAINER_NAME=%~1"
if "%CONTAINER_NAME:~0,1%"=="-" goto :usage_error
set ASSUME_YES=
if /i "%~2"=="-y"    set ASSUME_YES=1
if /i "%~2"=="--yes" set ASSUME_YES=1
if not "%~2"=="" if not defined ASSUME_YES (
    echo ERROR: Unknown option: %~2
    goto :usage_error
)
if not "%~3"=="" (
    echo ERROR: Unexpected argument: %~3
    goto :usage_error
)

REM Configuration
set IGNITION_IMAGE=inductiveautomation/ignition:8.3.1
if not defined IGNITION_PORT set IGNITION_PORT=8088
set CONTAINER_WAD_PATH=/usr/local/bin/ignition/user-lib/doom

echo.
echo ============================================================================
echo  Ignition DOOM Container Setup
echo ============================================================================
echo  Container: %CONTAINER_NAME%
echo  Image:     %IGNITION_IMAGE%
echo  Port:      %IGNITION_PORT%
echo ============================================================================
echo.

REM ── Required asset check ─────────────────────────────────────────────────────
REM DOOM1.WAD must be present — it's the minimum WAD needed to run.
REM Soundfonts are optional (music falls back to silence without them).
echo Checking required assets...
if not exist "assets\iwads\DOOM1.WAD" (
    echo.
    echo ============================================================================
    echo  ERROR: DOOM1.WAD not found in assets\iwads\
    echo.
    echo  DOOM1.WAD ships with this repository. Run scripts\get-assets.bat to restore it
    echo  from git, or re-clone the repository.
    echo ============================================================================
    echo.
    goto :abort
)
echo      OK
echo.

docker info >nul 2>&1
if errorlevel 1 (
    echo ERROR: Docker is not installed or the Docker daemon is not running.
    goto :abort
)

REM Recreating the container destroys everything in it — confirm first.
docker inspect "%CONTAINER_NAME%" >nul 2>&1
if not errorlevel 1 if not defined ASSUME_YES (
    echo WARNING: Container '%CONTAINER_NAME%' already exists. It will be DELETED and
    echo recreated, and all gateway data in it ^(projects, tags, config^) will be lost.
    set CONFIRM=
    set /p "CONFIRM=Type the container name to continue: "
    if not "!CONFIRM!"=="%CONTAINER_NAME%" (
        echo Aborted. ^(Use -y to skip this prompt.^)
        goto :abort
    )
    echo.
)

REM Step 1: Stop and remove existing container
echo [1/7] Stopping and removing existing container...
docker stop "%CONTAINER_NAME%" >nul 2>&1
if errorlevel 1 (
    echo      Container not running
) else (
    echo      Container stopped
)

docker rm "%CONTAINER_NAME%" >nul 2>&1
if errorlevel 1 (
    echo      Container does not exist
) else (
    echo      Container removed
)

REM Step 2: Create new container
echo.
echo [2/7] Creating new container...
docker run -d --name "%CONTAINER_NAME%" -p %IGNITION_PORT%:8088 %IGNITION_IMAGE% >nul
if errorlevel 1 (
    echo      ERROR: Failed to create container
    goto :error
)
echo      Container created successfully

REM Step 3: Wait for container to be ready
echo.
echo [3/7] Waiting for container to start...
REM (ping instead of timeout: timeout fails when stdin is redirected)
ping -n 6 127.0.0.1 >nul
echo      Container started

REM Step 4: Create DOOM directory structure
echo.
echo [4/7] Creating DOOM directory structure...
docker exec "%CONTAINER_NAME%" bash -c "mkdir -p %CONTAINER_WAD_PATH%"
if errorlevel 1 (
    echo      ERROR: Failed to create directory
    goto :error
)
echo      Directory created: %CONTAINER_WAD_PATH%

REM Step 5: Deploy assets
echo.
echo [5/7] Deploying assets...
echo      Running scripts\deploy-assets.bat to copy WADs, soundfonts, branding, and PWADs...
echo.
call "%~dp0deploy-assets.bat" "%CONTAINER_NAME%"
if errorlevel 1 (
    echo      WARNING: Asset deployment reported errors - check output above.
)

REM Step 6: Enable unsigned modules and set heap memory
echo.
echo [6/7] Enabling unsigned module installation and setting heap memory...
docker exec "%CONTAINER_NAME%" bash -c "echo 'wrapper.java.additional.9=-Dignition.allowunsignedmodules=true' >> /usr/local/bin/ignition/data/ignition.conf"
if errorlevel 1 (
    echo      ERROR: Failed to update configuration
    goto :error
)
docker exec "%CONTAINER_NAME%" bash -c "echo 'wrapper.java.maxmemory=3072' >> /usr/local/bin/ignition/data/ignition.conf"
if errorlevel 1 (
    echo      ERROR: Failed to set heap memory
    goto :error
)

REM Verify configuration
docker exec "%CONTAINER_NAME%" bash -c "grep 'allowunsignedmodules' /usr/local/bin/ignition/data/ignition.conf" >nul 2>&1
if errorlevel 1 (
    echo      ERROR: Configuration verification failed
    goto :error
)
echo      Unsigned modules enabled
docker exec "%CONTAINER_NAME%" bash -c "grep 'wrapper.java.maxmemory' /usr/local/bin/ignition/data/ignition.conf" >nul 2>&1
if errorlevel 1 (
    echo      ERROR: Heap memory configuration verification failed
    goto :error
)
echo      Heap memory set to 3072 MB

REM Step 7: Restart container to apply configuration
echo.
echo [7/7] Restarting container to apply configuration...
docker restart "%CONTAINER_NAME%" >nul 2>&1
if errorlevel 1 (
    echo      ERROR: Failed to restart container
    goto :error
)
echo      Container restarted

REM Wait for restart
ping -n 6 127.0.0.1 >nul

REM Final verification
echo.
echo ============================================================================
echo  Setup complete.
echo ============================================================================
echo  Installed WAD files:
docker exec "%CONTAINER_NAME%" bash -c "cd %CONTAINER_WAD_PATH%/iwads 2>/dev/null && ls -lh | grep -i '\.wad$' || echo '  (none found)'"
echo.
echo  Gateway URL:    http://localhost:%IGNITION_PORT%
echo  Landing page:   http://localhost:%IGNITION_PORT%/system/doom
echo  Health check:   http://localhost:%IGNITION_PORT%/system/doom/health
echo.
echo  To add or refresh assets later (without recreating the container):
echo    scripts\deploy-assets.bat %CONTAINER_NAME%
echo.
echo  Next steps:
echo    1. Commission the gateway (EULA, auth, activation)
echo    2. Install DOOM module via: Config ^> System ^> Modules
echo ============================================================================
echo.
endlocal & exit /b 0

:abort
endlocal & exit /b 1

:usage_error
echo ERROR: Container name is required as the first argument.
echo Usage: scripts\setup-ignition-container.bat ^<container-name^> [-y^|--yes]
endlocal & exit /b 1

:error
echo.
echo ============================================================================
echo  ERROR: Setup failed.
echo ============================================================================
echo  Please check the error messages above and try again.
echo ============================================================================
echo.
endlocal & exit /b 1
