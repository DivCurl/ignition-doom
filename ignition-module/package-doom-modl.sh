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
#
# Target Ignition line (--target):
#   8.3   (default) requires Ignition 8.3.0+, as declared in module.xml
#   2027  EXPERIMENTAL: declares Ignition 2027.2.0+ (2027.x gateways refuse 8.3-declared modules).
#         Same compiled code as the 8.3 build (compiled against the 8.3 SDK); verified to run on
#         2027.2.0-beta2, but not yet compile-validated against the 2027 SDK.
#         Output: doom-perspective-vX.Y.Z-ign2027.modl (and -ign2027-signed.modl)
#
# Usage: bash ignition-module/package-doom-modl.sh [--no-sign] [--target 8.3|2027]

set -e

# Declared minimum Ignition version for the experimental 2027.x build.
IGN2027_MIN_VERSION="2027.2.0"

NO_SIGN=0
TARGET="8.3"
while [ $# -gt 0 ]; do
    case "$1" in
        --no-sign) NO_SIGN=1 ;;
        --target)  TARGET="${2:-}"; shift ;;
        *) echo "ERROR: unknown option $1 (use --no-sign, --target 8.3|2027)" >&2; exit 1 ;;
    esac
    shift
done
case "$TARGET" in 8.3|2027) ;; *) echo "ERROR: --target must be 8.3 or 2027" >&2; exit 1 ;; esac

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
if [ "$TARGET" = "2027" ]; then
    MIN_IGN="$IGN2027_MIN_VERSION"; SUFFIX="-ign2027"
else
    MIN_IGN=$(sed -n 's:.*<requiredIgnitionVersion>\(.*\)</requiredIgnitionVersion>.*:\1:p' "$SCRIPT_DIR/module.xml" | head -1)
    SUFFIX=""
fi

echo ""
echo "============================================================"
echo "  DOOM Perspective Module Packager  v$VER"
echo "============================================================"
echo "  Target:  Ignition $TARGET (requires $MIN_IGN+)"
echo "  Source:  $SRC"
echo "  Stage:   $STAGE"
echo "  Output:  $OUT"
echo "============================================================"
if [ "$TARGET" = "2027" ]; then
    echo "  EXPERIMENTAL: 2027.x build. Same code as the 8.3 build with the declared version"
    echo "  changed; runs on 2027.2.0-beta2 but is not compile-validated against the 2027 SDK."
fi
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

# Target line: set the declared minimum Ignition version in the STAGED module.xml only
sed -i.bak "s:<requiredIgnitionVersion>[^<]*</requiredIgnitionVersion>:<requiredIgnitionVersion>$MIN_IGN</requiredIgnitionVersion>:" "$STAGE/module.xml"
rm -f "$STAGE/module.xml.bak"

echo "Staged files:"
ls -lh "$STAGE"

# Zip staged files and copy as .modl
ZIP="/tmp/doom-perspective-v$VER$SUFFIX.zip"
MODL="$OUT/doom-perspective-v$VER$SUFFIX.modl"

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
