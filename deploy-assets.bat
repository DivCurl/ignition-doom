@echo off
REM ============================================================================
REM Ignition DOOM Asset Deployment Script
REM ============================================================================
REM Copies WAD files, soundfonts, and branding assets to a running Ignition
REM container. Use this after initial container setup (setup-ignition-container)
REM to add or refresh assets without recreating the container.
REM
REM Usage:
REM   deploy-assets.bat <container>              Deploy all asset categories
REM   deploy-assets.bat <container> -w -s        Deploy wads and sounds only
REM   deploy-assets.bat <container> -w --clean   Purge wads, then redeploy them
REM   deploy-assets.bat <container> --clean      Purge and redeploy all categories
REM
REM Arguments:
REM   <container>      Docker container name (required, positional first argument)
REM
REM Selective deploy flags (combine freely):
REM   -w, --wads       Deploy IWAD files from assets\iwads\
REM   -s, --sounds     Deploy SoundFont files from assets\soundfonts\
REM   -b, --branding   Deploy branding assets from assets\branding\
REM   -p, --pwads      Deploy PWAD/mod files from assets\pwads\
REM   -a, --all        Deploy all categories (same as omitting flags entirely)
REM
REM Purge modifier:
REM   --clean          Purge selected categories in the container before deploying
REM                    Applies to whichever categories are being deployed
REM ============================================================================

setlocal enabledelayedexpansion

REM ── Container name (mandatory first argument) ─────────────────────────────
if "%~1"=="" (
    echo.
    echo ERROR: Container name is required as the first argument.
    echo Usage: deploy-assets.bat ^<container-name^> [-w^|-s^|-b^|-p^|-a] [--clean]
    echo.
    exit /b 1
)
set CONTAINER_NAME=%~1
shift
set CONTAINER_DOOM_PATH=/usr/local/bin/ignition/user-lib/doom
set CONTAINER_IWAD_PATH=%CONTAINER_DOOM_PATH%/iwads

REM ── Parse arguments ───────────────────────────────────────────────────────
set CLEAN=
set DEPLOY_WADS=
set DEPLOY_SOUNDS=
set DEPLOY_BRANDING=
set DEPLOY_PWADS=
set ANY_DEPLOY_FLAG=

:parse_args
if "%~1"=="-w"         ( set DEPLOY_WADS=1     & set ANY_DEPLOY_FLAG=1 & shift & goto :parse_args )
if "%~1"=="--wads"     ( set DEPLOY_WADS=1     & set ANY_DEPLOY_FLAG=1 & shift & goto :parse_args )
if "%~1"=="-s"         ( set DEPLOY_SOUNDS=1   & set ANY_DEPLOY_FLAG=1 & shift & goto :parse_args )
if "%~1"=="--sounds"   ( set DEPLOY_SOUNDS=1   & set ANY_DEPLOY_FLAG=1 & shift & goto :parse_args )
if "%~1"=="-b"         ( set DEPLOY_BRANDING=1 & set ANY_DEPLOY_FLAG=1 & shift & goto :parse_args )
if "%~1"=="--branding" ( set DEPLOY_BRANDING=1 & set ANY_DEPLOY_FLAG=1 & shift & goto :parse_args )
if "%~1"=="-p"         ( set DEPLOY_PWADS=1    & set ANY_DEPLOY_FLAG=1 & shift & goto :parse_args )
if "%~1"=="--pwads"    ( set DEPLOY_PWADS=1    & set ANY_DEPLOY_FLAG=1 & shift & goto :parse_args )
if "%~1"=="-a"         ( set DEPLOY_WADS=1 & set DEPLOY_SOUNDS=1 & set DEPLOY_BRANDING=1 & set DEPLOY_PWADS=1 & set ANY_DEPLOY_FLAG=1 & shift & goto :parse_args )
if "%~1"=="--all"      ( set DEPLOY_WADS=1 & set DEPLOY_SOUNDS=1 & set DEPLOY_BRANDING=1 & set DEPLOY_PWADS=1 & set ANY_DEPLOY_FLAG=1 & shift & goto :parse_args )
if "%~1"=="--clean"    ( set CLEAN=1 & shift & goto :parse_args )

REM Default: deploy all categories when no explicit deploy flags are given
if not defined ANY_DEPLOY_FLAG (
    set DEPLOY_WADS=1
    set DEPLOY_SOUNDS=1
    set DEPLOY_BRANDING=1
    set DEPLOY_PWADS=1
)

