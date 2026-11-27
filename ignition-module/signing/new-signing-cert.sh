#!/bin/bash
# new-signing-cert.sh
# One-time setup: creates a self-signed RSA code-signing certificate for signing the .modl.
#
#   bash ignition-module/signing/new-signing-cert.sh [--force] [--subject "CN=..., O=..."] [--days N]
#
# Produces (both gitignored — NEVER commit the .p12, it contains the private key):
#   ignition-module/signing/doom-signing.p12   PKCS#12 keystore (private key + certificate)
#   ignition-module/signing/doom-signing.p7b   PKCS#7 certificate chain (public; embedded in signed modules)
#
# Keystore password: $DOOM_SIGNING_PASSWORD, or prompted for.
# Overrides: DOOM_SIGNING_KEYSTORE / DOOM_SIGNING_CHAIN / DOOM_SIGNING_ALIAS
# Requires: keytool (JDK), openssl

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MODULE_DIR="$(dirname "$SCRIPT_DIR")"
KEYSTORE="${DOOM_SIGNING_KEYSTORE:-$SCRIPT_DIR/doom-signing.p12}"
CHAIN="${DOOM_SIGNING_CHAIN:-$SCRIPT_DIR/doom-signing.p7b}"
ALIAS="${DOOM_SIGNING_ALIAS:-doom-signer}"
DAYS=3650
SUBJECT=""
FORCE=0

die() { echo "ERROR: $*" >&2; exit 1; }

while [ $# -gt 0 ]; do
    case "$1" in
        --force)   FORCE=1 ;;
        --subject) SUBJECT="$2"; shift ;;
        --days)    DAYS="$2"; shift ;;
        *) die "unknown option: $1" ;;
    esac
    shift
done

command -v keytool >/dev/null || die "keytool not found on PATH. Install a JDK (the same one used for the Maven build)."
command -v openssl >/dev/null || die "openssl not found on PATH (needed to build the .p7b chain)."

if { [ -e "$KEYSTORE" ] || [ -e "$CHAIN" ]; } && [ $FORCE -eq 0 ]; then
    die "Signing files already exist ($KEYSTORE / $CHAIN). Re-run with --force to replace them. Modules signed with the old certificate will need their new certificate re-accepted on each gateway."
fi

if [ -z "$SUBJECT" ]; then
    # Build a DN from module.xml; escape DN special characters in the values.
    xml_val() { sed -n "s:.*<$1>\(.*\)</$1>.*:\1:p" "$MODULE_DIR/module.xml" | head -1; }
    esc() { printf '%s' "$1" | sed 's/\([,+"\\<>;=]\)/\\\1/g'; }
    SUBJECT="CN=$(esc "$(xml_val name) Module Signing"), O=$(esc "$(xml_val vendor)")"
fi

# Password: env var or prompt. Passed to keytool via -storepass:env so it never appears on a command line.
if [ -z "${DOOM_SIGNING_PASSWORD:-}" ]; then
    read -rsp "New keystore password (min 6 chars): " P1; echo
    read -rsp "Confirm password: " P2; echo
    [ "$P1" = "$P2" ] || die "Passwords do not match."
    DOOM_SIGNING_PASSWORD="$P1"
fi
[ ${#DOOM_SIGNING_PASSWORD} -ge 6 ] || die "Password must be at least 6 characters (keytool requirement)."
export DOOM_SIGNING_PASSWORD

TMP_CERT="$(mktemp)"
trap 'rm -f "$TMP_CERT"' EXIT
rm -f "$KEYSTORE" "$CHAIN"

echo "Generating RSA-3072 code-signing certificate"
echo "  Subject:  $SUBJECT"
echo "  Validity: $DAYS days"
keytool -genkeypair -alias "$ALIAS" -keyalg RSA -keysize 3072 -sigalg SHA256withRSA \
    -validity "$DAYS" -dname "$SUBJECT" \
    -ext KeyUsage=digitalSignature -ext ExtendedKeyUsage=codeSigning \
    -storetype PKCS12 -keystore "$KEYSTORE" -storepass:env DOOM_SIGNING_PASSWORD

keytool -exportcert -rfc -alias "$ALIAS" -keystore "$KEYSTORE" -storepass:env DOOM_SIGNING_PASSWORD -file "$TMP_CERT"

# Wrap the certificate in a PKCS#7 (.p7b) chain, as module-signer requires.
openssl crl2pkcs7 -nocrl -certfile "$TMP_CERT" -outform DER -out "$CHAIN"
FP="$(openssl x509 -in "$TMP_CERT" -noout -fingerprint -sha256 | cut -d= -f2)"

echo ""
echo "Created:"
echo "  $KEYSTORE   (PRIVATE - back it up, never commit or share it)"
echo "  $CHAIN"
echo ""
echo "SHA-256 fingerprint (publish this so gateway admins can verify the cert they accept):"
echo "  $FP"
echo ""
echo "Next: run package-doom-modl.sh (signs automatically) or signing/sign-modl.sh."
