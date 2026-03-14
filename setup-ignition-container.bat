@echo off
REM ============================================================================
REM Ignition DOOM Container Setup Script
REM ============================================================================
REM This script automates the setup of an Ignition container with:
REM - DOOM WAD file installation
REM - Unsigned module support enabled
REM - Proper directory structure and permissions
REM ============================================================================

setlocal enabledelayedexpansion

REM ── Container name (mandatory first argument) ────────────────────────────────
if "%~1"=="" (
    echo.
    echo ERROR: Container name is required as the first argument.
    echo Usage: setup-ignition-container.bat ^<container-name^>
    echo.
    exit /b 1
)
set CONTAINER_NAME=%~1

REM Configuration
set IGNITION_IMAGE=inductiveautomation/ignition:8.3.1
set IGNITION_PORT=8088
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
if not exist "assets\iwads\DOOM1.WAD" if not exist "assets\iwads\doom1.wad" (
    echo.
    echo ============================================================================
    echo  ERROR: DOOM1.WAD not found in assets\iwads\
    echo.
    echo  Run get-assets.bat to download it automatically, or place DOOM1.WAD
    echo  manually in assets\iwads\  ^(id Software shareware, freely redistributable^).
    echo ============================================================================
    echo.
    exit /b 1
)
echo      OK
echo.

REM Step 1: Stop and remove existing container
echo [1/7] Stopping and removing existing container...
docker stop %CONTAINER_NAME% 2>nul
if errorlevel 1 (
    echo      Container not running
) else (
    echo      Container stopped
)

docker rm %CONTAINER_NAME% 2>nul
if errorlevel 1 (
    echo      Container does not exist
) else (
    echo      Container removed
)

REM Step 2: Create new container
echo.
echo [2/7] Creating new container...
docker run -d --name %CONTAINER_NAME% -p %IGNITION_PORT%:8088 %IGNITION_IMAGE%
if errorlevel 1 (
    echo      ERROR: Failed to create container
    goto :error
)
echo      Container created successfully

REM Step 3: Wait for container to be ready
echo.
echo [3/7] Waiting for container to start...
timeout /t 5 /nobreak >nul
echo      Container started

REM Step 4: Create DOOM directory structure
echo.
echo [4/7] Creating DOOM directory structure...
docker exec %CONTAINER_NAME% bash -c "mkdir -p %CONTAINER_WAD_PATH%"
if errorlevel 1 (
    echo      ERROR: Failed to create directory
    goto :error
)
echo      Directory created: %CONTAINER_WAD_PATH%

REM Step 5: Create asset directories and deploy assets
echo.
echo [5/7] Creating asset directories and deploying assets...
docker exec %CONTAINER_NAME% bash -c "mkdir -p %CONTAINER_WAD_PATH%/soundfonts %CONTAINER_WAD_PATH%/branding"
if errorlevel 1 (
    echo      ERROR: Failed to create asset directories
    goto :error
)
echo      Directories created.
echo      Running deploy-assets.bat to copy WADs, soundfonts, and branding...
echo.
call "%~dp0deploy-assets.bat" %CONTAINER_NAME%
if errorlevel 1 (
    echo      WARNING: Asset deployment reported errors - check output above.
)

REM Step 6: Enable unsigned modules and set heap memory
echo.
echo [6/7] Enabling unsigned module installation and setting heap memory...
docker exec %CONTAINER_NAME% bash -c "echo 'wrapper.java.additional.9=-Dignition.allowunsignedmodules=true' >> /usr/local/bin/ignition/data/ignition.conf"
if errorlevel 1 (
    echo      ERROR: Failed to update configuration
    goto :error
)
docker exec %CONTAINER_NAME% bash -c "echo 'wrapper.java.maxmemory=3072' >> /usr/local/bin/ignition/data/ignition.conf"
if errorlevel 1 (
    echo      ERROR: Failed to set heap memory
    goto :error
)

REM Verify configuration
docker exec %CONTAINER_NAME% bash -c "grep 'allowunsignedmodules' /usr/local/bin/ignition/data/ignition.conf" >nul 2>&1
if errorlevel 1 (
    echo      ERROR: Configuration verification failed
    goto :error
)
echo      Unsigned modules enabled
docker exec %CONTAINER_NAME% bash -c "grep 'wrapper.java.maxmemory' /usr/local/bin/ignition/data/ignition.conf" >nul 2>&1
if errorlevel 1 (
    echo      ERROR: Heap memory configuration verification failed
    goto :error
)
echo      Heap memory set to 3072 MB

REM Step 7: Restart container to apply configuration
echo.
echo [7/7] Restarting container to apply configuration...
docker restart %CONTAINER_NAME% >nul 2>&1
if errorlevel 1 (
    echo      ERROR: Failed to restart container
    goto :error
)
echo      Container restarted

REM Wait for restart
timeout /t 5 /nobreak >nul

REM Final verification
echo.
echo ============================================================================
echo  Setup Complete!
echo ============================================================================
echo  Installed WAD files:
docker exec %CONTAINER_NAME% bash -c "ls -lh %CONTAINER_WAD_PATH%/iwads/*.WAD %CONTAINER_WAD_PATH%/iwads/*.wad 2>/dev/null || echo '  (none found)'"
echo.
echo  Gateway URL:    http://localhost:%IGNITION_PORT%
echo  Landing page:   http://localhost:%IGNITION_PORT%/system/doom
echo  Health check:   http://localhost:%IGNITION_PORT%/system/doom/health
echo.
echo  To add or refresh assets later (without recreating the container):
echo    deploy-assets.bat %CONTAINER_NAME%
echo.
echo  Next steps:
echo    1. Commission the gateway (EULA, auth, activation)
echo    2. Install DOOM module via: Config ^> System ^> Modules
echo ============================================================================
echo.
goto :end

:error
echo.
echo ============================================================================
echo  ERROR: Setup failed!
echo ============================================================================
echo  Please check the error messages above and try again.
echo ============================================================================
echo.
exit /b 1

:end
endlocal
exit /b 0
