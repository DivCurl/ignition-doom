#!/bin/bash
# ============================================================================
# Ignition DOOM Asset Deployment Script
# ============================================================================
# Copies WAD files, soundfonts, and branding assets to a running Ignition
# container. Use this after initial container setup (setup-ignition-container)
# to add or refresh assets without recreating the container.
#
# Usage:
#   scripts/deploy-assets.sh <container>              Deploy all asset categories
#   scripts/deploy-assets.sh <container> -w -s        Deploy wads and sounds only
#   scripts/deploy-assets.sh <container> -w --clean   Purge wads, then redeploy them
#   scripts/deploy-assets.sh <container> --clean      Purge and redeploy all categories
#
# Arguments:
#   <container>      Docker container name (required, positional first argument)
#
# Selective deploy flags (combine freely):
#   -w, --wads       Deploy IWAD files (*.wad) from assets/iwads/
#   -s, --sounds     Deploy SoundFont files from assets/soundfonts/
#   -b, --branding   Deploy branding assets from assets/branding/
#   -p, --pwads      Deploy PWAD/mod files (*.wad, *.pk3, *.pk7) from assets/pwads/
#   -a, --all        Deploy all categories (same as omitting flags entirely)
#
# Purge modifier:
#   --clean          Purge selected categories in the container before deploying
#                    Applies to whichever categories are being deployed
#
# Exit status is non-zero if any file failed to deploy.
# ============================================================================

cd "$(dirname "$0")/.." || exit 1   # asset paths below are relative to the repo root
export MSYS_NO_PATHCONV=1        # Git Bash: don't rewrite container paths as Windows paths

USAGE="Usage: scripts/deploy-assets.sh <container-name> [-w|-s|-b|-p|-a] [--clean]"
if [ -z "$1" ] || [ "${1#-}" != "$1" ]; then
    echo "ERROR: Container name is required as the first argument."
    echo "$USAGE"
    exit 1
fi
CONTAINER_NAME="$1"
shift
CONTAINER_DOOM_PATH="/usr/local/bin/ignition/user-lib/doom"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

# ── Parse arguments ───────────────────────────────────────────────────────────
CLEAN=false
DEPLOY_WADS=false
DEPLOY_SOUNDS=false
DEPLOY_BRANDING=false
DEPLOY_PWADS=false
ANY_DEPLOY_FLAG=false

for arg in "$@"; do
    case "$arg" in
        -w|--wads)     DEPLOY_WADS=true;     ANY_DEPLOY_FLAG=true ;;
        -s|--sounds)   DEPLOY_SOUNDS=true;   ANY_DEPLOY_FLAG=true ;;
        -b|--branding) DEPLOY_BRANDING=true; ANY_DEPLOY_FLAG=true ;;
        -p|--pwads)    DEPLOY_PWADS=true;    ANY_DEPLOY_FLAG=true ;;
        -a|--all)      DEPLOY_WADS=true; DEPLOY_SOUNDS=true; DEPLOY_BRANDING=true; DEPLOY_PWADS=true; ANY_DEPLOY_FLAG=true ;;
        --clean)       CLEAN=true ;;
        *) echo "ERROR: Unknown option: $arg"; echo "$USAGE"; exit 1 ;;
    esac
done

# Default: deploy all categories when no explicit deploy flags are given
if [ "$ANY_DEPLOY_FLAG" = "false" ]; then
    DEPLOY_WADS=true; DEPLOY_SOUNDS=true; DEPLOY_BRANDING=true; DEPLOY_PWADS=true
fi

TARGETS=""
[ "$DEPLOY_WADS"     = "true" ] && TARGETS="$TARGETS wads"
[ "$DEPLOY_SOUNDS"   = "true" ] && TARGETS="$TARGETS sounds"
[ "$DEPLOY_BRANDING" = "true" ] && TARGETS="$TARGETS branding"
[ "$DEPLOY_PWADS"    = "true" ] && TARGETS="$TARGETS pwads"

echo ""
echo "============================================================================"
echo "  Ignition DOOM Asset Deployment"
echo "============================================================================"
echo "  Container: $CONTAINER_NAME"
[ "$ANY_DEPLOY_FLAG" = "true" ] && echo "  Deploy:$TARGETS"
[ "$CLEAN" = "true" ] && echo -e "  ${YELLOW}Clean:$TARGETS${NC}"
echo "============================================================================"
echo ""

# ── Verify container is running ──────────────────────────────────────────────
echo "Checking container status..."
RUNNING=$(docker inspect -f "{{.State.Running}}" "$CONTAINER_NAME" 2>/dev/null)
if [ "$RUNNING" != "true" ]; then
    echo ""
    echo "============================================================================"
    echo -e "  ${RED}ERROR: Container '$CONTAINER_NAME' is not running.${NC}"
    echo ""
    echo "  Run scripts/setup-ignition-container.sh first to create and configure the"
    echo "  container, then re-run this script to deploy assets."
    echo "============================================================================"
    echo ""
    exit 1
fi
echo "      Container is running."
echo ""

FAILED=0

# Run a command in the container as root (needed for chown and for purging).
cexec() { docker exec -u root "$CONTAINER_NAME" bash -c "$1" >/dev/null 2>&1; }

