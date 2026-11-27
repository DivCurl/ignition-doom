# DOOM for Ignition Perspective

Running DOOM natively inside Inductive Automation Ignition Perspective, with server-side rendering on the Gateway JVM and browser display via a self-contained HTTP servlet pipeline. Full audio — sound effects and music — with no external dependencies.

This project sprouted suddenly and quite humbly into life while pondering over general low-level rendering capabilities in Ignition Perspective, and quickly became an obsession-driven side project while trying (and failing) to decompress from the daily grind — as more dirt was shoveled away to expose something... delightfully hellish. As a 90s kid, the original Doom holds a very special place in my heart for exposing my fragile but curious brain to the magic of computers. Later, I came to admire the craftsmanship, creativity, and raw engineering talent of the id Software team of that era with equal amounts of awe and respect. I therefore must dedicate each hour spent on this to the original id Software team, without whom none of this would be possible.

---

## Contents

- [Features](#features) · [Roadmap](#roadmap) · [Quick Start](#quick-start) ·
  [Compatibility](#compatibility)
- [Documentation](#documentation): [Building](docs/building.md) · [Deployment](docs/deployment.md) ·
  [Architecture](docs/architecture.md)
- [References](#references) · [License](#license) · [Credits](#credits)

---

## Features

- **Single-player** with full rendering, keyboard and mouse input, sound effects and MIDI music, for
  DOOM, DOOM II, Final DOOM and the shareware episode.
- **Mouselook.** WASD plus mouse aim with GZDoom-style freelook (y-shearing, Heretic / Crispy Doom
  look range), on by default. Mouselook, autoaim, a crosshair, sensitivity and invert Y are set on
  the start page; in a deathmatch, mouselook and autoaim are match rules chosen by the host. The
  mouse wheel changes weapons. A Controls page (`/system/doom/controls`) lists every key, and a
  Fullscreen button keeps browser shortcuts such as Ctrl+W in the game (Chrome and Edge, over
  HTTPS or localhost).
- **Deathmatch** with 2–4 players per match. Each player runs their own engine, kept in lockstep peer
  to peer, and other players join with the match ID.
- **PWADs and mods.** Vanilla-compatible PWADs work, with automatic IWAD detection, warp to the first
  map, and skill selection. See [WADs and mods](#wads-and-mods) for the limits.
- **Gateway-friendly operation**: a cap on concurrent games, automatic cleanup of idle games, an
  optional play password, and an Admin Settings panel protected by a master password.
- **Self-hosted.** The gateway serves every asset itself, so no external network access is needed.
- **Ignition 8.3.0 and later**, tested through 8.3.11-rc1 on Linux gateways. Asset folders follow
  the gateway's own install location, so Windows gateways should work too, but haven't been tested
  yet. An experimental build targets Ignition 2027.x. See [Compatibility](#compatibility).

## Roadmap

| Item | Status | Notes |
|---|---|---|
| Deathmatch hardening: high-latency netcode, spawn edge cases | In final testing | Substantially complete |
| Community WAD verification | In progress | Several vanilla-compatible PWADs verified |
| Ignition 2027.x support | Experimental | Runs on 2027.2.0-beta2; compiling against the 2027 SDK is still to do |
| In-game "Aim & Mouse" menu, so mouselook and the crosshair can change mid-game | Planned | The options are set on the start page for now |
| Deathmatch drop-in rejoin: let a player who left (or a new one) join a match under way | Idea | Players are fixed once a match starts, as in vanilla and the source ports; rejoining needs the new engine to replay the match so far |
| MIDI quality: SoundFont selection closer to the original score | Planned | |
| Ignition 8.1 support | On hold | Needs `javax` servlets and a Jetty 9.4 WebSocket rewrite |

---

## Quick Start

The fastest way to try it is a local Docker gateway. Each step links to the full instructions.

1. **Get the assets** (soundfonts; the shareware DOOM1.WAD is already in the repo):
   `scripts/get-assets.sh` or `scripts\get-assets.bat`.
2. **Build and package the module**, ideally signed. This needs a one-time Ignition SDK setup.
   See [Building](docs/building.md).
3. **Create a gateway and install the module.** `scripts/setup-ignition-container.sh <name>` (or
   `.bat`) creates an Ignition 8.3.1 container and copies in the WADs and soundfonts. Then install
   the `-signed.modl` under **Gateway Config → Modules**. For an existing gateway, see
   [Deployment](docs/deployment.md).
4. **Play.** Open `http://localhost:8088/system/doom`. On the first visit, set a master password
   using the setup code from the gateway log (`docker logs <name> | grep "setup code"`). See
   [Access Control](docs/deployment.md#access-control).

---

## Compatibility

### Ignition versions

Tested on Linux gateways in Docker. The automated smoke test
(`scripts/test-ignition-versions.sh`) installs the module, starts a single-player game, waits for a
rendered frame, and loads the sound list. The WebSocket handshake and gameplay were also checked by
hand on each version.

| Ignition | Status | Notes |
|---|---|---|
| 8.3.0 | Supported | Declared minimum of the 8.3 build |
| 8.3.1 | Supported | |
| 8.3.9 | Supported | |
| 8.3.10 | Supported | |
| 8.3.11-rc1 | Supported | Release candidate |
| 2027.2.0-beta2 | Experimental | Use the 2027 build (`-Target 2027`); runs on Java 25. The 8.3 build is refused: *"requires Ignition 8.3.0 and is not compatible with Ignition 2027.2.0"* |
| 8.1, 7.x | Not supported | 8.3 moved to Jetty 12 and Jakarta servlets. 8.1 uses Jetty 9.4 and `javax` servlets; 7.x runs on Java 8 |

The smoke test can be repeated for any version; see [Testing Ignition versions](docs/building.md#testing-ignition-versions).

### Browsers

| Browser | Status | Notes |
|---|---|---|
| Chrome | Tested | Windows. Fullscreen keeps Ctrl+W and Esc in the game over HTTPS or localhost |
| Edge | Tested | Windows. Same as Chrome |
| Firefox, Safari | Untested | Expected to work; Fullscreen there is plain full screen without the keyboard capture |

In full screen, hold Esc to leave it. When the game ends, the page leaves full screen and releases
the mouse and keyboard by itself.

### WADs and mods

The engine is Mocha DOOM, a Java port of id Software's original source with some prBoom
extensions. A WAD made for the original engine, the kind that runs in Chocolate Doom, works here.
Several community PWADs have been verified.

| Supported | Not supported |
|---|---|
| IWADs: DOOM (shareware and registered), Ultimate DOOM, DOOM II, TNT: Evilution, The Plutonia Experiment | DEHACKED patches. They are ignored, so modified monsters, weapons or text revert to stock behaviour |
| Vanilla-format PWADs: maps, textures, sounds, music | Hexen-format and UDMF maps |
| ZDoom extended nodes (ZNODES) and DeepBSP nodes, for large maps | ACS scripting, DECORATE, MAPINFO |

---

## Documentation

| Document | Contents |
|---|---|
| [Building](docs/building.md) | Prerequisites, assets, Ignition SDK setup, build, packaging (8.3 and experimental 2027 targets), signing, version testing |
| [Deployment](docs/deployment.md) | Docker and existing-gateway installs, file locations, URLs, access control (master and play passwords), resource requirements |
| [Architecture](docs/architecture.md) | Request flow, technology stack, design decisions, repository structure |

---

## References

- [Mocha DOOM](https://github.com/GoodSign2017/mochadoom) — Java DOOM port used as base engine
- [Chocolate Doom](https://github.com/chocolate-doom/chocolate-doom) — Architecture reference
- [DOOM Wiki](https://doomwiki.org) — WAD format, MUS format, DMX sound format specs
- [gleitz/midi-js-soundfonts](https://github.com/gleitz/midi-js-soundfonts) — FluidR3_GM melodic instruments
- [paulrosen/midi-js-soundfonts](https://github.com/paulrosen/midi-js-soundfonts) — FluidR3_GM + percussion
- [Ignition SDK documentation](https://www.sdk-docs.inductiveautomation.com/)

---

## License

GNU General Public License v3 or later. See [LICENSE](LICENSE) for the full text.

This project incorporates [Mocha DOOM](https://github.com/GoodSign2017/mochadoom) (GPL v3) and the
[DOOM source code](https://github.com/id-Software/DOOM) (GPL v2) by id Software. DOOM1.WAD
(shareware) is included under id Software's shareware terms and is not covered by the GPL. Commercial WAD files must be supplied by the user.
FluidR3_GM soundfont: Creative Commons Attribution 3.0. See [CREDITS.md](CREDITS.md) for full
attribution.

This project is not affiliated with or endorsed by Inductive Automation.
"Ignition" is a trademark of Inductive Automation, Inc.

## Credits

See [CREDITS.md](CREDITS.md) for full third-party licenses and attribution.

**Development**: Mike Dillmann ([DivCurl](https://github.com/DivCurl))
