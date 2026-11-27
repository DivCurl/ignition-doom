#!/bin/bash
# ============================================================================
# Ignition version compatibility test
# ============================================================================
# For each Ignition version: creates a throwaway Docker gateway, installs the
# module (headless: commissioning is completed through the gateway's own API),
# deploys DOOM1.WAD, and runs a smoke test that starts a real game and waits
# for a rendered frame. Prints a PASS/FAIL table; exit status 1 if any failed.
#
# Usage:
#   scripts/test-ignition-versions.sh [options] [VERSION ...]
#
#   VERSION         inductiveautomation/ignition image tag (default: 8.3.0 8.3)
#   --modl FILE     module to test (default: newest unsigned build for each version's line:
#                   2027.x tags use the experimental *-ign2027.modl, all others the 8.3
#                   build). A signed .modl is installed WITHOUT
#                   allowing unsigned modules, so signing is tested too.
#   --min-version V TEST ONLY: install a copy whose requiredIgnitionVersion is V,
#                   to probe whether older releases would work (unsigned only)
#   --pull          re-pull images even if present (e.g. to refresh "8.3")
#   --keep          leave every gateway running afterwards
#   --keep-passing  leave only the gateways that PASS running (failed ones are removed)
#   --heap MB       gateway max heap (default 2048; enough to actually play on the kept gateways)
#   --jvm ARG       extra JVM/wrapper argument for the gateways (repeatable),
#                   e.g. --jvm "-Xlog:gc:file=/usr/local/bin/ignition/logs/gc.log"
#   --port N        first host port (default 18100; one port per version)
#
# Needs: docker, curl, unzip (and the JDK's `jar` for --min-version).
# Containers are named doomtest-<version>; existing ones with that name are replaced.
# ============================================================================

cd "$(dirname "$0")/.." || exit 1
export MSYS_NO_PATHCONV=1     # Git Bash: don't rewrite container paths as Windows paths

IMAGE=inductiveautomation/ignition
ADMIN_PW="doomtest-$(od -An -tx1 -N6 /dev/urandom | tr -d ' \n')"
MODL_ARG=""; MIN_VERSION=""; PULL=false; KEEP=false; KEEP_PASSING=false; HEAP=2048; PORT=18100; VERSIONS=(); EXTRA_JVM=()

while [ $# -gt 0 ]; do
    case "$1" in
        --modl)        MODL_ARG="$2"; shift ;;
        --min-version) MIN_VERSION="$2"; shift ;;
        --pull)        PULL=true ;;
        --keep)        KEEP=true ;;
        --keep-passing) KEEP_PASSING=true ;;
        --heap)        HEAP="$2"; shift ;;
        --jvm)         EXTRA_JVM+=("$2"); shift ;;
        --port)        PORT="$2"; shift ;;
        -h|--help)     sed -n '2,31p' "$0"; exit 0 ;;
        -*)            echo "ERROR: unknown option $1"; exit 1 ;;
        *)             VERSIONS+=("$1") ;;
    esac
    shift
done
[ ${#VERSIONS[@]} -eq 0 ] && VERSIONS=(8.3.0 8.3)

for tool in docker curl unzip; do
    command -v $tool >/dev/null || { echo "ERROR: $tool not found"; exit 1; }
done
docker info >/dev/null 2>&1 || { echo "ERROR: Docker is not running"; exit 1; }

[ -f assets/iwads/DOOM1.WAD ] || { echo "ERROR: assets/iwads/DOOM1.WAD missing - run scripts/get-assets.sh"; exit 1; }
if [ -n "$MODL_ARG" ] && [ ! -f "$MODL_ARG" ]; then echo "ERROR: --modl file not found: $MODL_ARG"; exit 1; fi
if [ -n "$MIN_VERSION" ]; then
    command -v jar >/dev/null || { echo "ERROR: --min-version needs the JDK 'jar' tool"; exit 1; }
fi

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

# Which build a version gets: --modl if given, else the newest unsigned build for its
# Ignition line (2027.x tags -> the experimental *-ign2027.modl, all others -> the 8.3 build).
pick_modl() {
    if [ -n "$MODL_ARG" ]; then echo "$MODL_ARG"; return; fi
    case "$1" in
        2027*) ls -t ignition-module/build/doom-perspective-v*-ign2027.modl 2>/dev/null | head -1 ;;
        *)     ls -t ignition-module/build/doom-perspective-v*.modl 2>/dev/null | grep -v -e '-signed\.modl$' -e '-ign2027\.modl$' | head -1 ;;
    esac
}

