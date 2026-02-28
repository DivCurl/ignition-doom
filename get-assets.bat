@echo off
REM ============================================================================
REM Perspective DOOM — First-Run Asset Downloader
REM
REM Downloads freely-distributable assets required to run the module:
REM   - DOOM1.WAD  (id Software shareware release — freely redistributable)
REM   - FluidR3_GM soundfonts  (CC-BY 3.0 / MIT)
REM
REM Run once before setup-ignition-container.bat.
REM Files that already exist are skipped.
REM ============================================================================

setlocal enabledelayedexpansion

set WAD_DIR=assets\iwads
set SF_DIR=assets\soundfonts
set STATIC_DIR=ignition-module\gateway\src\main\resources\static

REM DOOM1.WAD — id Software shareware, v1.9 (4.1 MB, md5: f0cefca49926d00903cf57551d901abe)
set DOOM1_URL=https://distro.ibiblio.org/slitaz/sources/packages/d/doom1.wad

REM Soundfonts
set SF_BASE=https://cdn.jsdelivr.net/gh/gleitz/midi-js-soundfonts@master/FluidR3_GM
set PERC_URL=https://raw.githubusercontent.com/paulrosen/midi-js-soundfonts/gh-pages/FluidR3_GM/percussion-mp3.js
set SP_URL=https://cdn.jsdelivr.net/npm/soundfont-player@0.12.0/dist/soundfont-player.min.js

echo.
echo ============================================================================
echo  Perspective DOOM — First-Run Asset Downloader
echo ============================================================================
echo.

REM Create output dirs
if not exist "%WAD_DIR%"    mkdir "%WAD_DIR%"
if not exist "%SF_DIR%"     mkdir "%SF_DIR%"
if not exist "%STATIC_DIR%" mkdir "%STATIC_DIR%"

set OVERALL_FAIL=0

REM ── Step 1: DOOM1.WAD ────────────────────────────────────────────────────────
echo [1/3] DOOM1.WAD (id Software shareware)
if exist "%WAD_DIR%\DOOM1.WAD" (
    echo      SKIP (already present)
) else if exist "%WAD_DIR%\doom1.wad" (
    echo      SKIP (already present as doom1.wad)
) else (
    echo      Downloading ~4.1 MB from ibiblio...
    powershell -Command "Invoke-WebRequest -Uri '%DOOM1_URL%' -OutFile '%WAD_DIR%\DOOM1.WAD' -UseBasicParsing"
    if errorlevel 1 (
        echo.
        echo      ERROR: Could not download DOOM1.WAD automatically.
        echo      Place DOOM1.WAD manually in %WAD_DIR%\ and re-run.
        echo      (The id Software shareware release is freely redistributable.)
        set OVERALL_FAIL=1
    ) else (
        echo      OK
    )
)

REM ── Step 2: soundfont-player.min.js ─────────────────────────────────────────
echo.
echo [2/3] soundfont-player.min.js
if exist "%SF_DIR%\soundfont-player.min.js" (
    echo      SKIP (already present)
) else (
    powershell -Command "Invoke-WebRequest -Uri '%SP_URL%' -OutFile '%STATIC_DIR%\soundfont-player.min.js' -UseBasicParsing"
    if errorlevel 1 (
        echo      ERROR: Failed to download soundfont-player.min.js
        set OVERALL_FAIL=1
    ) else (
        copy /Y "%STATIC_DIR%\soundfont-player.min.js" "%SF_DIR%\soundfont-player.min.js" >nul
        echo      OK
    )
)

REM ── Step 3: FluidR3_GM instrument files ──────────────────────────────────────
echo.
echo [3/3] FluidR3_GM instrument files (covers all supported WADs)

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
  synth_bass_1 ^
  synth_bass_2 ^
  string_ensemble_1 ^
  string_ensemble_2 ^
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
        powershell -Command "Invoke-WebRequest -Uri '%SF_BASE%/!FILE!' -OutFile '%SF_DIR%\!FILE!' -UseBasicParsing" 2>nul
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
    powershell -Command "Invoke-WebRequest -Uri '%PERC_URL%' -OutFile '%SF_DIR%\percussion-mp3.js' -UseBasicParsing" 2>nul
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
    echo  Next: run setup-ignition-container.bat
)
echo ============================================================================
echo.

endlocal
exit /b %OVERALL_FAIL%
