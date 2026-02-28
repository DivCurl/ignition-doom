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

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"   # <repo>/ignition-module/
SRC="$(dirname "$SCRIPT_DIR")"                                # <repo>/
OUT="$SCRIPT_DIR/build"                                       # <repo>/ignition-module/build/
STAGE="/tmp/doom-modl-stage"                                  # staging area

# Read version from module.xml
if command -v xmllint &>/dev/null; then
    VER=$(xmllint --xpath 'string(/modules/module/version)' "$SCRIPT_DIR/module.xml")
else
    VER=$(grep -oP '(?<=<version>)[^<]+' "$SCRIPT_DIR/module.xml" | head -1)
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
cp "$SRC/headless-renderer/target/headless-renderer-$VER.jar"                  "$STAGE/headless-renderer.jar"

echo "Staged files:"
ls -lh "$STAGE"

# Zip staged files and copy as .modl
ZIP="/tmp/doom-perspective-v$VER.zip"
MODL="$OUT/doom-perspective-v$VER.modl"

rm -f "$ZIP" "$MODL"
(cd "$STAGE" && zip -q "$ZIP" *)
cp "$ZIP" "$MODL"

echo ""
echo "Done! Modl file:"
ls -lh "$MODL"
