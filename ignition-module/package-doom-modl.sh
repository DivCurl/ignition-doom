#!/bin/bash
# package-doom-modl.sh
# Packages the DOOM Perspective Ignition module into a deployable .modl file.
#
# Run from the repo root or the ignition-module directory:
#   bash ignition-module/package-doom-modl.sh
#
# Requires: zip, xmllint (or falls back to grep/sed for version parsing)
# Staging is done in /tmp to avoid file-lock conflicts with open editors.
# Output: ignition-module/build/doom-perspective-vX.Y.Z.modl
#         ignition-module/build/doom-perspective-vX.Y.Z-signed.modl  (if a signing keystore exists)
#
# Signing: if signing/doom-signing.p12 (or $DOOM_SIGNING_KEYSTORE) exists, the module is also
# signed via signing/sign-modl.sh. Create a keystore once with signing/new-signing-cert.sh.
# Pass --no-sign to skip signing.

set -e

NO_SIGN=0
[ "${1:-}" = "--no-sign" ] && NO_SIGN=1

command -v zip >/dev/null || { echo "ERROR: 'zip' not found - install it (e.g. apt install zip)." >&2; exit 1; }

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"   # <repo>/ignition-module/
SRC="$(dirname "$SCRIPT_DIR")"                                # <repo>/
OUT="$SCRIPT_DIR/build"                                       # <repo>/ignition-module/build/
STAGE="/tmp/doom-modl-stage"                                  # staging area

# Read version from module.xml
if command -v xmllint &>/dev/null; then
    VER=$(xmllint --xpath 'string(/modules/module/version)' "$SCRIPT_DIR/module.xml")
else
    VER=$(sed -n 's:.*<version>\(.*\)</version>.*:\1:p' "$SCRIPT_DIR/module.xml" | head -1)
fi

echo ""
echo "============================================================"
echo "  DOOM Perspective Module Packager  v$VER"
echo "============================================================"
echo "  Source:  $SRC"
echo "  Stage:   $STAGE"
echo "  Output:  $OUT"
echo "============================================================"
echo ""

# Clean and create staging dir
rm -rf "$STAGE"
mkdir -p "$STAGE"

# Ensure output dir exists
mkdir -p "$OUT"

# Copy module files into staging dir
cp "$SCRIPT_DIR/module.xml"                                                    "$STAGE/module.xml"
cp "$SCRIPT_DIR/common/target/common.jar"                                      "$STAGE/common.jar"
cp "$SCRIPT_DIR/gateway/target/gateway.jar"                                    "$STAGE/gateway.jar"
# headless-renderer JAR version comes from its own pom.xml (may differ from the module version)
HR_VER=$(sed -n 's:^    <version>\(.*\)</version>.*:\1:p' "$SRC/headless-renderer/pom.xml" | head -1)
cp "$SRC/headless-renderer/target/headless-renderer-$HR_VER.jar"               "$STAGE/headless-renderer.jar"

echo "Staged files:"
ls -lh "$STAGE"

# Zip staged files and copy as .modl
ZIP="/tmp/doom-perspective-v$VER.zip"
MODL="$OUT/doom-perspective-v$VER.modl"

rm -f "$ZIP" "$MODL" "${MODL%.modl}-signed.modl"   # never leave a stale signed build behind
(cd "$STAGE" && zip -q "$ZIP" *)
cp "$ZIP" "$MODL"

echo ""
echo "Done! Modl file:"
ls -lh "$MODL"

# Sign (optional)
KEYSTORE="${DOOM_SIGNING_KEYSTORE:-$SCRIPT_DIR/signing/doom-signing.p12}"
if [ $NO_SIGN -eq 1 ]; then
    echo "Skipping signing (--no-sign)."
elif [ -f "$KEYSTORE" ]; then
    bash "$SCRIPT_DIR/signing/sign-modl.sh" "$MODL" \
        || { echo "Signing failed; the unsigned module above is still available." >&2; exit 1; }
else
    echo "Module is UNSIGNED (no keystore at $KEYSTORE)."
    echo "To produce a signed module, run once:  bash ignition-module/signing/new-signing-cert.sh"
fi
