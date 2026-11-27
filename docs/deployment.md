# Deploying and Running Ignition DOOM

[← Back to the README](../README.md)

How to install the module on a gateway, where its files go, how access control works, and how much
the gateway needs. To build the module yourself, see [Building](building.md).

- [Requirements](#requirements)
- [Option A — Docker](#option-a--docker-recommended-for-local-development)
- [Option B — Existing Ignition gateway](#option-b--existing-ignition-gateway)
- [Play](#play)
- [Deployment File Locations](#deployment-file-locations)
- [Key URLs](#key-urls)
- [Access Control](#access-control)
- [Resource Requirements](#resource-requirements)

---

## Requirements

- Ignition gateway 8.3.0 or newer, self-hosted or Docker (tested 8.3.0 – 8.3.11-rc1; see
  [Compatibility](../README.md#compatibility))
- A signed `.modl` (**recommended**, see [Signing the module](building.md#signing-the-module-recommended)),
  or, for development, a gateway set to allow unsigned modules (the
  `-Dignition.allowunsignedmodules=true` JVM option)
- Docker + image `inductiveautomation/ignition:8.3.1` *(only for the container-based setup)*
- Enough CPU for the games you expect to run; see [Resource Requirements](#resource-requirements)

---

## Option A — Docker (recommended for local development)

Run once to create and configure the container (pick any container name):

```bat
scripts\setup-ignition-container.bat <container-name> [--allow-unsigned]     # Windows
scripts/setup-ignition-container.sh <container-name> [--allow-unsigned]      # Linux/Mac
```

> **Warning:** if a container with that name already exists, setup **deletes and recreates it**,
> and all gateway data in it is lost. The script asks you to type the name to confirm; `-y` skips
> the prompt. Set `IGNITION_PORT` to publish the gateway on a host port other than 8088.

By default the new gateway does **not** accept unsigned modules, so install the `-signed.modl`
([Signing the module](building.md#signing-the-module-recommended)). For quick development with unsigned builds,
add `--allow-unsigned`. You can switch an existing container later; this restarts it:

```bat
scripts\set-unsigned-modules.bat <container-name> on|off|status     # Windows
scripts/set-unsigned-modules.sh <container-name> on|off|status      # Linux/Mac
```

To add or refresh assets (WADs, soundfonts, branding, PWADs) in an already-running container:

```bat
scripts\deploy-assets.bat <container-name> [-w] [-s] [-b] [-p] [--clean]    # Windows
scripts/deploy-assets.sh <container-name> [-w] [-s] [-b] [-p] [--clean]     # Linux/Mac
```

With no flags, every category is deployed. `-w` deploys WADs, `-s` soundfonts, `-b` branding and
`-p` PWADs. `--clean` deletes the selected categories in the container before redeploying them.

Setup creates a local Ignition Docker container (using image `inductiveautomation/ignition:8.3.1`).
It calls `deploy-assets` automatically, and you can re-run `deploy-assets` on its own at any time.
All scripts can be run from any directory.

---

## Option B — Existing Ignition gateway

1. Use the `-signed.modl` build (**recommended**, see
   [Signing the module](building.md#signing-the-module-recommended)). For development only, you can instead
   allow unsigned modules: in `<ignition-install>/data/ignition.conf`, add
   `wrapper.java.additional.N=-Dignition.allowunsignedmodules=true`, where `N` is the next unused
   number after the existing `wrapper.java.additional` entries, then restart the gateway.
2. Copy the IWADs into `<ignition-install>/user-lib/doom/iwads/`, and any PWADs into
   `<ignition-install>/user-lib/doom/pwads/` (see [Deployment File Locations](#deployment-file-locations))
3. Copy the soundfont JS files into `<ignition-install>/user-lib/doom/soundfonts/`
4. Install the module: **Gateway Config → Modules → Install or Upgrade Module**, select the `.modl`

---

## Play

Open the dashboard at `/system/doom` on your gateway, for example
`http://localhost:8088/system/doom` for a local Docker install. On the first visit it asks you to set
a master password ([Access Control](#access-control)). From the dashboard, start a single-player
game or create a deathmatch, and other players join from the Active Matches list or with the link
the match creator shares.

---

## Deployment File Locations

All paths are relative to the gateway's install folder, `<ignition-install>`. The module asks the
gateway for its `user-lib` folder, so the same layout works wherever Ignition is installed:

- **Docker** (`setup-ignition-container`) and Linux: `/usr/local/bin/ignition/`
- **Windows**: typically `C:\Program Files\Inductive Automation\Ignition\`

| Path under `<ignition-install>` | Contents |
|---|---|
| `user-lib/doom/iwads/` | Game IWADs: DOOM1, DOOM, DOOM2, TNT, PLUTONIA (`.WAD`) |
| `user-lib/doom/pwads/` | Optional PWADs and mods (`.wad`, `.pk3`, `.pk7`) |
| `user-lib/doom/soundfonts/` | FluidR3_GM instrument JS files (music) |
| `user-lib/doom/branding/` | Optional logo images shown on the dashboard (up to 3) |
| `user-lib/doom/doom-settings.properties` | Module settings: master password hash, play password, max games. Created by the module |
| `data/ignition.conf` | `-Dignition.allowunsignedmodules=true` JVM option, only for unsigned development builds |

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
| `/system/doom/?settings=1` | Dashboard with the Admin Settings panel open (the old `/system/doom/admin` URL redirects here) |

When a play password is required, the `/play` and `/match/create` links only work after the
password has been entered on the dashboard.

---

## Access Control

**First visit.** The dashboard asks you to set a **master password**. To prove you own the gateway,
you also need the **setup code** from the gateway log:

```
DOOM setup code: XXXX-XXXX-XXXX  (enter it at /system/doom to set the master password)
```

In Docker, run `docker logs <container> | grep "setup code"`. A new code is printed every time the
module starts and after each use. You can choose **Not now** and set the password later from
**[Admin Settings]**. If you forget the master password, use **Forgot it?** on the login prompt to
reset it with the current setup code.

**Admin Settings** (the **[Admin Settings]** link on the dashboard, master password required):

- **Play password.** Off by default, so anyone who can reach the dashboard can play. Turn it on to
  make players enter a shared password before starting a single-player game or creating a match.
  Type your own, or click **Generate** for a strong one (16 characters such as
  `K7QM-2XRA-9DPF-HW4T`). Players can type it in any case, with or without dashes. Changing the
  password, or turning it on, asks players for the new one; games already running keep going. The
  owner never has to enter it.
- **Joining a match never needs the play password**, only the match ID. Players join with the
  link the match creator shares, or with the **Join** button in the dashboard's Active Matches list.
  Anyone who can open the dashboard can therefore join any match that still has free slots.
- **Max concurrent games**, which is saved across restarts.
- **Change master password.** Other logged-in browsers are logged out.
- When you're logged in, **End** buttons appear next to running games and matches.

**How it's stored.** Settings are saved in `user-lib/doom/doom-settings.properties`. The master
password is stored as a salted PBKDF2 hash, and the file is readable only by the gateway's user.
After repeated wrong passwords or setup codes, further attempts are refused for a few minutes.
Owner logins don't survive a gateway restart, so you log in again after one.

---

## Resource Requirements

Every running game is a full DOOM engine inside the gateway's JVM. In deathmatch each player runs
their own engine, so a 4-player match counts as 4 games. With no games running, the module's
overhead is negligible.

**Per game** (measured on Ignition 8.3.10 in Docker on a 32-core host):

| Resource | Per game | Notes |
|---|---|---|
| CPU | 1.2–1.7 cores | Rendering and frame encoding at 35 frames per second. Nearly the same with no viewer attached |
| Live heap | ~16 MB | Budget about 50 MB of `-Xmx` per game to leave room for garbage collection |
| Gateway process memory | ~18 MB | On top of the gateway's own footprint |
| Threads | ~3 | Game loop, frame encoder, and their helpers |
| Network, per viewer | ~2.5 MB/s (PNG, ~20 Mbit/s) or ~1.75 MB/s (JPEG, ~14 Mbit/s) | 35 frames/s; PNG frames ~75 KB, JPEG ~50 KB. The dashboard uses PNG |
| Web server threads | 1 per viewer | Each viewer's frame stream holds one of Ignition's web server threads (300 by default) |
| Start-up | ~0.3 s to start, first frame in ~1 s | |

**Totals for common sizes** (the 16-game row is extrapolated from the per-game figures):

| Games | CPU cores | Heap to budget | Network (PNG) | Network (JPEG) | Web server threads |
|---|---|---|---|---|---|
| 1 | 1.2–1.7 | ~50 MB | ~20 Mbit/s | ~14 Mbit/s | 1 |
| 2 | 2.4–3.4 | ~100 MB | ~40 Mbit/s | ~28 Mbit/s | 2 |
| 4 | 4.8–6.8 | ~200 MB | ~80 Mbit/s | ~56 Mbit/s | 4 |
| 8 | 10–14 | ~400 MB | ~160 Mbit/s | ~110 Mbit/s | 8 |
| 16 | 19–27 | ~800 MB | ~320 Mbit/s | ~220 Mbit/s | 16 |

**Impact on the gateway:**

- **CPU is the limit.** Each game needs more than a full core, all the time. Set
  **Max concurrent games** in [Admin Settings](#access-control) (default 8) so the games plus the
  gateway's own work (tags, Perspective sessions, historian) fit on the host's cores.
- **Unattended games keep running.** A game keeps rendering when nobody is watching, for example
  after a browser tab is closed, until idle cleanup ends it after 10 minutes. The owner can end
  games at once with the **End** buttons on the dashboard.
- **Bandwidth is significant over slow links.** One viewer uses about 20 Mbit/s with PNG, which
  matters on VPN or site-to-site connections.
- **Memory is modest.** Even 16 games fit in an extra 1 GB of heap.

These figures come from games idling at the start of E1M1. Busy scenes cost somewhat more CPU. In
the test, frame delivery fell from 35 to about 27 frames per second per viewer once 4 or more
viewers streamed at the same time. This may be a limit of the single test client rather than of the
gateway.