echo.
echo ============================================================================
echo  Ignition DOOM Asset Deployment
echo ============================================================================
echo  Container: %CONTAINER_NAME%
if defined ANY_DEPLOY_FLAG (
    set DEPLOY_TARGETS=
    if defined DEPLOY_WADS     set DEPLOY_TARGETS=!DEPLOY_TARGETS! wads
    if defined DEPLOY_SOUNDS   set DEPLOY_TARGETS=!DEPLOY_TARGETS! sounds
    if defined DEPLOY_BRANDING set DEPLOY_TARGETS=!DEPLOY_TARGETS! branding
    if defined DEPLOY_PWADS    set DEPLOY_TARGETS=!DEPLOY_TARGETS! pwads
    echo  Deploy:!DEPLOY_TARGETS!
)
if defined CLEAN (
    set CLEAN_TARGETS=
    if defined DEPLOY_WADS     set CLEAN_TARGETS=!CLEAN_TARGETS! wads
    if defined DEPLOY_SOUNDS   set CLEAN_TARGETS=!CLEAN_TARGETS! sounds
    if defined DEPLOY_BRANDING set CLEAN_TARGETS=!CLEAN_TARGETS! branding
    if defined DEPLOY_PWADS    set CLEAN_TARGETS=!CLEAN_TARGETS! pwads
    echo  Clean:!CLEAN_TARGETS!
)
echo ============================================================================
echo.

REM ── Verify container is running ───────────────────────────────────────────
echo Checking container status...
for /f %%S in ('docker inspect -f "{{.State.Running}}" %CONTAINER_NAME% 2^>nul') do set RUNNING=%%S
if not "%RUNNING%"=="true" (
    echo.
    echo ============================================================================
    echo  ERROR: Container '%CONTAINER_NAME%' is not running.
    echo.
    echo  Run setup-ignition-container.bat first to create and configure the
    echo  container, then re-run this script to deploy assets.
    echo ============================================================================
    echo.
    exit /b 1
)
echo      Container is running.
echo.

REM ── [wads] Deploy IWAD files ──────────────────────────────────────────────
if not defined DEPLOY_WADS goto :skip_wads
echo [wads] Deploying DOOM IWAD files...
echo        Searching assets\iwads\ for WAD files...
docker exec %CONTAINER_NAME% bash -c "mkdir -p %CONTAINER_IWAD_PATH%" >nul 2>&1
if defined CLEAN (
    echo        Purging existing WAD files from container...
    docker exec -u root %CONTAINER_NAME% bash -c "rm -f %CONTAINER_IWAD_PATH%/*.WAD %CONTAINER_IWAD_PATH%/*.wad" >nul 2>&1
)

set WAD_COUNT=0
for %%W in (DOOM1.WAD DOOM.WAD DOOM2.WAD TNT.WAD PLUTONIA.WAD) do (
    if exist "assets\iwads\%%W" (
        docker cp "assets\iwads\%%W" %CONTAINER_NAME%:/tmp/%%W >nul 2>&1
        if not errorlevel 1 (
            docker exec -u root %CONTAINER_NAME% bash -c "cp /tmp/%%W %CONTAINER_IWAD_PATH%/ && chown ignition:ignition %CONTAINER_IWAD_PATH%/%%W" >nul 2>&1
            if not errorlevel 1 (
                echo        Deployed: %%W
                set /a WAD_COUNT+=1
            ) else (
                echo        WARNING: Failed to install %%W in container
            )
        ) else (
            echo        WARNING: Failed to copy %%W to container
        )
    ) else (
        echo        Skipped ^(not found^): %%W
    )
)

if %WAD_COUNT% EQU 0 (
    echo        WARNING: No WAD files found in assets\iwads\
) else (
    echo        %WAD_COUNT% WAD file^(s^) deployed.
)
echo.
:skip_wads

REM ── [sounds] Deploy soundfonts ────────────────────────────────────────────
if not defined DEPLOY_SOUNDS goto :skip_soundfonts
echo [sounds] Deploying SoundFont instrument files...
if not exist "assets\soundfonts" (
    echo         INFO: assets\soundfonts\ not found - skipping.
    goto :skip_soundfonts
)
if defined CLEAN (
    echo         Purging existing soundfonts from container...
    docker exec -u root %CONTAINER_NAME% bash -c "rm -rf %CONTAINER_DOOM_PATH%/soundfonts && mkdir -p %CONTAINER_DOOM_PATH%/soundfonts && chown ignition:ignition %CONTAINER_DOOM_PATH%/soundfonts" >nul 2>&1
) else (
    docker exec %CONTAINER_NAME% bash -c "mkdir -p %CONTAINER_DOOM_PATH%/soundfonts" >nul 2>&1
)
docker cp assets\soundfonts\. %CONTAINER_NAME%:/tmp/soundfonts/ >nul 2>&1
if errorlevel 1 (
    echo         WARNING: Failed to copy soundfonts to container.
    goto :skip_soundfonts
)
docker exec -u root %CONTAINER_NAME% bash -c "cp -r /tmp/soundfonts/. %CONTAINER_DOOM_PATH%/soundfonts/ && chown -R ignition:ignition %CONTAINER_DOOM_PATH%/soundfonts" >nul 2>&1
if errorlevel 1 (
    echo         WARNING: SoundFont installation failed.
) else (
    for %%F in (assets\soundfonts\*) do echo         Deployed: %%~nxF
)
echo.
:skip_soundfonts

