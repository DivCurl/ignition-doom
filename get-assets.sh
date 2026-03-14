#!/bin/bash
# ============================================================================
# Perspective DOOM — First-Run Asset Downloader
#
# Downloads freely-distributable assets required to run the module:
#   - DOOM1.WAD  (id Software shareware release — freely redistributable)
#   - FluidR3_GM soundfonts  (CC-BY 3.0 / MIT)
#
# Run once before setup-ignition-container.sh.
# Files that already exist are skipped.
# ============================================================================

WAD_DIR="assets/iwads"
SF_DIR="assets/soundfonts"
STATIC_DIR="ignition-module/gateway/src/main/resources/static"

# DOOM1.WAD — id Software shareware, v1.9 (4.1 MB, md5: f0cefca49926d00903cf57551d901abe)
DOOM1_URL="https://distro.ibiblio.org/slitaz/sources/packages/d/doom1.wad"

# Soundfonts
SF_BASE="https://cdn.jsdelivr.net/gh/gleitz/midi-js-soundfonts@master/FluidR3_GM"
PERC_URL="https://raw.githubusercontent.com/paulrosen/midi-js-soundfonts/gh-pages/FluidR3_GM/percussion-mp3.js"
SP_URL="https://cdn.jsdelivr.net/npm/soundfont-player@0.12.0/dist/soundfont-player.min.js"

RED='\033[0;31m'
YELLOW='\033[1;33m'
GREEN='\033[0;32m'
NC='\033[0m'

echo ""
echo "============================================================================"
echo "  Perspective DOOM — First-Run Asset Downloader"
echo "============================================================================"
echo ""

# pick a download tool
if command -v curl >/dev/null 2>&1; then
    DL="curl"
elif command -v wget >/dev/null 2>&1; then
    DL="wget"
else
    echo -e "${RED}ERROR: Neither curl nor wget found. Install one and retry.${NC}"
    exit 1
fi

download() {
    local url="$1"
    local dest="$2"
    if [ "$DL" = "curl" ]; then
        curl -fsSL "$url" -o "$dest"
    else
        wget -q "$url" -O "$dest"
    fi
}

mkdir -p "$WAD_DIR" "$SF_DIR" "$STATIC_DIR"

OVERALL_FAIL=0

# ── Step 1: DOOM1.WAD ────────────────────────────────────────────────────────
echo "[1/3] DOOM1.WAD (id Software shareware)"
if [ -f "$WAD_DIR/DOOM1.WAD" ] || [ -f "$WAD_DIR/doom1.wad" ]; then
    echo "      SKIP (already present)"
else
    echo "      Downloading ~4.1 MB from ibiblio..."
    if download "$DOOM1_URL" "$WAD_DIR/DOOM1.WAD"; then
        echo "      OK"
    else
        rm -f "$WAD_DIR/DOOM1.WAD"
        echo -e "      ${RED}ERROR: Could not download DOOM1.WAD automatically.${NC}"
        echo "      Place DOOM1.WAD manually in $WAD_DIR/ and re-run."
        echo "      (The id Software shareware release is freely redistributable.)"
        OVERALL_FAIL=1
    fi
fi

# ── Step 2: soundfont-player.min.js ─────────────────────────────────────────
echo ""
echo "[2/3] soundfont-player.min.js"
if [ -f "$SF_DIR/soundfont-player.min.js" ]; then
    echo "      SKIP (already present)"
else
    if download "$SP_URL" "$STATIC_DIR/soundfont-player.min.js"; then
        cp "$STATIC_DIR/soundfont-player.min.js" "$SF_DIR/soundfont-player.min.js"
        echo "      OK"
    else
        echo -e "      ${RED}ERROR: Failed to download soundfont-player.min.js${NC}"
        OVERALL_FAIL=1
    fi
fi

# ── Step 3: FluidR3_GM instrument files ──────────────────────────────────────
echo ""
echo "[3/3] FluidR3_GM instrument files (covers all supported WADs)"

# Derived from MusInstrumentScanner output across DOOM1, DOOM2, TNT, and PLUTONIA.
INSTRUMENTS="
acoustic_grand_piano electric_piano_1 harpsichord vibraphone tubular_bells
rock_organ church_organ acoustic_guitar_nylon electric_guitar_clean
electric_guitar_muted overdriven_guitar distortion_guitar guitar_harmonics
acoustic_bass electric_bass_finger electric_bass_pick slap_bass_1 slap_bass_2
synth_bass_1 synth_bass_2 string_ensemble_1 string_ensemble_2
synth_strings_1 synth_strings_2 choir_aahs trumpet trombone tuba
french_horn brass_section synth_brass_1 synth_brass_2 flute
lead_1_square lead_2_sawtooth pad_2_warm pad_7_halo fx_7_echoes
timpani melodic_tom synth_drum reverse_cymbal guitar_fret_noise
"

SF_COUNT=0
SF_SKIP=0
SF_FAIL=0

for INST in $INSTRUMENTS; do
    FILE="${INST}-mp3.js"
    if [ -f "$SF_DIR/$FILE" ]; then
        SF_SKIP=$((SF_SKIP + 1))
    else
        if download "$SF_BASE/$FILE" "$SF_DIR/$FILE" 2>/dev/null; then
            SF_COUNT=$((SF_COUNT + 1))
        else
            rm -f "$SF_DIR/$FILE"
            echo -e "      ${YELLOW}FAIL: $INST${NC}"
            SF_FAIL=$((SF_FAIL + 1))
        fi
    fi
done

# percussion comes from paulrosen fork — gleitz doesn't include it
if [ -f "$SF_DIR/percussion-mp3.js" ]; then
    SF_SKIP=$((SF_SKIP + 1))
else
    if download "$PERC_URL" "$SF_DIR/percussion-mp3.js" 2>/dev/null; then
        SF_COUNT=$((SF_COUNT + 1))
    else
        rm -f "$SF_DIR/percussion-mp3.js"
        echo -e "      ${YELLOW}FAIL: percussion${NC}"
        SF_FAIL=$((SF_FAIL + 1))
    fi
fi

if [ "$SF_FAIL" -gt 0 ]; then
    echo "      $SF_COUNT downloaded, $SF_SKIP already present, $SF_FAIL FAILED"
    OVERALL_FAIL=1
else
    echo "      $SF_COUNT downloaded, $SF_SKIP already present"
fi

# ── Summary ──────────────────────────────────────────────────────────────────
echo ""
echo "============================================================================"
if [ "$OVERALL_FAIL" -ne 0 ]; then
    echo -e "  ${YELLOW}WARNING: one or more downloads failed.${NC}"
    echo "  Fix the issues above and re-run before deploying."
else
    echo -e "  ${GREEN}Done. All required assets are in place.${NC}"
    echo ""
    echo "  Next: run ./setup-ignition-container.sh"
fi
echo "============================================================================"
echo ""

exit $OVERALL_FAIL
