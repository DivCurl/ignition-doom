#!/bin/bash
# ============================================================================
# Ignition DOOM Asset Deployment Script
# ============================================================================
# Copies WAD files, soundfonts, and branding assets to a running Ignition
# container. Use this after initial container setup (setup-ignition-container)
# to add or refresh assets without recreating the container.
#
# Usage:
#   ./deploy-assets.sh <container>                  Deploy all asset categories
#   ./deploy-assets.sh <container> -w -s            Deploy wads and sounds only
#
# Arguments:
#   <container>      Docker container name (required, positional first argument)
#
# Selective deploy flags (combine freely):
#   -w, --wads       Deploy IWAD files from assets/iwads/
#   -s, --sounds     Deploy SoundFont files from assets/soundfonts/
#   -b, --branding   Deploy branding assets from assets/branding/
#   -p, --pwads      Deploy PWAD/mod files from assets/pwads/
#
# Purge flags (clear the category in the container before deploying):
#   --purge-wad      Clear existing WADs before deploying
#   --purge-sound    Clear existing soundfonts before deploying
#   --purge-branding Clear existing branding assets before deploying
#   --purge-pwad     Clear existing PWADs before deploying
#   --purge-all      Clear all categories before deploying
# ============================================================================

if [ -z "$1" ]; then
    echo "ERROR: Container name is required as the first argument."
    echo "Usage: ./deploy-assets.sh <container-name> [-w] [-s] [-b] [-p] [--purge-*]"
    exit 1
fi
CONTAINER_NAME="$1"
shift
CONTAINER_DOOM_PATH="/usr/local/bin/ignition/user-lib/doom"
CONTAINER_IWAD_PATH="$CONTAINER_DOOM_PATH/iwads"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

# ── Parse arguments ───────────────────────────────────────────────────────────
PURGE_WAD=false
PURGE_SOUND=false
PURGE_BRANDING=false
PURGE_PWAD=false
DEPLOY_WADS=false
DEPLOY_SOUNDS=false
DEPLOY_BRANDING=false
DEPLOY_PWADS=false
ANY_DEPLOY_FLAG=false

for arg in "$@"; do
    case "$arg" in
        -w|--wads)        DEPLOY_WADS=true;     ANY_DEPLOY_FLAG=true ;;
        -s|--sounds)      DEPLOY_SOUNDS=true;   ANY_DEPLOY_FLAG=true ;;
        -b|--branding)    DEPLOY_BRANDING=true; ANY_DEPLOY_FLAG=true ;;
        -p|--pwads)       DEPLOY_PWADS=true;    ANY_DEPLOY_FLAG=true ;;
        --purge-all)      PURGE_WAD=true; PURGE_SOUND=true; PURGE_BRANDING=true; PURGE_PWAD=true ;;
        --purge-wad)      PURGE_WAD=true ;;
        --purge-sound)    PURGE_SOUND=true ;;
        --purge-branding) PURGE_BRANDING=true ;;
        --purge-pwad)     PURGE_PWAD=true ;;
    esac
done

# Default: deploy all categories when no explicit deploy flags are given
if [ "$ANY_DEPLOY_FLAG" = "false" ]; then
    DEPLOY_WADS=true; DEPLOY_SOUNDS=true; DEPLOY_BRANDING=true; DEPLOY_PWADS=true
fi

echo ""
echo "============================================================================"
echo "  Ignition DOOM Asset Deployment"
echo "============================================================================"
echo "  Container: $CONTAINER_NAME"
if [ "$ANY_DEPLOY_FLAG" = "true" ]; then
    DEPLOY_TARGETS=""
    [ "$DEPLOY_WADS"     = "true" ] && DEPLOY_TARGETS="$DEPLOY_TARGETS wads"
    [ "$DEPLOY_SOUNDS"   = "true" ] && DEPLOY_TARGETS="$DEPLOY_TARGETS sounds"
    [ "$DEPLOY_BRANDING" = "true" ] && DEPLOY_TARGETS="$DEPLOY_TARGETS branding"
    [ "$DEPLOY_PWADS"    = "true" ] && DEPLOY_TARGETS="$DEPLOY_TARGETS pwads"
    echo "  Deploy:$DEPLOY_TARGETS"