# prepare_modl <tag> <name>: sets CUR_MODL / CUR_SIGNED, or returns 1 with CUR_ERR set.
prepare_modl() {
    CUR_MODL=$(pick_modl "$1"); CUR_SIGNED=false; CUR_ERR=""
    if [ ! -f "$CUR_MODL" ]; then
        case "$1" in
            2027*) CUR_ERR="no -ign2027 build found - package with: package-doom-modl.ps1 -Target 2027 (or --target 2027)" ;;
            *)     CUR_ERR="no .modl found - build and package the module first (or use --modl)" ;;
        esac
        return 1
    fi
    unzip -l "$CUR_MODL" | grep -q 'signatures.properties' && CUR_SIGNED=true
    if [ -n "$MIN_VERSION" ]; then
        $CUR_SIGNED && { CUR_ERR="--min-version needs an unsigned .modl (editing it breaks the signature)"; return 1; }
        cp "$CUR_MODL" "$WORK/$2.modl"
        unzip -p "$CUR_MODL" module.xml \
            | sed "s:<requiredIgnitionVersion>[^<]*</requiredIgnitionVersion>:<requiredIgnitionVersion>$MIN_VERSION</requiredIgnitionVersion>:" \
            > "$WORK/module.xml"
        (cd "$WORK" && jar uf "$2.modl" module.xml) || { CUR_ERR="could not patch module.xml"; return 1; }
        CUR_MODL="$WORK/$2.modl"
    fi
    return 0
}

# Shareware WAD, staged as user-lib/doom/iwads/ so one docker cp creates the folders.
mkdir -p "$WORK/doom/iwads" && cp assets/iwads/DOOM1.WAD "$WORK/doom/iwads/"

# Host paths for docker.exe: under Git Bash, /tmp/... must become C:/... (path conversion is off).
hostpath() { if command -v cygpath >/dev/null; then cygpath -m "$1"; else echo "$1"; fi; }

uuid() {
    local h; h=$(od -An -tx1 -N16 /dev/urandom | tr -d ' \n')
    echo "${h:0:8}-${h:8:4}-4${h:13:3}-a${h:17:3}-${h:20:12}"
}

echo ""
echo "============================================================================"
echo "  Ignition DOOM - version compatibility test"
echo "============================================================================"
echo "  Module:   ${MODL_ARG:-auto: 8.3 build, or the -ign2027 build for 2027.x tags}"
[ -n "$MIN_VERSION" ] && echo "  NOTE:     requiredIgnitionVersion overridden to $MIN_VERSION (test copy only)"
echo "  Versions: ${VERSIONS[*]}"
echo "============================================================================"

RESULTS=()
FAILED=0

