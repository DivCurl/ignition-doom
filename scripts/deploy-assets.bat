@echo off
REM ============================================================================
REM Ignition DOOM Asset Deployment Script
REM ============================================================================
REM Copies WAD files, soundfonts, and branding assets to a running Ignition
REM container. Use this after initial container setup (setup-ignition-container)
REM to add or refresh assets without recreating the container.
REM
REM Usage:
REM   scripts\deploy-assets.bat <container>              Deploy all asset categories
REM   scripts\deploy-assets.bat <container> -w -s        Deploy wads and sounds only
REM   scripts\deploy-assets.bat <container> -w --clean   Purge wads, then redeploy them
REM   scripts\deploy-assets.bat <container> --clean      Purge and redeploy all categories
REM
REM Arguments:
REM   <container>      Docker container name (required, positional first argument)
REM
REM Selective deploy flags (combine freely):
REM   -w, --wads       Deploy IWAD files (*.wad) from assets\iwads\
REM   -s, --sounds     Deploy SoundFont files from assets\soundfonts\
REM   -b, --branding   Deploy branding assets from assets\branding\
REM   -p, --pwads      Deploy PWAD/mod files (*.wad, *.pk3, *.pk7) from assets\pwads\
REM   -a, --all        Deploy all categories (same as omitting flags entirely)
REM
REM Purge modifier:
REM   --clean          Purge selected categories in the container before deploying
REM                    Applies to whichever categories are being deployed
REM
REM Exit status is non-zero if any file failed to deploy.
REM ============================================================================

setlocal enabledelayedexpansion
cd /d "%~dp0.."

REM ── Container name (mandatory first argument) ─────────────────────────────
if "%~1"=="" goto :usage_error
set "CONTAINER_NAME=%~1"
if "%CONTAINER_NAME:~0,1%"=="-" goto :usage_error
shift
set CONTAINER_DOOM_PATH=/usr/local/bin/ignition/user-lib/doom

REM ── Parse arguments ───────────────────────────────────────────────────────
set CLEAN=
set DEPLOY_WADS=
set DEPLOY_SOUNDS=
set DEPLOY_BRANDING=
set DEPLOY_PWADS=
set ANY_DEPLOY_FLAG=

:parse_args
if "%~1"=="" goto :args_done
if /i "%~1"=="-w"         ( set "DEPLOY_WADS=1"     & set "ANY_DEPLOY_FLAG=1" & shift & goto :parse_args )
if /i "%~1"=="--wads"     ( set "DEPLOY_WADS=1"     & set "ANY_DEPLOY_FLAG=1" & shift & goto :parse_args )
if /i "%~1"=="-s"         ( set "DEPLOY_SOUNDS=1"   & set "ANY_DEPLOY_FLAG=1" & shift & goto :parse_args )
if /i "%~1"=="--sounds"   ( set "DEPLOY_SOUNDS=1"   & set "ANY_DEPLOY_FLAG=1" & shift & goto :parse_args )
if /i "%~1"=="-b"         ( set "DEPLOY_BRANDING=1" & set "ANY_DEPLOY_FLAG=1" & shift & goto :parse_args )
if /i "%~1"=="--branding" ( set "DEPLOY_BRANDING=1" & set "ANY_DEPLOY_FLAG=1" & shift & goto :parse_args )
if /i "%~1"=="-p"         ( set "DEPLOY_PWADS=1"    & set "ANY_DEPLOY_FLAG=1" & shift & goto :parse_args )
if /i "%~1"=="--pwads"    ( set "DEPLOY_PWADS=1"    & set "ANY_DEPLOY_FLAG=1" & shift & goto :parse_args )
if /i "%~1"=="-a"         ( set "DEPLOY_WADS=1" & set "DEPLOY_SOUNDS=1" & set "DEPLOY_BRANDING=1" & set "DEPLOY_PWADS=1" & set "ANY_DEPLOY_FLAG=1" & shift & goto :parse_args )
if /i "%~1"=="--all"      ( set "DEPLOY_WADS=1" & set "DEPLOY_SOUNDS=1" & set "DEPLOY_BRANDING=1" & set "DEPLOY_PWADS=1" & set "ANY_DEPLOY_FLAG=1" & shift & goto :parse_args )
if /i "%~1"=="--clean"    ( set "CLEAN=1" & shift & goto :parse_args )
echo ERROR: Unknown option: %~1
goto :usage_error
:args_done

