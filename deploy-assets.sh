#!/bin/bash
# ============================================================================
# Ignition DOOM Asset Deployment Script
# ============================================================================
# Copies WAD files, soundfonts, and branding assets to a running Ignition
# container. Use this after initial container setup (setup-ignition-container)
# to add or refresh assets without recreating the container.
#
# Usage:
#   ./deploy-assets.sh <container>              Deploy all asset categories
#   ./deploy-assets.sh <container> -w -s        Deploy wads and sounds only
#   ./deploy-assets.sh <container> -w --clean   Purge wads, then redeploy them
#   ./deploy-assets.sh <container> --clean      Purge and redeploy all categories
#
# Arguments:
#   <container>      Docker container name (required, positional first argument)
#
# Selective deploy flags (combine freely):
#   -w, --wads       Deploy IWAD files from assets/iwads/
#   -s, --sounds     Deploy SoundFont files from assets/soundfonts/
#   -b, --branding   Deploy branding assets from assets/branding/
#   -p, --pwads      Deploy PWAD/mod files from assets/pwads/
#   -a, --all        Deploy all categories (same as omitting flags entirely)
#
# Purge modifier:
#   --clean          Purge selected categories in the container before deploying
#                    Applies to whichever categories are being deployed
# ============================================================================

if [ -z "$1" ]; then
    echo "ERROR: Container name is required as the first argument."
    echo "Usage: ./deploy-assets.sh <container-name> [-w|-s|-b|-p|-a] [--clean]"
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
if [ "$CLEAN" = "true" ]; then
    CLEAN_TARGETS=""
    [ "$DEPLOY_WADS"     = "true" ] && CLEAN_TARGETS="$CLEAN_TARGETS wads"
    [ "$DEPLOY_SOUNDS"   = "true" ] && CLEAN_TARGETS="$CLEAN_TARGETS sounds"
    [ "$DEPLOY_BRANDING" = "true" ] && CLEAN_TARGETS="$CLEAN_TARGETS branding"
    [ "$DEPLOY_PWADS"    = "true" ] && CLEAN_TARGETS="$CLEAN_TARGETS pwads"
    echo -e "  ${YELLOW}Clean:$CLEAN_TARGETS${NC}"
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
    if [ "$CLEAN" = "true" ]; then
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
        if [ "$CLEAN" = "true" ]; then
            echo "         Purging existing soundfonts from container..."
            docker exec -u root "$CONTAINER_NAME" bash -c \
                "rm -rf $CONTAINER_DOOM_PATH/soundfonts && mkdir -p $CONTAINER_DOOM_PATH/soundfonts && chown ignition:ignition $CONTAINER_DOOM_PATH/soundfonts" >/dev/null 2>&1
        else
            docker exec "$CONTAINER_NAME" bash -c "mkdir -p $CONTAINER_DOOM_PATH/soundfonts" >/dev/null 2>&1
        fi
        if docker cp "assets/soundfonts/." "$CONTAINER_NAME:/tmp/soundfonts/" >/dev/null 2>&1; then
            if docker exec -u root "$CONTAINER_NAME" bash -c \
                "cp -r /tmp/soundfonts/. $CONTAINER_DOOM_PATH/soundfonts/ && chown -R ignition:ignition $CONTAINER_DOOM_PATH/soundfonts" >/dev/null 2>&1; then
                for SF_FILE in assets/soundfonts/*; do
                    [ -f "$SF_FILE" ] && echo "         Deployed: $(basename "$SF_FILE")"
                done
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
        if [ "$CLEAN" = "true" ]; then
            echo "           Purging existing branding assets from container..."
            docker exec -u root "$CONTAINER_NAME" bash -c \
                "rm -rf $CONTAINER_DOOM_PATH/branding && mkdir -p $CONTAINER_DOOM_PATH/branding && chown ignition:ignition $CONTAINER_DOOM_PATH/branding" >/dev/null 2>&1
        else
            docker exec "$CONTAINER_NAME" bash -c "mkdir -p $CONTAINER_DOOM_PATH/branding" >/dev/null 2>&1
        fi
        if docker cp "assets/branding/." "$CONTAINER_NAME:/tmp/branding/" >/dev/null 2>&1; then
            if docker exec -u root "$CONTAINER_NAME" bash -c \
                "cp -r /tmp/branding/. $CONTAINER_DOOM_PATH/branding/ && chown -R ignition:ignition $CONTAINER_DOOM_PATH/branding" >/dev/null 2>&1; then
                for B_FILE in assets/branding/*; do
                    [ -f "$B_FILE" ] && echo -e "           ${GREEN}Deployed: $(basename "$B_FILE")${NC}"
                done
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
        if [ "$CLEAN" = "true" ]; then
            echo "        Purging existing PWAD files from container..."
            docker exec -u root "$CONTAINER_NAME" bash -c \
                "rm -rf $CONTAINER_DOOM_PATH/pwads && mkdir -p $CONTAINER_DOOM_PATH/pwads && chown ignition:ignition $CONTAINER_DOOM_PATH/pwads" >/dev/null 2>&1
        else
            docker exec "$CONTAINER_NAME" bash -c "mkdir -p $CONTAINER_DOOM_PATH/pwads" >/dev/null 2>&1
        fi
        PWAD_COUNT=0
        for PWAD_FILE in assets/pwads/*.wad assets/pwads/*.WAD assets/pwads/*.pk3 assets/pwads/*.pk7; do
            [ -f "$PWAD_FILE" ] || continue
            PWAD_NAME=$(basename "$PWAD_FILE")
            if docker cp "$PWAD_FILE" "$CONTAINER_NAME:/tmp/$PWAD_NAME" >/dev/null 2>&1; then
                if docker exec -u root "$CONTAINER_NAME" bash -c \
                    "cp /tmp/$PWAD_NAME $CONTAINER_DOOM_PATH/pwads/ && chown ignition:ignition $CONTAINER_DOOM_PATH/pwads/$PWAD_NAME" >/dev/null 2>&1; then
                    echo -e "        ${GREEN}Deployed: $PWAD_NAME${NC}"
                    PWAD_COUNT=$((PWAD_COUNT + 1))
                else
                    echo -e "        ${YELLOW}WARNING: Failed to install $PWAD_NAME in container${NC}"
                fi
            else
                echo -e "        ${YELLOW}WARNING: Failed to copy $PWAD_NAME to container${NC}"
            fi
        done
        if [ "$PWAD_COUNT" -eq 0 ]; then
            echo "        INFO: No PWAD/mod files found in assets/pwads/ - skipping."
        else
            echo "        $PWAD_COUNT PWAD/mod file(s) deployed."
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
