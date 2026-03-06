# DOOM for Ignition Perspective

Running DOOM natively inside Inductive Automation Ignition Perspective, with server-side rendering on the Gateway JVM and browser display via a self-contained HTTP servlet pipeline. Full audio — sound effects and music — with no external dependencies.

**Current Version:** v0.9.2

---

## Features

| Feature | Status |
|---|---|
| Single-player (all 5 WADs) — render, input, sound, music | ✅ Working |
| Session isolation — up to 4 concurrent independent engines | ✅ Working |
| WAD selection via URL param (`?wad=DOOM2`) | ✅ Working |
| Browser emulated in-game console (`~` key) — warp, cheat, skill commands | ✅ Working |
| P2P deathmatch — independent engine per player, lockstep sync | ✅ Working |
| Per-player POV, HUD, positional audio, and music | ✅ Working |
| Deathmatch respawn — geometric validation prevents void-space spawns | ✅ Working |
| Level transitions — no screen wipe corruption, P2P lockstep continues | ✅ Working |
| Disconnect resilience — remaining players continue on disconnect | ✅ Working |
| Admin endpoint — session list + kill button + token auth | ✅ Working |
| Self-hosted assets — zero external network dependencies | ✅ Working |
| Game starts full-screen by default | ✅ Working |
| Session landing page — browser UI for starting single-player and creating/joining deathmatch lobbies | ✅ Working |
| PWAD / mod file support — load custom WADs alongside the base IWAD | ✅ Working |
| PWAD auto-warp — game starts directly in MAP01/E1M1 without menu navigation | ✅ Working |
| PWAD IWAD auto-detection — landing page detects and applies the required base WAD | ✅ Working |
| Skill level selection — choosable per session on the landing page when a PWAD is loaded | ✅ Working |
| End Game cleanup — browser shows "GAME ENDED" overlay when game is exited via in-game menu | ✅ Working |

## Planned

| Feature | Status |
|---|---|
| Higher-fidelity MIDI playback — closer match to the classic DOOM score via improved SoundFont selection or tuning | 🔲 Planned |
| Deathmatch hardening — netcode performance under high latency, spawn location edge cases | 🔲 Planned |
| Linux testing — validate build, deployment, and gameplay on a Linux-hosted Ignition gateway | 🔲 Planned |
| Ignition version compatibility — validate installation, module lifecycle, and gameplay across additional gateway versions beyond the currently tested 8.3.1 | 🔲 Planned |
| PWAD compatibility — broader playtesting across community WADs and mod files to identify and resolve engine compatibility issues | 🔲 Planned |

---

## Architecture Overview

```
Browser
  └─ GET /system/doom/frame          ← PNG frame (640×400, ~30–60 KB)
  └─ POST /system/doom/input         ← keyboard state (JS keyCodes)
  └─ GET /system/doom/sounds/events  ← positional sound events (JSON)
  └─ GET /system/doom/music/status   ← MUS→MIDI track state

Gateway (Ignition Module)
  └─ DoomInputServlet                ← all HTTP routing
  └─ SessionManager                  ← lifecycle, WAD discovery, session cap
  └─ DoomSession                     ← per-session child ClassLoader + engine wrapper
  └─ MatchSession                    ← N independent engines + TikcmdBus
  └─ TikcmdBus                       ← ring-buffer tikcmd exchange for P2P lockstep

Engine (per session, child ClassLoader)
  └─ Mocha DOOM                      ← pure-Java DOOM engine (35 tics/sec, headless)
  └─ P2PNetDriver                    ← exchanges 8-byte tikcmds with peer engines
  └─ HeadlessSoundDriver             ← origin-tracked sound events
  └─ HeadlessMusicDriver             ← MUS→MIDI conversion
  └─ FrameEncoder                    ← palette→RGB→PNG/JPEG, dedicated encoder thread
```

**ClassLoader isolation** — each browser session gets a child-first `SessionClassLoader` that loads `headless-renderer.jar`, isolating all Mocha DOOM statics per session.