REM Default: deploy all categories when no explicit deploy flags are given
if not defined ANY_DEPLOY_FLAG (
    set DEPLOY_WADS=1
    set DEPLOY_SOUNDS=1
    set DEPLOY_BRANDING=1
    set DEPLOY_PWADS=1
)

set TARGETS=
if defined DEPLOY_WADS     set "TARGETS=!TARGETS! wads"
if defined DEPLOY_SOUNDS   set "TARGETS=!TARGETS! sounds"
if defined DEPLOY_BRANDING set "TARGETS=!TARGETS! branding"
if defined DEPLOY_PWADS    set "TARGETS=!TARGETS! pwads"

echo.
echo ============================================================================
echo  Ignition DOOM Asset Deployment
echo ============================================================================
echo  Container: %CONTAINER_NAME%
if defined ANY_DEPLOY_FLAG echo  Deploy:!TARGETS!
if defined CLEAN           echo  Clean:!TARGETS!
echo ============================================================================
echo.

REM ── Verify container is running ───────────────────────────────────────────
echo Checking container status...
set RUNNING=
for /f %%S in ('docker inspect -f "{{.State.Running}}" "%CONTAINER_NAME%" 2^>nul') do set RUNNING=%%S
if not "%RUNNING%"=="true" (
    echo.
    echo ============================================================================
    echo  ERROR: Container '%CONTAINER_NAME%' is not running.
    echo.
    echo  Run scripts\setup-ignition-container.bat first to create and configure the
    echo  container, then re-run this script to deploy assets.
    echo ============================================================================
    echo.
    goto :abort
)
echo      Container is running.
echo.

set FAILED=0

REM ── [wads] Deploy IWAD files ──────────────────────────────────────────────
if not defined DEPLOY_WADS goto :skip_wads
echo [wads] Deploying DOOM IWAD files from assets\iwads\...
call :prepare_dir iwads "       "
set COUNT=0
set ATTEMPTED=0
REM *.wad also matches DOOM1.WAD (Windows wildcards are case-insensitive)
for %%W in (assets\iwads\*.wad) do call :deploy_file iwads "%%W" "       "
if !ATTEMPTED! EQU 0 (
    echo        WARNING: No WAD files found in assets\iwads\
) else if !COUNT! GTR 0 (
    echo        !COUNT! WAD file^(s^) deployed.
)
echo.
:skip_wads

REM ── [sounds] Deploy soundfonts ────────────────────────────────────────────
if not defined DEPLOY_SOUNDS goto :skip_sounds
echo [sounds] Deploying SoundFont instrument files from assets\soundfonts\...
call :prepare_dir soundfonts "         "
call :deploy_dir soundfonts "         "
if !COUNT! EQU 0 echo          INFO: No soundfont files found - run scripts\get-assets.bat to fetch them.
echo.
:skip_sounds

REM ── [branding] Deploy branding assets ────────────────────────────────────
if not defined DEPLOY_BRANDING goto :skip_branding
echo [branding] Deploying branding assets from assets\branding\...
call :prepare_dir branding "           "
call :deploy_dir branding "           "
if !COUNT! EQU 0 echo            INFO: No branding files found - skipping.
echo.
:skip_branding

REM ── [pwads] Deploy PWAD / mod files ──────────────────────────────────────
if not defined DEPLOY_PWADS goto :skip_pwads
echo [pwads] Deploying PWAD / mod files from assets\pwads\...
call :prepare_dir pwads "        "
set COUNT=0
set ATTEMPTED=0
for %%P in (assets\pwads\*.wad assets\pwads\*.pk3 assets\pwads\*.pk7) do call :deploy_file pwads "%%P" "        "
if !ATTEMPTED! EQU 0 (
    echo         INFO: No PWAD/mod files found - skipping.
) else if !COUNT! GTR 0 (
    echo         !COUNT! PWAD/mod file^(s^) deployed.
)
echo.
:skip_pwads

