#!/bin/bash
# ============================================================================
# Ignition DOOM Container Setup Script
# ============================================================================
# This script automates the setup of an Ignition container with:
# - DOOM WAD file installation
# - Unsigned module support enabled
# - Proper directory structure and permissions
#
# Usage:  scripts/setup-ignition-container.sh <container-name> [-y|--yes] [--allow-unsigned]
#
#   If a container with that name already exists it is DELETED and recreated
#   (all gateway data in it is lost). You are asked to confirm unless -y is given.
#   Set IGNITION_PORT to publish the gateway on a host port other than 8088.
#
#   Unsigned modules are NOT allowed by default: install the -signed.modl (see
#   ignition-module/signing/). --allow-unsigned pre-sets the gateway to accept
#   unsigned modules; scripts/set-unsigned-modules.sh changes it later.
# ============================================================================

set -e  # Exit on error

cd "$(dirname "$0")/.."          # asset paths below are relative to the repo root
export MSYS_NO_PATHCONV=1     # Git Bash: don't rewrite container paths as Windows paths

USAGE="Usage: scripts/setup-ignition-container.sh <container-name> [-y|--yes] [--allow-unsigned]"
# Container name (mandatory first argument)
if [ -z "$1" ] || [ "${1#-}" != "$1" ]; then
    echo "ERROR: Container name is required as the first argument."
    echo "$USAGE"
    exit 1
fi
CONTAINER_NAME="$1"
shift
ASSUME_YES=false
ALLOW_UNSIGNED=false
for arg in "$@"; do
    case "$arg" in
        -y|--yes) ASSUME_YES=true ;;
        --allow-unsigned) ALLOW_UNSIGNED=true ;;
        *) echo "ERROR: Unknown option: $arg"; echo "$USAGE"; exit 1 ;;
    esac
done
IGNITION_IMAGE="inductiveautomation/ignition:8.3.1"
IGNITION_PORT="${IGNITION_PORT:-8088}"
CONTAINER_WAD_PATH="/usr/local/bin/ignition/user-lib/doom"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo ""
echo "============================================================================"
echo "  Ignition DOOM Container Setup"
echo "============================================================================"
echo "  Container: $CONTAINER_NAME"
echo "  Image:     $IGNITION_IMAGE"
echo "  Port:      $IGNITION_PORT"
echo "============================================================================"
echo ""

# ── Required asset check ──────────────────────────────────────────────────────
# DOOM1.WAD must be present — it's the minimum WAD needed to run.
# Soundfonts are optional (music falls back to silence without them).
echo "Checking required assets..."
if [ ! -f "assets/iwads/DOOM1.WAD" ] && [ ! -f "assets/iwads/doom1.wad" ]; then
    echo ""
    echo "============================================================================"
    echo -e "  ${RED}ERROR: DOOM1.WAD not found in assets/iwads/${NC}"
    echo ""
    echo "  DOOM1.WAD ships with this repository. Run scripts/get-assets.sh to restore it"
    echo "  from git, or re-clone the repository."
    echo "============================================================================"
    echo ""
    exit 1
fi
echo "      OK"
echo ""

if ! docker info >/dev/null 2>&1; then
    echo -e "${RED}ERROR: Docker is not installed or the Docker daemon is not running.${NC}"
    exit 1
fi

# Recreating the container destroys everything in it — confirm first.
if docker inspect "$CONTAINER_NAME" >/dev/null 2>&1 && [ "$ASSUME_YES" != "true" ]; then
    echo -e "${YELLOW}WARNING: Container '$CONTAINER_NAME' already exists. It will be DELETED and${NC}"
    echo -e "${YELLOW}recreated, and all gateway data in it (projects, tags, config) will be lost.${NC}"
    if [ -t 0 ]; then
        read -rp "Type the container name to continue: " CONFIRM
    else
        CONFIRM=""
    fi
    if [ "$CONFIRM" != "$CONTAINER_NAME" ]; then
        echo "Aborted. (Use -y to skip this prompt.)"
        exit 1
    fi
    echo ""
fi

# Step 1: Stop and remove existing container
echo "[1/7] Stopping and removing existing container..."
if docker stop "$CONTAINER_NAME" 2>/dev/null; then
    echo "      Container stopped"
