#!/bin/bash
# ============================================================================
# Ignition DOOM Container Setup Script
# ============================================================================
# This script automates the setup of an Ignition container with:
# - DOOM WAD file installation
# - Unsigned module support enabled
# - Proper directory structure and permissions
# ============================================================================

set -e  # Exit on error

# Container name (mandatory first argument)
if [ -z "$1" ]; then
    echo "ERROR: Container name is required as the first argument."
    echo "Usage: ./setup-ignition-container.sh <container-name>"
    exit 1
fi
CONTAINER_NAME="$1"
IGNITION_IMAGE="inductiveautomation/ignition:8.3.1"
IGNITION_PORT="8088"
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
    echo "  Run ./get-assets.sh to download it automatically, or place DOOM1.WAD"
    echo "  manually in assets/iwads/  (id Software shareware, freely redistributable)."
    echo "============================================================================"
    echo ""
    exit 1
fi
echo "      OK"
echo ""

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
echo "[5/7] Creating asset directories and deploying assets..."
docker exec "$CONTAINER_NAME" bash -c \
    "mkdir -p $CONTAINER_WAD_PATH/soundfonts $CONTAINER_WAD_PATH/branding" || {
    echo -e "      ${RED}ERROR: Failed to create asset directories${NC}"
    exit 1
}
echo "      Directories created."
echo "      Running deploy-assets.sh to copy WADs, soundfonts, and branding..."
echo ""
bash "$(dirname "$0")/deploy-assets.sh" "$CONTAINER_NAME" || echo -e "      ${YELLOW}WARNING: Asset deployment reported errors - check output above.${NC}"

# Step 6: Enable unsigned modules
echo ""
echo "[6/7] Enabling unsigned module installation..."
if docker exec "$CONTAINER_NAME" bash -c "echo 'wrapper.java.additional.9=-Dignition.allowunsignedmodules=true' >> /usr/local/bin/ignition/data/ignition.conf"; then
    # Verify configuration
    if docker exec "$CONTAINER_NAME" bash -c "grep 'allowunsignedmodules' /usr/local/bin/ignition/data/ignition.conf" >/dev/null 2>&1; then
        echo -e "      ${GREEN}Unsigned modules enabled${NC}"
    else
        echo -e "      ${RED}ERROR: Configuration verification failed${NC}"
        exit 1
    fi
else
    echo -e "      ${RED}ERROR: Failed to update configuration${NC}"
    exit 1
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
docker exec "$CONTAINER_NAME" bash -c "ls -lh $CONTAINER_WAD_PATH/iwads/*.WAD $CONTAINER_WAD_PATH/iwads/*.wad 2>/dev/null || echo '  (none found)'"
echo ""
echo "  Gateway URL:    http://localhost:$IGNITION_PORT"
echo "  Landing page:   http://localhost:$IGNITION_PORT/system/doom"
echo "  Health check:   http://localhost:$IGNITION_PORT/system/doom/health"
echo ""
echo "  To add or refresh assets later (without recreating the container):"
echo "    bash deploy-assets.sh $CONTAINER_NAME"
echo ""
echo "  Next steps:"
echo "    1. Commission the gateway (EULA, auth, activation)"
echo "    2. Install DOOM module via: Config > System > Modules"
echo "============================================================================"
echo ""
