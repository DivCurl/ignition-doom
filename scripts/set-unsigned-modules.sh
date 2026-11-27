#!/bin/bash
# ============================================================================
# Turn the Ignition gateway's "allow unsigned modules" setting on or off in an
# existing container, or show its current state.
#
# Usage:  scripts/set-unsigned-modules.sh <container-name> on|off|status [--no-restart]
#
#   on / off   edit ignition.conf, then restart the container so it takes effect
#              (--no-restart skips the restart; the change applies on next start)
#   status     print the current setting
#
# Signed modules (see ignition-module/signing/) install with this setting off.
# ============================================================================

cd "$(dirname "$0")/.." || exit 1
export MSYS_NO_PATHCONV=1     # Git Bash: don't rewrite container paths as Windows paths

USAGE="Usage: scripts/set-unsigned-modules.sh <container-name> on|off|status [--no-restart]"
CONTAINER_NAME="$1"; ACTION="$2"; OPT="$3"
if [ -z "$CONTAINER_NAME" ] || [ "${CONTAINER_NAME#-}" != "$CONTAINER_NAME" ]; then echo "$USAGE"; exit 1; fi
case "$ACTION" in on|off|status) ;; *) echo "$USAGE"; exit 1 ;; esac
case "$OPT" in ""|--no-restart) ;; *) echo "ERROR: Unknown option: $OPT"; echo "$USAGE"; exit 1 ;; esac

if [ "$(docker inspect -f '{{.State.Running}}' "$CONTAINER_NAME" 2>/dev/null)" != "true" ]; then
    echo "ERROR: Container '$CONTAINER_NAME' is not running."
    exit 1
fi

RESULT=$(docker exec -i "$CONTAINER_NAME" bash -s -- "$ACTION" < scripts/lib/unsigned-modules.sh) || {
    echo "ERROR: Failed to update ignition.conf: $RESULT"; exit 1; }

if [ "$ACTION" = "status" ]; then
    echo "Unsigned modules: $RESULT"
    exit 0
fi
echo "Unsigned modules: $RESULT"
case "$RESULT" in already*) exit 0 ;; esac

if [ "$OPT" = "--no-restart" ]; then
    echo "Restart the container for the change to take effect."
else
    echo "Restarting container to apply..."
    docker restart "$CONTAINER_NAME" >/dev/null || { echo "ERROR: Failed to restart container"; exit 1; }
    echo "Done."
fi
