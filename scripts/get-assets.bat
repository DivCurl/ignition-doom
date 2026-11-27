@echo off
REM ============================================================================
REM Perspective DOOM — First-Run Asset Downloader
REM
REM Fetches assets required to run the module:
REM   - DOOM1.WAD  (id Software shareware — ships with the repo; restored from git if missing)
REM   - FluidR3_GM soundfonts  (CC-BY 3.0 / MIT)
REM (soundfont-player.min.js is committed under the gateway's static resources.)
REM
REM Run once before scripts\setup-ignition-container.bat.
REM Files that already exist are skipped.
REM ============================================================================

setlocal enabledelayedexpansion
cd /d "%~dp0.."

set WAD_DIR=assets\iwads
set SF_DIR=assets\soundfonts

REM DOOM1.WAD — id Software shareware, v1.9 (4.1 MB, md5: f0cefca49926d00903cf57551d901abe)
REM Committed to the repo (see CREDITS.md); restored from git if missing.
set DOOM1_REPO_PATH=assets/iwads/DOOM1.WAD

REM Soundfonts
set SF_BASE=https://cdn.jsdelivr.net/gh/gleitz/midi-js-soundfonts@master/FluidR3_GM
set PERC_URL=https://raw.githubusercontent.com/paulrosen/midi-js-soundfonts/gh-pages/FluidR3_GM/percussion-mp3.js

echo.
echo ============================================================================
echo  Perspective DOOM - First-Run Asset Downloader
echo ============================================================================
echo.

REM Create output dirs
if not exist "%WAD_DIR%"    mkdir "%WAD_DIR%"
if not exist "%SF_DIR%"     mkdir "%SF_DIR%"

set OVERALL_FAIL=0

REM ── Step 1: DOOM1.WAD ────────────────────────────────────────────────────────
echo [1/2] DOOM1.WAD (id Software shareware)
if exist "%WAD_DIR%\DOOM1.WAD" (
    echo      SKIP ^(already present^)
) else if exist "%WAD_DIR%\doom1.wad" (
    echo      SKIP ^(already present as doom1.wad^)
) else (
    echo      Restoring from the git repository...
    git checkout -- "%DOOM1_REPO_PATH%" >nul 2>&1
    if errorlevel 1 (
        echo.
        echo      ERROR: Could not restore DOOM1.WAD from git.
        echo      DOOM1.WAD ships with this repository. Re-clone it, or download
        echo      %DOOM1_REPO_PATH% from https://github.com/DivCurl/ignition-doom
        echo      into %WAD_DIR%\ and re-run.
        set OVERALL_FAIL=1
    ) else (
        echo      OK
    )
)

REM ── Step 2: FluidR3_GM instrument files ──────────────────────────────────────
echo.
echo [2/2] FluidR3_GM instrument files (covers all supported WADs)

REM Derived from MusInstrumentScanner output across DOOM1, DOOM2, TNT, and PLUTONIA.
set INSTRUMENTS=^
  acoustic_grand_piano ^
  electric_piano_1 ^
  harpsichord ^
  vibraphone ^
  tubular_bells ^
  rock_organ ^
  church_organ ^
  acoustic_guitar_nylon ^
  electric_guitar_clean ^
  electric_guitar_muted ^
  overdriven_guitar ^
  distortion_guitar ^
  guitar_harmonics ^
  acoustic_bass ^
  electric_bass_finger ^
  electric_bass_pick ^
  slap_bass_1 ^
  slap_bass_2 ^
  synth_bass_1 ^
  synth_bass_2 ^
  string_ensemble_1 ^
  string_ensemble_2 ^
  violin ^
  cello ^
  orchestral_harp ^
  pizzicato_strings ^
  tremolo_strings ^
  dulcimer ^
  synth_strings_1 ^
  synth_strings_2 ^
  choir_aahs ^
  trumpet ^
  trombone ^
  tuba ^
  french_horn ^
  brass_section ^
  synth_brass_1 ^
  synth_brass_2 ^
  flute ^
  lead_1_square ^
  lead_2_sawtooth ^
  pad_2_warm ^
  pad_7_halo ^
  fx_7_echoes ^
  timpani ^
  melodic_tom ^
  synth_drum ^
  reverse_cymbal ^
  guitar_fret_noise

set SF_COUNT=0
set SF_SKIP=0
set SF_FAIL=0

for %%I in (%INSTRUMENTS%) do (
    set FILE=%%I-mp3.js
    if exist "%SF_DIR%\!FILE!" (
        set /a SF_SKIP+=1
    ) else (
        powershell -NoProfile -Command "$ProgressPreference='SilentlyContinue'; Invoke-WebRequest -Uri '%SF_BASE%/!FILE!' -OutFile '%SF_DIR%\!FILE!' -UseBasicParsing" 2>nul
        if errorlevel 1 (
            echo      FAIL: %%I
            set /a SF_FAIL+=1
        ) else (
            set /a SF_COUNT+=1
        )
    )
)

REM percussion comes from paulrosen fork — gleitz doesn't include it
if exist "%SF_DIR%\percussion-mp3.js" (
    set /a SF_SKIP+=1
) else (
    powershell -NoProfile -Command "$ProgressPreference='SilentlyContinue'; Invoke-WebRequest -Uri '%PERC_URL%' -OutFile '%SF_DIR%\percussion-mp3.js' -UseBasicParsing" 2>nul
    if errorlevel 1 (
        echo      FAIL: percussion
        set /a SF_FAIL+=1
    ) else (
        set /a SF_COUNT+=1
    )
)

if %SF_FAIL% GTR 0 (
    echo      %SF_COUNT% downloaded, %SF_SKIP% already present, %SF_FAIL% FAILED
    set OVERALL_FAIL=1
) else (
    echo      %SF_COUNT% downloaded, %SF_SKIP% already present
)

REM ── Summary ──────────────────────────────────────────────────────────────────
echo.
echo ============================================================================
if %OVERALL_FAIL% NEQ 0 (
    echo  WARNING: one or more downloads failed.
    echo  Fix the issues above and re-run before deploying.
) else (
    echo  Done. All required assets are in place.
    echo.
    echo  Next: run scripts\setup-ignition-container.bat
)
echo ============================================================================
echo.

endlocal & exit /b %OVERALL_FAIL%