fi
if [ "$PURGE_WAD" = "true" ] || [ "$PURGE_SOUND" = "true" ] || [ "$PURGE_BRANDING" = "true" ] || [ "$PURGE_PWAD" = "true" ]; then
    PURGE_TARGETS=""
    [ "$PURGE_WAD"      = "true" ] && PURGE_TARGETS="$PURGE_TARGETS wad"
    [ "$PURGE_SOUND"    = "true" ] && PURGE_TARGETS="$PURGE_TARGETS sound"
    [ "$PURGE_BRANDING" = "true" ] && PURGE_TARGETS="$PURGE_TARGETS branding"
    [ "$PURGE_PWAD"     = "true" ] && PURGE_TARGETS="$PURGE_TARGETS pwad"
    echo -e "  ${YELLOW}Purge:$PURGE_TARGETS${NC}"
fi
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
    echo "  Run ./setup-ignition-container.sh first to create and configure the"
    echo "  container, then re-run this script to deploy assets."
    echo "============================================================================"
    echo ""
    exit 1
fi
echo "      Container is running."
echo ""

# ── [wads] Deploy IWAD files ──────────────────────────────────────────────────
if [ "$DEPLOY_WADS" = "true" ]; then
    echo "[wads] Deploying DOOM IWAD files..."
    echo "       Searching assets/iwads/ for WAD files..."
    docker exec "$CONTAINER_NAME" bash -c "mkdir -p $CONTAINER_IWAD_PATH" >/dev/null 2>&1
    if [ "$PURGE_WAD" = "true" ]; then
        echo "       Purging existing WAD files from container..."
        docker exec -u root "$CONTAINER_NAME" bash -c \
            "rm -f $CONTAINER_IWAD_PATH/*.WAD $CONTAINER_IWAD_PATH/*.wad" >/dev/null 2>&1
    fi

    WAD_COUNT=0
    for WAD in DOOM1.WAD DOOM.WAD DOOM2.WAD TNT.WAD PLUTONIA.WAD; do
        if [ -f "assets/iwads/$WAD" ]; then
            if docker cp "assets/iwads/$WAD" "$CONTAINER_NAME:/tmp/$WAD" >/dev/null 2>&1; then
                if docker exec -u root "$CONTAINER_NAME" bash -c \
                    "cp /tmp/$WAD $CONTAINER_IWAD_PATH/ && chown ignition:ignition $CONTAINER_IWAD_PATH/$WAD" >/dev/null 2>&1; then
                    echo -e "       ${GREEN}Deployed: $WAD${NC}"
                    WAD_COUNT=$((WAD_COUNT + 1))
                else
                    echo -e "       ${YELLOW}WARNING: Failed to install $WAD in container${NC}"
                fi
            else
                echo -e "       ${YELLOW}WARNING: Failed to copy $WAD to container${NC}"
            fi
        else
            echo "       Skipped (not found): $WAD"
        fi
    done

    if [ "$WAD_COUNT" -eq 0 ]; then
        echo -e "       ${YELLOW}WARNING: No WAD files found in assets/iwads/${NC}"
    else
        echo "       $WAD_COUNT WAD file(s) deployed."
    fi
    echo ""
fi