**P2P multiplayer** — each player gets their own independent DOOM engine. Engines exchange 8-byte serialized tikcmds via `TikcmdBus` (a ring buffer in `common.jar`). DOOM's deterministic simulation keeps all engines in lockstep — identical tikcmd sequences produce identical game state.

---

## Technology Stack

| Component | Technology |
|---|---|
| Game engine | Mocha DOOM (pure Java port of Chocolate Doom) |
| Rendering | Palette-indexed software renderer, 640×400, PNG (JPEG optional via `?format=jpeg`) |
| Transport | HTTP servlet (direct frame serving, no WebSocket, no tags) |
| Multiplayer | P2P tikcmd-sync via TikcmdBus ring buffer |
| Sound effects | DMX sounds from WAD, Web Audio API, 44100 Hz 16-bit |
| Music | MUS→MIDI server-side, SoundFont playback browser-side |
| SoundFonts | FluidR3_GM (gleitz fork for melodic, paulrosen fork for percussion) |
| Platform | Ignition Gateway Module (Java 17; tested on 8.3.1) |

---

## Prerequisites

- Java 11+, Maven 3.8+ *(module compiles to Java 11 bytecode; Java 17 required at runtime by Ignition)*
- Ignition gateway, self-hosted or Docker (tested on 8.3.1)
- **Unsigned module installation must be enabled** on the target gateway (`allowunsignedmodules=true` in `ignition.conf`) — the module is not signed by Inductive Automation
- Docker + image `inductiveautomation/ignition:8.3.1` *(only for the container-based setup)*
- Internet access for first-run asset downloads (DOOM1.WAD, soundfonts)

---

## Quick Start

**1. Download assets (first run only)**

```bat
get-assets.bat        # Windows
./get-assets.sh       # Linux/Mac
```

Downloads DOOM1.WAD (shareware, ~4 MB) and FluidR3_GM soundfonts (~99 MB) into `assets/`.

**2. Build the module**

```bash
# Install parent POM and common module to local Maven repo
cd ignition-module
mvn install -N
mvn install -pl common

# Build headless-renderer JAR
# Note: use 'install' not 'clean install' — mvn clean may fail if an IDE
# has a file lock on target/test-classes. Delete target/classes manually
# first if you suspect stale IDE-compiled files are inflating the JAR.
cd ../headless-renderer
mvn install -DskipTests

# Build gateway + module JARs
cd ../ignition-module
mvn package -DskipTests
```

The Ignition SDK is fetched automatically from Inductive Automation's public Maven repository.
If that's unavailable, see [ignition-module/INSTALL-SDK.md](ignition-module/INSTALL-SDK.md).

**3. Package the .modl file**

Both scripts stage files outside the workspace to avoid file-lock conflicts with open editors.
Output goes to `ignition-module/build/`.

**Windows** — run from PowerShell:

```powershell
powershell -ExecutionPolicy Bypass -File ignition-module\package-doom-modl.ps1
```

**Linux / Mac** — requires `zip` and `xmllint` (or `grep`/`sed` as fallback):

```bash
bash ignition-module/package-doom-modl.sh
```

**4. Deploy to Ignition**

**Option A — Docker (recommended for local development)**

Run once to create and configure the container:

```bat
setup-ignition-container.bat        # Windows
./setup-ignition-container.sh       # Linux/Mac
```

To add or refresh assets (WADs, soundfonts, branding) in an already-running container:

```bat
deploy-assets.bat                   # Windows
./deploy-assets.sh                  # Linux/Mac
```

Sets up a local Ignition Docker container (using image `inductiveautomation/ignition:8.3.1`) and enables unsigned modules. Asset deployment is handled by `deploy-assets`, which is called automatically during setup and can be re-run independently at any time.

**Option B — Existing Ignition gateway**

1. Enable unsigned modules: add `allowunsignedmodules=true` to `ignition.conf` in your Ignition
   data directory, then restart the gateway.