# Prepare a container directory: purge it first with --clean, then ensure it
# exists and is owned by the gateway's 'ignition' user.
prepare_dir() {
    local dir="$CONTAINER_DOOM_PATH/$1"
    if [ "$CLEAN" = "true" ]; then
        echo "$2Purging existing $1 from container..."
        cexec "rm -rf '$dir'"
    fi
    cexec "mkdir -p '$dir' && chown ignition:ignition '$dir'"
}

# Copy each given local file into a container directory (docker cp writes it
# directly; ownership is fixed afterwards). Sets COPIED to the number copied
# and ATTEMPTED to the number of matching local files.
deploy_files() {
    local dir="$CONTAINER_DOOM_PATH/$1" indent="$2" f name
    shift 2
    COPIED=0
    ATTEMPTED=0
    for f in "$@"; do
        [ -f "$f" ] || continue
        ATTEMPTED=$((ATTEMPTED + 1))
        name=$(basename "$f")
        if docker cp "$f" "$CONTAINER_NAME:$dir/$name" >/dev/null 2>&1 \
           && cexec "chown ignition:ignition '$dir/$name'"; then
            echo -e "$indent${GREEN}Deployed: $name${NC}"
            COPIED=$((COPIED + 1))
        else
            echo -e "$indent${YELLOW}WARNING: Failed to deploy $name${NC}"
            FAILED=1
        fi
    done
}

# Copy the whole contents of assets/<category>/ into the container in one docker cp
# (used for the many small soundfont files). Sets COPIED to the number of files.
deploy_dir() {
    local src="assets/$1" dir="$CONTAINER_DOOM_PATH/$1" indent="$2" n
    COPIED=0
    [ -d "$src" ] || return 0
    n=$(find "$src" -maxdepth 1 -type f ! -name '.gitkeep' | wc -l | tr -d ' ')
    [ "$n" -eq 0 ] && return 0
    if docker cp "$src/." "$CONTAINER_NAME:$dir/" >/dev/null 2>&1 \
       && cexec "rm -f '$dir/.gitkeep' && chown -R ignition:ignition '$dir'"; then
        echo -e "$indent${GREEN}Deployed $n file(s)${NC}"
        COPIED=$n
    else
        echo -e "$indent${YELLOW}WARNING: Failed to copy assets/$1/ to container${NC}"
        FAILED=1
        COPIED=-1   # not 0, so callers don't also report "no files found"
    fi
}

shopt -s nullglob nocaseglob   # *.wad also matches DOOM1.WAD; no match → empty list

# ── [wads] Deploy IWAD files ──────────────────────────────────────────────────
if [ "$DEPLOY_WADS" = "true" ]; then
    echo "[wads] Deploying DOOM IWAD files from assets/iwads/..."
    prepare_dir iwads "       "
    deploy_files iwads "       " assets/iwads/*.wad
    if [ "$ATTEMPTED" -eq 0 ]; then
        echo -e "       ${YELLOW}WARNING: No WAD files found in assets/iwads/${NC}"
    elif [ "$COPIED" -gt 0 ]; then
        echo "       $COPIED WAD file(s) deployed."
    fi
    echo ""
fi

# ── [sounds] Deploy soundfonts ────────────────────────────────────────────────
if [ "$DEPLOY_SOUNDS" = "true" ]; then
    echo "[sounds] Deploying SoundFont instrument files from assets/soundfonts/..."
    prepare_dir soundfonts "         "
    deploy_dir soundfonts "         "
    [ "$COPIED" -eq 0 ] && echo "         INFO: No soundfont files found - run scripts/get-assets.sh to fetch them."
    echo ""
fi

# ── [branding] Deploy branding assets ────────────────────────────────────────
if [ "$DEPLOY_BRANDING" = "true" ]; then
    echo "[branding] Deploying branding assets from assets/branding/..."
    prepare_dir branding "           "
    deploy_dir branding "           "
    [ "$COPIED" -eq 0 ] && echo "           INFO: No branding files found - skipping."
    echo ""
fi

# ── [pwads] Deploy PWAD / mod files ──────────────────────────────────────────
if [ "$DEPLOY_PWADS" = "true" ]; then
    echo "[pwads] Deploying PWAD / mod files from assets/pwads/..."
    prepare_dir pwads "        "
    deploy_files pwads "        " assets/pwads/*.wad assets/pwads/*.pk3 assets/pwads/*.pk7
    if [ "$ATTEMPTED" -eq 0 ]; then
        echo "        INFO: No PWAD/mod files found - skipping."
    elif [ "$COPIED" -gt 0 ]; then
        echo "        $COPIED PWAD/mod file(s) deployed."
    fi
    echo ""
fi

# ── Summary ───────────────────────────────────────────────────────────────────
echo "============================================================================"
if [ "$FAILED" -ne 0 ]; then
    echo -e "  ${YELLOW}Deployment finished with errors - see warnings above.${NC}"
else
    echo "  Deployment Complete!"
fi
echo "============================================================================"
if [ "$DEPLOY_WADS" = "true" ]; then
    echo "  WAD files in container:"
    docker exec "$CONTAINER_NAME" bash -c \
        "cd $CONTAINER_DOOM_PATH/iwads 2>/dev/null && ls | grep -i '\.wad\$' | sed 's/^/    /' || echo '    (none)'"
    echo ""
fi
echo "  Landing page:  http://localhost:8088/system/doom"
echo "  Health check:  http://localhost:8088/system/doom/health"
echo "============================================================================"
echo ""

exit $FAILED