# ── [sounds] Deploy soundfonts ────────────────────────────────────────────────
if [ "$DEPLOY_SOUNDS" = "true" ]; then
    echo "[sounds] Deploying SoundFont instrument files..."
    if [ ! -d "assets/soundfonts" ]; then
        echo "         INFO: assets/soundfonts/ not found - skipping."
    else
        if [ "$PURGE_SOUND" = "true" ]; then
            echo "         Purging existing soundfonts from container..."
            docker exec -u root "$CONTAINER_NAME" bash -c \
                "rm -rf $CONTAINER_DOOM_PATH/soundfonts && mkdir -p $CONTAINER_DOOM_PATH/soundfonts && chown ignition:ignition $CONTAINER_DOOM_PATH/soundfonts" >/dev/null 2>&1
        else
            docker exec "$CONTAINER_NAME" bash -c "mkdir -p $CONTAINER_DOOM_PATH/soundfonts" >/dev/null 2>&1
        fi
        if docker cp "assets/soundfonts/." "$CONTAINER_NAME:/tmp/soundfonts/" >/dev/null 2>&1; then
            if docker exec -u root "$CONTAINER_NAME" bash -c \
                "cp -r /tmp/soundfonts/. $CONTAINER_DOOM_PATH/soundfonts/ && chown -R ignition:ignition $CONTAINER_DOOM_PATH/soundfonts" >/dev/null 2>&1; then
                SF_COUNT=$(ls assets/soundfonts/*-mp3.js 2>/dev/null | wc -l)
                echo "         $SF_COUNT instrument files deployed."
            else
                echo -e "         ${YELLOW}WARNING: SoundFont installation failed.${NC}"
            fi
        else
            echo -e "         ${YELLOW}WARNING: Failed to copy soundfonts to container.${NC}"
        fi
    fi
    echo ""
fi

# ── [branding] Deploy branding assets ────────────────────────────────────────
if [ "$DEPLOY_BRANDING" = "true" ]; then
    echo "[branding] Deploying branding assets..."
    if [ ! -d "assets/branding" ]; then
        echo "           INFO: assets/branding/ not found - skipping."
    else
        if [ "$PURGE_BRANDING" = "true" ]; then
            echo "           Purging existing branding assets from container..."
            docker exec -u root "$CONTAINER_NAME" bash -c \
                "rm -rf $CONTAINER_DOOM_PATH/branding && mkdir -p $CONTAINER_DOOM_PATH/branding && chown ignition:ignition $CONTAINER_DOOM_PATH/branding" >/dev/null 2>&1
        else
            docker exec "$CONTAINER_NAME" bash -c "mkdir -p $CONTAINER_DOOM_PATH/branding" >/dev/null 2>&1
        fi
        if docker cp "assets/branding/." "$CONTAINER_NAME:/tmp/branding/" >/dev/null 2>&1; then
            if docker exec -u root "$CONTAINER_NAME" bash -c \
                "cp -r /tmp/branding/. $CONTAINER_DOOM_PATH/branding/ && chown -R ignition:ignition $CONTAINER_DOOM_PATH/branding" >/dev/null 2>&1; then
                echo -e "           ${GREEN}Branding assets deployed.${NC}"
            else
                echo -e "           ${YELLOW}WARNING: Branding asset installation failed.${NC}"
            fi
        else
            echo -e "           ${YELLOW}WARNING: Failed to copy branding assets to container.${NC}"
        fi
    fi
    echo ""
fi

# ── [pwads] Deploy PWAD / mod files ──────────────────────────────────────────
if [ "$DEPLOY_PWADS" = "true" ]; then
    echo "[pwads] Deploying PWAD / mod files..."
    if [ ! -d "assets/pwads" ]; then
        echo "        INFO: assets/pwads/ not found - skipping."
    else
        if [ "$PURGE_PWAD" = "true" ]; then
            echo "        Purging existing PWAD files from container..."
            docker exec -u root "$CONTAINER_NAME" bash -c \
                "rm -rf $CONTAINER_DOOM_PATH/pwads && mkdir -p $CONTAINER_DOOM_PATH/pwads && chown ignition:ignition $CONTAINER_DOOM_PATH/pwads" >/dev/null 2>&1
        else
            docker exec "$CONTAINER_NAME" bash -c "mkdir -p $CONTAINER_DOOM_PATH/pwads" >/dev/null 2>&1
        fi
        PWAD_COUNT=$(ls assets/pwads/*.wad assets/pwads/*.WAD assets/pwads/*.pk3 assets/pwads/*.pk7 2>/dev/null | wc -l | tr -d ' ')
        if [ "$PWAD_COUNT" -eq 0 ]; then
            echo "        INFO: No PWAD/mod files found in assets/pwads/ - skipping."
        else
            if docker cp "assets/pwads/." "$CONTAINER_NAME:/tmp/pwads/" >/dev/null 2>&1; then
                if docker exec -u root "$CONTAINER_NAME" bash -c \
                    "find /tmp/pwads -maxdepth 1 -type f | xargs -I{} cp {} $CONTAINER_DOOM_PATH/pwads/ && chown -R ignition:ignition $CONTAINER_DOOM_PATH/pwads" >/dev/null 2>&1; then
                    echo -e "        ${GREEN}$PWAD_COUNT PWAD/mod file(s) deployed.${NC}"
                else
                    echo -e "        ${YELLOW}WARNING: PWAD installation failed.${NC}"
                fi
            else
                echo -e "        ${YELLOW}WARNING: Failed to copy PWADs to container.${NC}"
            fi
        fi
    fi
    echo ""
fi

# ── Summary ───────────────────────────────────────────────────────────────────
echo "============================================================================"
echo "  Deployment Complete!"
echo "============================================================================"
if [ "$DEPLOY_WADS" = "true" ]; then
    echo "  WAD files in container:"
    docker exec "$CONTAINER_NAME" bash -c \
        "ls $CONTAINER_IWAD_PATH/*.WAD $CONTAINER_IWAD_PATH/*.wad 2>/dev/null | xargs -I{} basename {} || echo '  (none)'"
    echo ""
fi
echo "  Landing page:  http://localhost:8088/system/doom"
echo "  Health check:  http://localhost:8088/system/doom/health"
echo "============================================================================"
echo ""