2. Copy WAD files into `<ignition-install>/user-lib/doom/`
3. Copy soundfont JS files into `<ignition-install>/user-lib/doom/soundfonts/`
4. Install the module: **Gateway Config → Modules → Install or Upgrade Module**, select the `.modl`

**5. Play**

Navigate to the following paths on your Ignition gateway (e.g. `http://localhost:8088` for a
local Docker install):

- Single-player: `/system/doom/play`
- Deathmatch: `/system/doom/match/create?wad=DOOM2&players=2`

---

## Deployment File Locations

**Docker container** (paths inside the container, as used by `setup-ignition-container.*`):

| Path | Contents |
|---|---|
| `/usr/local/bin/ignition/user-lib/doom/*.WAD` | Game IWADs (DOOM1, DOOM, DOOM2, TNT, PLUTONIA) |
| `/usr/local/bin/ignition/user-lib/doom/soundfonts/` | FluidR3_GM instrument JS files |
| `/usr/local/bin/ignition/data/ignition.conf` | `allowunsignedmodules=true` flag |

**Existing gateway** (adjust root path for your OS and install location):

| Path | Contents |
|---|---|
| `<ignition-install>/user-lib/doom/*.WAD` | Game IWADs |
| `<ignition-install>/user-lib/doom/soundfonts/` | FluidR3_GM instrument JS files |
| `<ignition-install>/data/ignition.conf` | `allowunsignedmodules=true` flag |

Typical install roots: `/usr/local/bin/ignition/` (Linux), `C:\Program Files\Inductive Automation\Ignition\` (Windows).

---

## Key URLs

All paths are relative to your Ignition gateway root (e.g. `http://localhost:8088`).

| Path | Description |
|---|---|
| `/system/doom` | Session landing page — start games, view active sessions |
| `/system/doom/play` | Single-player (default WAD) |
| `/system/doom/play?wad=DOOM2` | Single-player with WAD selection |
| `/system/doom/play?wad=DOOM2&pwad=mymod.wad&skill=3` | Single-player with PWAD and skill level (0–4) |
| `/system/doom/match/create?wad=DOOM2&players=2` | Create a 2-player deathmatch |
| `/system/doom/health` | JSON health + session/match status |
| `/system/doom/admin?token=TOKEN` | Session admin (token printed in gateway log on startup) |

---

## Key Design Decisions

**ClassLoader isolation** — each browser session gets a child-first `SessionClassLoader` that loads `headless-renderer.jar`. This isolates all Mocha DOOM statics (palette, WAD cache, game state) per session with no singleton collision across tabs.

**Interface bridge** — `ISessionRunner` lives in `common.jar` (parent classloader). `SessionRunner` in `headless-renderer.jar` (child classloader) implements it. All cross-boundary calls go through this interface; DTOs are plain Java types to avoid classloader boundary issues.

**Session cap** — `SessionManager` enforces MAX_SESSIONS=4 across single-player sessions and multiplayer matches combined. A 5th engine request returns HTTP 503.

**Multiplayer (P2P tikcmd-sync)** — each player gets their own independent DOOM engine (`DoomSession`) running on its own thread. Engines share a `TikcmdBus` ring buffer (`common.jar`, parent classloader) and exchange 8-byte serialized tikcmds each game tic via `P2PNetDriver`. DOOM's deterministic simulation keeps all engines in lockstep. Each browser naturally gets the correct POV, HUD, sound, and music. Engines start in parallel background threads once all player slots are filled; browsers poll `/match/status` during the lobby.

**Direct frame serving** — frames served via `GET /system/doom/frame?session=UUID` from an `AtomicReference<String>`. No WebSocket, no memory tags, no timer scripts.

**Self-hosted audio** — all assets served from the Ignition gateway. `soundfont-player.min.js` bundled in `gateway.jar`. Instrument JS files on the gateway filesystem under `user-lib/doom/soundfonts/`. Zero external network dependencies — works air-gapped.

