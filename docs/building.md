# Building Ignition DOOM

[← Back to the README](../README.md)

How to build, package, sign and test the module. To install and run a finished `.modl`, see
[Deployment](deployment.md).

- [Prerequisites](#prerequisites)
- [Get the assets](#get-the-assets)
- [Ignition SDK (one-time setup)](#ignition-sdk-one-time-setup)
- [Build](#build)
- [Package the .modl](#package-the-modl)
- [Signing the module (recommended)](#signing-the-module-recommended)
- [Testing Ignition versions](#testing-ignition-versions)

---

## Prerequisites

- Java 11+ and Maven 3.8+ *(the module compiles to Java 11 bytecode; Ignition brings its own
  runtime: Java 17 for 8.3, Java 25 for the 2027 beta)*
- Docker, to extract the Ignition SDK and for the test gateways
- On Linux/Mac, `zip` for packaging (`xmllint` is used if present)
- Internet access for first-run asset downloads (soundfonts; DOOM1.WAD ships with the repo)

---

## Get the assets

Run once:

```bat
scripts\get-assets.bat        # Windows
scripts/get-assets.sh         # Linux/Mac
```

Downloads the FluidR3_GM soundfonts (~99 MB) into `assets/`. DOOM1.WAD (shareware, ~4 MB) is
already in the repo; the script restores it from git if it is missing.

---

## Ignition SDK (one-time setup)

The build compiles against two Ignition 8.3.1 SDK jars, `ignition-common` and `gateway-api`, under
the Maven coordinates `com.inductiveautomation.ignitionsdk:<name>:8.3.1`. Inductive Automation's
public Maven repository publishes only a POM for those coordinates; the jars themselves live under a
different group (`com.inductiveautomation.ignition`). So `mvn package` can't download them as
configured. Do this once per machine: copy the jars out of a running **8.3.1** gateway container,
for example one created with `scripts/setup-ignition-container` (see [Deployment](deployment.md)),
and install them into your local Maven repository.

On Windows, `ignition-module\install-sdk.bat` does all of this interactively. Otherwise, run these
from the `ignition-module/` folder:

```bash
# Find your container name
docker ps
mkdir -p sdk

# Copy SDK JARs from container
docker cp <container-name>:/usr/local/bin/ignition/lib/core/common/common.jar ./sdk/ignition-common.jar
docker cp <container-name>:/usr/local/bin/ignition/lib/core/gateway/gateway-api-8.3.1.jar ./sdk/gateway-api.jar

# Install to local Maven repository
mvn install:install-file -Dfile=sdk/ignition-common.jar \
    -DgroupId=com.inductiveautomation.ignitionsdk \
    -DartifactId=ignition-common \
    -Dversion=8.3.1 \
    -Dpackaging=jar

mvn install:install-file -Dfile=sdk/gateway-api.jar \
    -DgroupId=com.inductiveautomation.ignitionsdk \
    -DartifactId=gateway-api \
    -Dversion=8.3.1 \
    -Dpackaging=jar
```

Check that it worked:

```bash
ls ~/.m2/repository/com/inductiveautomation/ignitionsdk/ignition-common/8.3.1/
ls ~/.m2/repository/com/inductiveautomation/ignitionsdk/gateway-api/8.3.1/
```

---

## Build

```bash
cd ignition-module
mvn package -DskipTests
```

This one command builds `common`, `headless-renderer` and `gateway` in dependency order. The
`ignition-module/pom.xml` reactor includes `../headless-renderer`. Avoid `mvn clean` if an IDE has
the build output open, because a file lock can make it fail. In that case, delete the
`target/classes` folders by hand.

The gateway deliberately has no compile dependency on `headless-renderer`. Any accidental import of
engine classes (`mochadoom.*`, `doom.*`, `com.doom.headless.*`, …) into gateway code fails the build.

---

## Package the .modl

Both scripts stage files outside the workspace to avoid file-lock conflicts with open editors.
Output goes to `ignition-module/build/`.

**Windows** — run from PowerShell:

```powershell
powershell -ExecutionPolicy Bypass -File ignition-module\package-doom-modl.ps1
```

**Linux / Mac** — requires `zip` (`xmllint` is used if present):

```bash
bash ignition-module/package-doom-modl.sh
```

By default this builds for **Ignition 8.3** (requires 8.3.0 or newer). For **Ignition 2027.x**, add
`-Target 2027` (PowerShell) or `--target 2027` (bash). This is **experimental**: it is the same
compiled code with 2027.2.0 declared as the minimum version, written to
`doom-perspective-vX.Y.Z-ign2027.modl`. It runs on 2027.2.0-beta2 but hasn't been compiled against
the 2027 SDK yet. Each line's gateways refuse the other line's build, so they can't be mixed up.

---

## Signing the module (recommended)

**Signing is the recommended way to deploy this module.** A signed module installs on a gateway
with its default security settings. Otherwise the gateway has to be set to allow unsigned modules,
which applies to every module installed on it, not just this one. Keep unsigned builds for local
development.

Signing uses Inductive Automation's
[module-signer](https://github.com/inductiveautomation/module-signer) with a self-signed RSA
code-signing certificate.

Create the certificate once. This needs `keytool` from the JDK; the bash version also needs `openssl`.

```powershell
powershell -ExecutionPolicy Bypass -File ignition-module\signing\new-signing-cert.ps1   # Windows
```
```bash
bash ignition-module/signing/new-signing-cert.sh                                       # Linux/Mac
```

This writes `ignition-module/signing/doom-signing.p12` (the private key) and `doom-signing.p7b`
(the certificate chain). Both are gitignored. Back up the `.p12` and keep it private. If you
replace it, every gateway has to accept the new certificate again. The script prints the
certificate's SHA-256 fingerprint. Publish it with releases so gateway admins can check that the
certificate they accept is yours.

Once the keystore exists, the packaging scripts also write
`doom-perspective-vX.Y.Z-signed.modl`, next to the unsigned file. To sign an existing `.modl`
directly, run `signing/sign-modl.ps1 -ModlIn <file>` or `signing/sign-modl.sh <file>`.

- **Password**: set `DOOM_SIGNING_PASSWORD`, or enter it when prompted.
- **Skip signing**: pass `-NoSign` / `--no-sign` to the packaging scripts.
- **Different certificate**: set `DOOM_SIGNING_KEYSTORE`, `DOOM_SIGNING_CHAIN` and `DOOM_SIGNING_ALIAS`.
  This works with any RSA code-signing keystore (`.p12`/`.pfx`/`.jks`) and its `.p7b` chain,
  including a certificate issued by a CA.
- **First run**: the module-signer jar is downloaded from IA's public Nexus into
  `ignition-module/signing/tools/` and checked against a pinned SHA-1.

When a module's certificate is self-signed rather than issued by a trusted CA, the gateway asks
the admin to review and accept the certificate when the module is installed.

---

## Testing Ignition versions

`scripts/test-ignition-versions.sh` creates a throwaway Docker gateway for each Ignition version,
installs the module, and runs a smoke test: it starts a single-player game, waits for a rendered
frame, and loads the sound list. It prints a PASS/FAIL table. The current results are in the
README's [Compatibility](../README.md#compatibility) section.

Build and package the module first:

```bash
scripts/test-ignition-versions.sh 8.3.0 8.3.10                 # PASS/FAIL table; gateways removed afterwards
scripts/test-ignition-versions.sh --keep-passing 8.3.10        # leave passing gateways running to play on
scripts/test-ignition-versions.sh 2027.2.0-beta2               # uses the -ign2027 build automatically
scripts/test-ignition-versions.sh --min-version 8.2.0 <tag>    # probe a release below the declared minimum
```

Each version gets its own gateway on ports 18100 and up. 2027.x tags automatically use the
`-ign2027` build (package it with `-Target 2027` first). A signed `.modl` (`--modl`) installs
without allowing unsigned modules, so the test checks signing too. Windows users can run it from
Git Bash. Run the script with `--help` for all options.