else
    echo "      Container not running"
fi

if docker rm "$CONTAINER_NAME" 2>/dev/null; then
    echo "      Container removed"
else
    echo "      Container does not exist"
fi

# Step 2: Create new container
echo ""
echo "[2/7] Creating new container..."
if docker run -d --name "$CONTAINER_NAME" -p "$IGNITION_PORT:8088" "$IGNITION_IMAGE" >/dev/null; then
    echo -e "      ${GREEN}Container created successfully${NC}"
else
    echo -e "      ${RED}ERROR: Failed to create container${NC}"
    exit 1
fi

# Step 3: Wait for container to be ready
echo ""
echo "[3/7] Waiting for container to start..."
sleep 5
echo "      Container started"

# Step 4: Create DOOM directory structure
echo ""
echo "[4/7] Creating DOOM directory structure..."
if docker exec "$CONTAINER_NAME" bash -c "mkdir -p $CONTAINER_WAD_PATH"; then
    echo "      Directory created: $CONTAINER_WAD_PATH"
else
    echo -e "      ${RED}ERROR: Failed to create directory${NC}"
    exit 1
fi

# Step 5: Create asset directories and deploy assets
echo ""
echo "[5/7] Deploying assets..."
echo "      Running deploy-assets.sh to copy WADs, soundfonts, branding, and PWADs..."
echo ""
bash scripts/deploy-assets.sh "$CONTAINER_NAME" || echo -e "      ${YELLOW}WARNING: Asset deployment reported errors - check output above.${NC}"

# Step 6: Gateway configuration (heap memory; unsigned modules only if requested)
echo ""
echo "[6/7] Configuring gateway (heap memory, unsigned modules if set by config)..."
if docker exec "$CONTAINER_NAME" bash -c "echo 'wrapper.java.maxmemory=3072' >> /usr/local/bin/ignition/data/ignition.conf && grep -q '^wrapper.java.maxmemory=3072' /usr/local/bin/ignition/data/ignition.conf"; then
    echo -e "      ${GREEN}Heap memory set to 3072 MB${NC}"
else
    echo -e "      ${RED}ERROR: Failed to set heap memory${NC}"
    exit 1
fi
if [ "$ALLOW_UNSIGNED" = "true" ]; then
    if RESULT=$(docker exec -i "$CONTAINER_NAME" bash -s -- on < scripts/lib/unsigned-modules.sh); then
        echo -e "      ${GREEN}Unsigned modules allowed${NC} ($RESULT)"
    else
        echo -e "      ${RED}ERROR: Failed to allow unsigned modules${NC}"
        exit 1
    fi
else
    echo "      Unsigned modules NOT allowed (default) - install the -signed.modl,"
    echo "      or run: scripts/set-unsigned-modules.sh $CONTAINER_NAME on"
fi

# Step 7: Restart container to apply configuration
echo ""
echo "[7/7] Restarting container to apply configuration..."
if docker restart "$CONTAINER_NAME" >/dev/null 2>&1; then
    echo "      Container restarted"
else
    echo -e "      ${RED}ERROR: Failed to restart container${NC}"
    exit 1
fi

# Wait for restart
sleep 5

# Final verification
echo ""
echo "============================================================================"
echo "  Setup Complete!"
echo "============================================================================"
echo "  Installed WAD files:"
docker exec "$CONTAINER_NAME" bash -c "cd $CONTAINER_WAD_PATH/iwads 2>/dev/null && ls -lh | grep -i '\.wad\$' || echo '  (none found)'"
echo ""
echo "  Gateway URL:    http://localhost:$IGNITION_PORT"
echo "  Landing page:   http://localhost:$IGNITION_PORT/system/doom"
echo "  Health check:   http://localhost:$IGNITION_PORT/system/doom/health"
echo ""
echo "  To add or refresh assets later (without recreating the container):"
echo "    scripts/deploy-assets.sh $CONTAINER_NAME"
echo ""
echo "  Next steps:"
echo "    1. Commission the gateway (EULA, auth, activation)"
echo "    2. Install DOOM module via: Config > System > Modules"
echo "============================================================================"
echo ""