REM ── [branding] Deploy branding assets ────────────────────────────────────
if not defined DEPLOY_BRANDING goto :skip_branding
echo [branding] Deploying branding assets...
if not exist "assets\branding" (
    echo           INFO: assets\branding\ not found - skipping.
    goto :skip_branding
)
if defined CLEAN (
    echo           Purging existing branding assets from container...
    docker exec -u root %CONTAINER_NAME% bash -c "rm -rf %CONTAINER_DOOM_PATH%/branding && mkdir -p %CONTAINER_DOOM_PATH%/branding && chown ignition:ignition %CONTAINER_DOOM_PATH%/branding" >nul 2>&1
) else (
    docker exec %CONTAINER_NAME% bash -c "mkdir -p %CONTAINER_DOOM_PATH%/branding" >nul 2>&1
)
docker cp assets\branding\. %CONTAINER_NAME%:/tmp/branding/ >nul 2>&1
if errorlevel 1 (
    echo           WARNING: Failed to copy branding assets to container.
    goto :skip_branding
)
docker exec -u root %CONTAINER_NAME% bash -c "cp -r /tmp/branding/. %CONTAINER_DOOM_PATH%/branding/ && chown -R ignition:ignition %CONTAINER_DOOM_PATH%/branding" >nul 2>&1
if errorlevel 1 (
    echo           WARNING: Branding asset installation failed.
) else (
    for %%F in (assets\branding\*) do echo           Deployed: %%~nxF
)
echo.
:skip_branding

REM ── [pwads] Deploy PWAD / mod files ──────────────────────────────────────
if not defined DEPLOY_PWADS goto :skip_pwads
echo [pwads] Deploying PWAD / mod files...
if not exist "assets\pwads" (
    echo         INFO: assets\pwads\ not found - skipping.
    goto :skip_pwads
)
if defined CLEAN (
    echo         Purging existing PWAD files from container...
    docker exec -u root %CONTAINER_NAME% bash -c "rm -rf %CONTAINER_DOOM_PATH%/pwads && mkdir -p %CONTAINER_DOOM_PATH%/pwads && chown ignition:ignition %CONTAINER_DOOM_PATH%/pwads" >nul 2>&1
) else (
    docker exec %CONTAINER_NAME% bash -c "mkdir -p %CONTAINER_DOOM_PATH%/pwads" >nul 2>&1
)
set PWAD_COUNT=0
for %%P in (assets\pwads\*.wad assets\pwads\*.WAD assets\pwads\*.pk3 assets\pwads\*.pk7) do (
    docker cp "%%P" %CONTAINER_NAME%:/tmp/%%~nxP >nul 2>&1
    if not errorlevel 1 (
        docker exec -u root %CONTAINER_NAME% bash -c "cp /tmp/%%~nxP %CONTAINER_DOOM_PATH%/pwads/ && chown ignition:ignition %CONTAINER_DOOM_PATH%/pwads/%%~nxP" >nul 2>&1
        if not errorlevel 1 (
            echo         Deployed: %%~nxP
            set /a PWAD_COUNT+=1
        ) else (
            echo         WARNING: Failed to install %%~nxP in container
        )
    ) else (
        echo         WARNING: Failed to copy %%~nxP to container
    )
)
if %PWAD_COUNT% EQU 0 (
    echo         INFO: No PWAD/mod files found in assets\pwads\ - skipping.
) else (
    echo         %PWAD_COUNT% PWAD/mod file^(s^) deployed.
)
echo.
:skip_pwads

REM ── Summary ───────────────────────────────────────────────────────────────
echo ============================================================================
echo  Deployment Complete!
echo ============================================================================
if defined DEPLOY_WADS (
    echo  WAD files in container:
    docker exec %CONTAINER_NAME% bash -c "ls %CONTAINER_IWAD_PATH%/*.WAD %CONTAINER_IWAD_PATH%/*.wad 2>/dev/null | xargs -I{} basename {} || echo '  (none)'"
    echo.
)
echo  Landing page:  http://localhost:8088/system/doom
echo  Health check:  http://localhost:8088/system/doom/health
echo ============================================================================
echo.

endlocal
exit /b 0