**VerifyError fix** — Java 17 bytecode verifier rejects nested ternaries with generic array casts. `SoftwareGraphicsSystem.colormap()` uses explicit if-else chains to avoid this.

---

## Repository Structure

```
ignition-doom/
├── README.md                        This file
├── LICENSE                          GNU General Public License v3
├── CREDITS.md                       Third-party licenses and attribution
├── get-assets.bat / get-assets.sh   First-run downloader — DOOM1.WAD + soundfonts
├── setup-ignition-container.bat/.sh One-time Docker container creation and configuration
├── deploy-assets.bat / deploy-assets.sh  Copy WADs, soundfonts, and branding to a running container
├── headless-renderer/               Mocha DOOM + headless rendering pipeline (JAR)
│   └── src/main/java/
│       ├── com/doom/headless/       SessionRunner, FrameEncoder, DoomSoundExtractor
│       ├── doom/                    P2PNetDriver (package-private field access)
│       ├── s/                       HeadlessMusicDriver, HeadlessSoundDriver
│       ├── v/renderers/             BufferedRenderer, HeadlessIndexedRenderer
│       └── mochadoom/               Engine entry point (headless + singleton)
├── ignition-module/                 Ignition Gateway Module (Maven multi-module)
│   ├── common/                      TikcmdBus + ISessionRunner (parent classloader)
│   ├── gateway/                     SessionManager, DoomSession, DoomInputServlet, ...
│   │   └── src/main/java/com/doom/gateway/
│   │       ├── DoomLandingPage.java Session landing page HTML generator
│   │       └── ...
│   ├── module.xml                   Module descriptor
│   ├── package-doom-modl.ps1        Windows: packages JARs into .modl (PowerShell)
│   ├── package-doom-modl.sh         Linux/Mac: packages JARs into .modl (bash)
│   ├── build/                       Deployable .modl artifacts (gitignored)
│   ├── install-sdk.bat              Fallback: extract SDK JARs from Docker container
│   └── INSTALL-SDK.md               SDK installation guide
└── assets/                          Local game assets (most gitignored)
    ├── iwads/                       IWAD files: DOOM1.WAD committed; DOOM.WAD, DOOM2.WAD, etc. gitignored
    ├── pwads/                       Optional PWAD/mod files (gitignored)
    └── soundfonts/                  FluidR3_GM JS files (gitignored, fetched by get-assets)
```

---

## References

- [Mocha DOOM](https://github.com/GoodSign2017/mochadoom) — Java DOOM port used as base engine
- [Chocolate Doom](https://github.com/chocolate-doom/chocolate-doom) — Architecture reference
- [DOOM Wiki](https://doomwiki.org) — WAD format, MUS format, DMX sound format specs
- [gleitz/midi-js-soundfonts](https://github.com/gleitz/midi-js-soundfonts) — FluidR3_GM melodic instruments
- [paulrosen/midi-js-soundfonts](https://github.com/paulrosen/midi-js-soundfonts) — FluidR3_GM + percussion
- [Ignition SDK](https://docs.inductiveautomation.com/docs/8.1/appendix/sdk)

---

## License

GNU General Public License v3 or later. See [LICENSE](LICENSE) for the full text.

This project incorporates [Mocha DOOM](https://github.com/GoodSign2017/mochadoom) (GPL v3) and the
[DOOM source code](https://github.com/id-Software/DOOM) (GPL v2) by id Software. DOOM1.WAD
(shareware) is freely redistributable. Commercial WAD files must be supplied by the user.
FluidR3_GM soundfont: Creative Commons Attribution 3.0. See [CREDITS.md](CREDITS.md) for full
attribution.

This project is not affiliated with or endorsed by Inductive Automation.
"Ignition" is a trademark of Inductive Automation, Inc.

## Credits

See [CREDITS.md](CREDITS.md) for full third-party licenses and attribution.

**Development**: Mike Dillmann ([DivCurl](https://github.com/DivCurl))
