#!/bin/bash
# Runs INSIDE the Ignition container (piped in via `docker exec -i <c> bash -s -- <cmd>`).
# Edits ignition.conf so the gateway does / does not accept unsigned modules.
#   on      add -Dignition.allowunsignedmodules=true (no-op if already present)
#   off     remove it
#   status  print "on" or "off"
# The gateway must be restarted for a change to take effect.
set -e
CONF=/usr/local/bin/ignition/data/ignition.conf
KEY='-Dignition.allowunsignedmodules=true'
[ -f "$CONF" ] || { echo "ERROR: $CONF not found" >&2; exit 2; }

is_on() { grep -qE '^wrapper\.java\.additional\.[0-9]+=-Dignition\.allowunsignedmodules=true' "$CONF"; }

case "$1" in
    status)
        if is_on; then echo on; else echo off; fi ;;
    on)
        if is_on; then echo "already on"; exit 0; fi
        # Next free index, counting commented-out entries too so a later
        # un-comment (e.g. the debug agent lines) can never collide.
        last=$(grep -oE '^#?wrapper\.java\.additional\.[0-9]+=' "$CONF" | grep -oE '[0-9]+' | sort -n | tail -1)
        idx=$(( ${last:-0} + 1 ))
        echo "wrapper.java.additional.${idx}=${KEY}" >> "$CONF"
        is_on && echo "on (wrapper.java.additional.${idx})" ;;
    off)
        if ! is_on; then echo "already off"; exit 0; fi
        sed -i '/^wrapper\.java\.additional\.[0-9]*=-Dignition\.allowunsignedmodules=true/d' "$CONF"
        is_on && { echo "ERROR: could not remove setting" >&2; exit 1; }
        echo "off" ;;
    *)
        echo "usage: unsigned-modules.sh on|off|status" >&2; exit 2 ;;
esac
