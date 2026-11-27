# Architecture

[← Back to the README](../README.md)

How the module is put together: the request flow, the engine isolation, the multiplayer design,
and where everything lives in the repository.

- [Architecture Overview](#architecture-overview)
- [Technology Stack](#technology-stack)
- [Key Design Decisions](#key-design-decisions)
- [Repository Structure](#repository-structure)

---

## Architecture Overview

```
Browser
  └─ GET /system/doom/frame/stream   ← MJPEG push stream (server pushes PNG or JPEG frames as rendered)
  └─ WS  /system/doom-ws             ← primary: input + sound/music events (bidirectional)
  └─ POST /system/doom/input         ← fallback: keyboard state (JS keyCodes)
  └─ GET /system/doom/events/stream  ← fallback: SSE sound/music events

Gateway (Ignition Module)
  └─ DoomInputServlet                ← all HTTP routing
  └─ DoomWebSocketServlet            ← WebSocket upgrade handler
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
| Game engine | Mocha DOOM: a pure-Java port of id Software's original DOOM source, with some prBoom extensions |
| Rendering | Palette-indexed software renderer, 640×400, frames as PNG (default) or JPEG (`?format=jpeg`) |
| Transport | HTTP servlet — MJPEG push stream for frames; WebSocket (SSE/POST fallback) for input and events |
| Multiplayer | P2P tikcmd-sync via TikcmdBus ring buffer |
| Sound effects | DMX sounds from WAD, Web Audio API, 44100 Hz 16-bit |
| Music | MUS→MIDI server-side, SoundFont playback browser-side |
| SoundFonts | FluidR3_GM (gleitz fork for melodic, paulrosen fork for percussion) |
| Platform | Ignition Gateway Module (Ignition 8.3.0+; tested 8.3.0 – 8.3.11-rc1; experimental 2027.x build) |

---

## Key Design Decisions

**ClassLoader isolation** — each browser session gets a child-first `SessionClassLoader` that loads `headless-renderer.jar`. This isolates all Mocha DOOM statics (palette, WAD cache, game state) per session with no singleton collision across tabs.

**Interface bridge** — `ISessionRunner` lives in `common.jar` (parent classloader). `SessionRunner` in `headless-renderer.jar` (child classloader) implements it. All cross-boundary calls go through this interface; DTOs are plain Java types to avoid classloader boundary issues.

**Session cap** — `SessionManager` caps the number of running engines across single-player games and deathmatch players combined (default 8, set from 1 to 64 under **Max concurrent games** in Admin Settings and saved across restarts). A request over the cap returns HTTP 503.

**Multiplayer (P2P tikcmd-sync)** — each player gets their own independent DOOM engine (`DoomSession`) running on its own thread. Engines share a `TikcmdBus` ring buffer (`common.jar`, parent classloader) and exchange 8-byte serialized tikcmds each game tic via `P2PNetDriver`. DOOM's deterministic simulation keeps all engines in lockstep. Each browser naturally gets the correct POV, HUD, sound, and music. Engines start in parallel background threads once all player slots are filled; browsers poll `/match/status` during the lobby.

**MJPEG push streaming** — frames served via `GET /system/doom/frame/stream?session=UUID` as a `multipart/x-mixed-replace` stream. The server holds the response open and pushes each encoded frame (PNG by default, or JPEG) as it's rendered, decoupling frame rate from browser round-trip time. No memory tags, no polling.

**WebSocket transport** — input keycodes and audio events (sound + music) are exchanged over a single bidirectional WebSocket (`/system/doom-ws`), eliminating SSE/POST round-trip overhead. The browser falls back to SSE + POST if the WebSocket handshake fails.

**Self-hosted audio** — all assets served from the Ignition gateway. `soundfont-player.min.js` bundled in `gateway.jar`. Instrument JS files on the gateway filesystem under `user-lib/doom/soundfonts/`. Zero external network dependencies — works air-gapped.

**VerifyError fix** — Java 17 bytecode verifier rejects nested ternaries with generic array casts. `SoftwareGraphicsSystem.colormap()` uses explicit if-else chains to avoid this.

---

## Repository Structure

```
ignition-doom/
├── README.md                        Overview, features, roadmap, quick start, compatibility
├── docs/                            building.md, deployment.md, architecture.md (this file)
├── LICENSE                          GNU General Public License v3
├── .gitattributes                   Forces LF for .sh and CRLF for .bat scripts on every platform
├── CREDITS.md                       Third-party licenses and attribution
├── scripts/                         Setup and helper scripts (each as .bat + .sh; run from any directory)
│   ├── get-assets                   First-run downloader — soundfonts (restores DOOM1.WAD from git if missing)
│   ├── setup-ignition-container     One-time Docker container creation and configuration
│   ├── deploy-assets                Copy WADs, soundfonts, branding, and PWADs to a running container
│   ├── set-unsigned-modules         Turn "allow unsigned modules" on/off in an existing container
│   ├── test-ignition-versions.sh    Deploy + smoke-test the module on throwaway gateways of several Ignition versions
│   └── lib/unsigned-modules.sh      Shared ignition.conf editor used by the scripts above
├── headless-renderer/               Mocha DOOM + headless rendering pipeline (JAR)
│   └── src/main/java/
│       ├── com/doom/headless/       SessionRunner, FrameEncoder, DoomSoundExtractor
│       ├── doom/                    P2PNetDriver (package-private field access)
│       ├── s/                       HeadlessMusicDriver, HeadlessSoundDriver
│       ├── v/renderers/             BufferedRenderer, HeadlessIndexedRenderer
│       └── mochadoom/               Engine entry point (headless + singleton)
├── ignition-module/                 Ignition Gateway Module; its pom.xml is the build reactor (also builds ../headless-renderer)
│   ├── common/                      TikcmdBus + ISessionRunner (parent classloader)
│   ├── gateway/                     SessionManager, DoomSession, DoomInputServlet, ...
│   │   └── src/main/java/com/doom/gateway/
│   │       ├── AccessControl.java   Master/play passwords, setup code, persisted settings
│   │       ├── DoomLandingPage.java Session landing page HTML generator
│   │       └── ...
│   ├── module.xml                   Module descriptor
│   ├── package-doom-modl.ps1        Windows: packages JARs into .modl (PowerShell)
│   ├── package-doom-modl.sh         Linux/Mac: packages JARs into .modl (bash)
│   ├── signing/                     Optional module signing (new-signing-cert / sign-modl, .ps1 + .sh)
│   ├── build/                       Deployable .modl artifacts (gitignored)
│   └── install-sdk.bat              Extract the Ignition SDK JARs from a gateway container into Maven (Windows) (see docs/building.md)
└── assets/                          Local game assets (most gitignored)
    ├── iwads/                       IWAD files: DOOM1.WAD committed; DOOM.WAD, DOOM2.WAD, etc. gitignored
    ├── pwads/                       Optional PWAD/mod files (gitignored)
    ├── branding/                    Optional dashboard logos (gitignored)
    └── soundfonts/                  FluidR3_GM JS files (gitignored, fetched by get-assets)
```
