#!/bin/bash
# sign-modl.sh
# Signs a .modl with Inductive Automation's module-signer, using the keystore created by
# new-signing-cert.sh (or any RSA code-signing keystore + .p7b chain, e.g. a CA-issued cert).
#
#   bash ignition-module/signing/sign-modl.sh [<modl-in> [<modl-out>]]
#
# Defaults: signs ignition-module/build/doom-perspective-v<version>.modl (version from module.xml)
#           and writes doom-perspective-v<version>-signed.modl next to it.
#
# Keystore password: $DOOM_SIGNING_PASSWORD, or prompted for.
# Overrides: DOOM_SIGNING_KEYSTORE / DOOM_SIGNING_CHAIN / DOOM_SIGNING_ALIAS
#   module-signer picks PKCS#12 for .p12/.pfx keystores and JKS for anything else.
#
# module-signer is downloaded once from IA's public Nexus into signing/tools/ (gitignored) and its
# SHA-1 is checked against the value pinned below. Requires: java, unzip, curl or wget, sha1sum or shasum.

set -euo pipefail

# --- Pinned signer (IA public Nexus; matches the .sha1 published alongside it) ---
SIGNER_VERSION="0.0.1"
SIGNER_SHA1="ab947ffd2d4d8976f273d27a5897aa2287f89828"
SIGNER_URL="https://nexus.inductiveautomation.com/repository/inductiveautomation-releases/com/inductiveautomation/ignitionsdk/module-signer/$SIGNER_VERSION/module-signer-$SIGNER_VERSION-jar-with-dependencies.jar"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MODULE_DIR="$(dirname "$SCRIPT_DIR")"
SIGNER_JAR="$SCRIPT_DIR/tools/module-signer-$SIGNER_VERSION.jar"
KEYSTORE="${DOOM_SIGNING_KEYSTORE:-$SCRIPT_DIR/doom-signing.p12}"
CHAIN="${DOOM_SIGNING_CHAIN:-$SCRIPT_DIR/doom-signing.p7b}"
ALIAS="${DOOM_SIGNING_ALIAS:-doom-signer}"

die() { echo "ERROR: $*" >&2; exit 1; }

if [ $# -ge 1 ]; then
    MODL_IN="$1"
else
    VER=$(sed -n 's:.*<version>\(.*\)</version>.*:\1:p' "$MODULE_DIR/module.xml" | head -1)
    MODL_IN="$MODULE_DIR/build/doom-perspective-v$VER.modl"
fi
MODL_OUT="${2:-${MODL_IN%.modl}-signed.modl}"

for f in "$MODL_IN" "$KEYSTORE" "$CHAIN"; do
    [ -f "$f" ] || die "Not found: $f (run new-signing-cert.sh first if the keystore/chain is missing)"
done
command -v java  >/dev/null || die "java not found on PATH."
command -v unzip >/dev/null || die "unzip not found on PATH."

sha1() { if command -v sha1sum >/dev/null; then sha1sum "$1" | cut -d' ' -f1; else shasum -a 1 "$1" | cut -d' ' -f1; fi; }

# --- Fetch + verify module-signer ---
if [ ! -f "$SIGNER_JAR" ] || [ "$(sha1 "$SIGNER_JAR")" != "$SIGNER_SHA1" ]; then
    echo "Downloading module-signer $SIGNER_VERSION from Inductive Automation Nexus..."
    mkdir -p "$(dirname "$SIGNER_JAR")"
    TMP="$SIGNER_JAR.download"
    if command -v curl >/dev/null; then curl -fsSL -o "$TMP" "$SIGNER_URL"; else wget -q -O "$TMP" "$SIGNER_URL"; fi
    GOT="$(sha1 "$TMP")"
    if [ "$GOT" != "$SIGNER_SHA1" ]; then
        rm -f "$TMP"
        die "module-signer checksum mismatch (expected $SIGNER_SHA1, got $GOT). Not using it."
    fi
    mv -f "$TMP" "$SIGNER_JAR"
fi

# --- Password ---
if [ -z "${DOOM_SIGNING_PASSWORD:-}" ]; then
    read -rsp "Password for $(basename "$KEYSTORE"): " DOOM_SIGNING_PASSWORD; echo
fi

# --- Sign ---
# Note: module-signer only accepts passwords as command-line arguments, so the password is briefly
# visible to other processes run by the same user (e.g. via ps).
echo "Signing $(basename "$MODL_IN") ..."
rm -f "$MODL_OUT"
java -jar "$SIGNER_JAR" "-keystore=$KEYSTORE" "-keystore-pwd=$DOOM_SIGNING_PASSWORD" \
    "-alias=$ALIAS" "-alias-pwd=$DOOM_SIGNING_PASSWORD" \
    "-chain=$CHAIN" "-module-in=$MODL_IN" "-module-out=$MODL_OUT" \
    || die "module-signer failed. Check the keystore password and alias '$ALIAS'."
[ -f "$MODL_OUT" ] || die "module-signer produced no output."

# --- Sanity check: every original entry has a signature, chain is embedded ---
unzip -Z1 "$MODL_OUT" | grep -qx 'certificates.p7b' || die "signed module is missing certificates.p7b"
SIGNED="$(unzip -p "$MODL_OUT" signatures.properties | grep -v '^#' | cut -d= -f1 | tr -d '\r')" \
    || die "signed module is missing signatures.properties"
COUNT=0
while IFS= read -r entry; do
    case "$entry" in */) continue ;; esac
    printf '%s\n' "$SIGNED" | grep -qxF "/$entry" || die "entry without a signature: $entry"
    COUNT=$((COUNT + 1))
done < <(unzip -Z1 "$MODL_IN")

echo ""
echo "Signed module: $MODL_OUT"
echo "  Entries signed: $COUNT"
if command -v openssl >/dev/null; then
    CERT="$(openssl pkcs7 -inform DER -in "$CHAIN" -print_certs 2>/dev/null)"
    echo "  Certificate:    $(printf '%s\n' "$CERT" | openssl x509 -noout -subject -enddate | tr '\n' ' ')"
    echo "  SHA-256:        $(printf '%s\n' "$CERT" | openssl x509 -noout -fingerprint -sha256 | cut -d= -f2)"
fi