REM ── Summary ───────────────────────────────────────────────────────────────
echo ============================================================================
if "%FAILED%"=="1" (
    echo  Deployment finished with errors - see warnings above.
) else (
    echo  Deployment complete.
)
echo ============================================================================
if defined DEPLOY_WADS (
    echo  WAD files in container:
    docker exec "%CONTAINER_NAME%" bash -c "cd %CONTAINER_DOOM_PATH%/iwads 2>/dev/null && ls | grep -i '\.wad$' | sed 's/^/    /' || echo '    (none)'"
    echo.
)
echo  Landing page:  http://localhost:8088/system/doom
echo  Health check:  http://localhost:8088/system/doom/health
echo ============================================================================
echo.

endlocal & exit /b %FAILED%

REM ══ Subroutines ═══════════════════════════════════════════════════════════

:abort
endlocal & exit /b 1

:usage_error
echo ERROR: Container name is required as the first argument.
echo Usage: scripts\deploy-assets.bat ^<container-name^> [-w^|-s^|-b^|-p^|-a] [--clean]
endlocal & exit /b 1

REM :prepare_dir <category> "<indent>"
REM Purge the container directory with --clean, then ensure it exists and is
REM owned by the gateway's 'ignition' user.
:prepare_dir
if defined CLEAN (
    echo %~2Purging existing %~1 from container...
    docker exec -u root "%CONTAINER_NAME%" bash -c "rm -rf '%CONTAINER_DOOM_PATH%/%~1'" >nul 2>&1
)
docker exec -u root "%CONTAINER_NAME%" bash -c "mkdir -p '%CONTAINER_DOOM_PATH%/%~1' && chown ignition:ignition '%CONTAINER_DOOM_PATH%/%~1'" >nul 2>&1
goto :eof

REM :deploy_file <category> "<local file>" "<indent>"
REM docker cp writes the file directly into place; ownership is fixed afterwards.
:deploy_file
set /a ATTEMPTED+=1
docker cp "%~2" "%CONTAINER_NAME%:%CONTAINER_DOOM_PATH%/%~1/%~nx2" >nul 2>&1 || goto :deploy_file_failed
docker exec -u root "%CONTAINER_NAME%" bash -c "chown ignition:ignition '%CONTAINER_DOOM_PATH%/%~1/%~nx2'" >nul 2>&1 || goto :deploy_file_failed
echo %~3Deployed: %~nx2
set /a COUNT+=1
goto :eof
:deploy_file_failed
echo %~3WARNING: Failed to deploy %~nx2
set FAILED=1
goto :eof

REM :deploy_dir <category> "<indent>"
REM Copies the whole contents of assets\<category>\ in one docker cp (used for
REM the many small soundfont files). Sets COUNT to the number of files copied.
:deploy_dir
set COUNT=0
if not exist "assets\%~1\" goto :eof
for %%F in ("assets\%~1\*") do if /i not "%%~nxF"==".gitkeep" set /a COUNT+=1
if !COUNT! EQU 0 goto :eof
docker cp "assets\%~1\." "%CONTAINER_NAME%:%CONTAINER_DOOM_PATH%/%~1/" >nul 2>&1 || goto :deploy_dir_failed
docker exec -u root "%CONTAINER_NAME%" bash -c "rm -f '%CONTAINER_DOOM_PATH%/%~1/.gitkeep' && chown -R ignition:ignition '%CONTAINER_DOOM_PATH%/%~1'" >nul 2>&1 || goto :deploy_dir_failed
echo %~2Deployed !COUNT! file(s)
goto :eof
:deploy_dir_failed
echo %~2WARNING: Failed to copy assets\%~1\ to container
set FAILED=1
set COUNT=-1
goto :eof