# test_version <tag> <port>  - sets RESULT to "PASS ..." or "FAIL ..."
test_version() {
    local tag="$1" port="$2"
    local name="doomtest-$(echo "$tag" | tr -c 'A-Za-z0-9\n' '-')"
    local base="http://localhost:$port"
    local start=$SECONDS
    RESULT=""

    step() { printf '  %-46s' "$1"; }
    fail() { echo "FAIL"; echo "      $1"; RESULT="FAIL  $1"; }

    echo ""
    echo "[$tag]  container $name, port $port"

    step "image"
    if $PULL || ! docker image inspect "$IMAGE:$tag" >/dev/null 2>&1; then
        docker pull -q "$IMAGE:$tag" >/dev/null 2>&1 || { fail "could not pull $IMAGE:$tag"; return; }
    fi
    echo "ok"

    step "module"
    prepare_modl "$tag" "$name" || { fail "$CUR_ERR"; return; }
    echo "$(basename "$CUR_MODL") ($($CUR_SIGNED && echo signed || echo unsigned))"

    step "create gateway + copy module and WAD"
    docker rm -f "$name" >/dev/null 2>&1
    local jvm=(-- "wrapper.java.maxmemory=$HEAP"); $CUR_SIGNED || jvm+=(-Dignition.allowunsignedmodules=true); jvm+=("${EXTRA_JVM[@]}")
    docker create --name "$name" -p "$port:8088" \
        -e ACCEPT_IGNITION_EULA=Y -e GATEWAY_ADMIN_USERNAME=admin -e GATEWAY_ADMIN_PASSWORD="$ADMIN_PW" \
        -e IGNITION_EDITION=standard "$IMAGE:$tag" "${jvm[@]}" >/dev/null 2>&1 \
        || { fail "docker create failed"; return; }
    # Modules must be in place before the first start, or the gateway ignores them.
    docker cp "$(hostpath "$CUR_MODL")" "$name:/usr/local/bin/ignition/user-lib/modules/doom-perspective.modl" >/dev/null 2>&1 \
        || { fail "could not copy the module into the container"; return; }
    docker cp "$(hostpath "$WORK/doom")" "$name:/usr/local/bin/ignition/user-lib/" >/dev/null 2>&1 \
        || { fail "could not copy DOOM1.WAD into the container"; return; }
    docker start "$name" >/dev/null 2>&1 || { fail "could not start the container"; return; }
    echo "ok"

    # Commissioning: accept the module's certificate, then start the gateway.
    step "commission + start gateway (up to 6 min)"
    local accepted=false finished=false empty=0 code b reason=""
    while [ $((SECONDS - start)) -lt 360 ]; do
        code=$(curl -s -o /dev/null -w '%{http_code}' -m 3 "$base/system/doom/health")
        [ "$code" = "200" ] && break
        b=$(curl -s -m 3 "$base/bootstrap")
        if echo "$b" | grep -q '"modules"' && ! $accepted; then
            if curl -s -m 5 "$base/get-step?step=modules" | grep -q 'com.doom.perspective'; then
                curl -s -o /dev/null -m 10 -X POST -H 'Content-Type: application/json' \
                    -d '{"id":"modules","step":"modules","data":{"acceptedLicenses":[],"acceptedCertificates":["com.doom.perspective"]}}' \
                    "$base/post-step" && accepted=true
            fi
        elif echo "$b" | grep -q '"steps":{}' && ! $finished; then
            # Nothing left to commission: start the gateway. Without a module step,
            # wait for a few empty polls first so an early-boot response can't skip it.
            empty=$((empty + 1))
            if $accepted || [ $empty -ge 3 ]; then
                curl -s -o /dev/null -m 10 -X POST -H 'Content-Type: application/json' \
                    -d '{"id":"finished","step":"finished","data":{"startGateway":true}}' "$base/post-step" && finished=true
            fi
        fi
        reason=$(docker logs "$name" 2>&1 | grep -iE 'quarantine|incompatible|requires.*(ignition|platform|framework)' | grep -iE 'doom|com\.doom' | tail -1 | sed 's/^.*\]: //')
        [ -n "$reason" ] && break
        sleep 4
    done
    if [ "$code" != "200" ]; then
        [ -z "$reason" ] && reason=$(docker logs "$name" 2>&1 | grep -iE 'com\.doom|D\.[A-Z]' | grep -E ' E \[| W \[' | tail -1 | sed 's/^.*\]: //')
        fail "module not running (/system/doom/health $code)${reason:+: $reason}"
        return
    fi
    echo "ok ($((SECONDS - start))s)"
    docker exec -u root "$name" chown -R ignition:ignition /usr/local/bin/ignition/user-lib/doom >/dev/null 2>&1

    local gw; gw=$(curl -s -m 5 "$base/system/gwinfo" | grep -oE 'Version=[^;]+' | head -1 | cut -d= -f2)

    step "health: status ok, DOOM1.WAD found"
    curl -s -m 5 "$base/system/doom/health" | grep -q '"status":"ok".*DOOM1.WAD' || { fail "health JSON missing status or WAD"; return; }
    echo "ok"

    step "dashboard + owner.js + owner state"
    curl -s -m 5 "$base/system/doom/" | grep -q '<title>Ignition DOOM</title>' || { fail "dashboard page not served"; return; }
    [ "$(curl -s -o /dev/null -w '%{http_code}' -m 5 "$base/system/doom/static/owner.js")" = "200" ] || { fail "owner.js not served"; return; }
    curl -s -m 5 "$base/system/doom/owner/state" | grep -q '"setupRequired":true' || { fail "owner state wrong"; return; }
    echo "ok"

    local sid; sid=$(uuid)
    step "start single-player game"
    code=$(curl -s -o /dev/null -w '%{http_code}' -m 30 "$base/system/doom/play?wad=DOOM1&session=$sid")
    [ "$code" = "200" ] || { fail "/play returned $code"; return; }
    echo "ok"

    step "engine renders a frame (up to 60s)"
    local bytes=0 t0=$SECONDS
    while [ $((SECONDS - t0)) -lt 60 ]; do
        bytes=$(curl -s -o /dev/null -w '%{size_download}' -m 5 "$base/system/doom/frame?session=$sid")
        [ "${bytes:-0}" -gt 1000 ] && break
        sleep 2
    done
    [ "${bytes:-0}" -gt 1000 ] || { fail "no frame rendered"; return; }
    echo "ok ($bytes bytes)"

    step "sound list loads (up to 30s)"
    local snd="" t1=$SECONDS
    while [ $((SECONDS - t1)) -lt 30 ]; do
        snd=$(curl -s -m 5 "$base/system/doom/sounds?session=$sid")
        echo "$snd" | grep -q '"' && break
        sleep 2
    done
    echo "$snd" | grep -q '"' || { fail "sound list empty"; return; }
    echo "ok ($(echo "$snd" | grep -o '"[^"]*"' | wc -l | tr -d ' ') sounds)"

    local errs; errs=$(docker logs "$name" 2>&1 | grep -cE ' E \[D\.')
    RESULT="PASS  gateway ${gw:-$tag}, $((SECONDS - start))s$([ "$errs" -gt 0 ] && echo ", $errs DOOM error log line(s) - check docker logs $name")"
}

i=0
for v in "${VERSIONS[@]}"; do
    test_version "$v" $((PORT + i))
    RESULTS+=("$(printf '%-18s %s' "$v" "$RESULT")")
    case "$RESULT" in PASS*) ;; *) FAILED=1 ;; esac
    name="doomtest-$(echo "$v" | tr -c 'A-Za-z0-9\n' '-')"
    if $KEEP || { $KEEP_PASSING && [ "${RESULT%% *}" = "PASS" ]; }; then
        echo "  kept: http://localhost:$((PORT + i))/system/doom  (gateway admin password: $ADMIN_PW)"
    else
        docker rm -f "$name" >/dev/null 2>&1
    fi
    i=$((i + 1))
done

echo ""
echo "============================================================================"
echo "  Results"
echo "============================================================================"
for r in "${RESULTS[@]}"; do echo "  $r"; done
echo "============================================================================"
exit $FAILED
