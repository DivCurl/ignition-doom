/*
 * Copyright (C) 2026 Mike Dillmann (DivCurl)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.doom.common;

import java.util.Set;

/**
 * Interface between gateway.jar (parent CL) and headless-renderer.jar (child CL).
 *
 * All method signatures must use only java.lang/java.util types or com.doom.common types.
 * Both sides of the boundary load com.doom.common from the parent CL, so the reflected
 * cast to ISessionRunner works without a ClassCastException.
 *
 * Don't add methods that reference engine types (doom/mochadoom/g/s/v/w/i packages) —
 * the child CL would load them and the cast at the boundary would fail.
 */
public interface ISessionRunner {

    // ── Lifecycle ────────────────────────────────────────────────────────────

    /** Starts the DOOM engine. Blocks up to 30s for init; throws on timeout or failure. */
    void start(String wadPath, String configDir, int width, int height,
               int startEp, int startMap, int skill) throws Exception;

    /** Stops the engine and releases all resources. */
    void stop();

    /** True if the engine is up and running. */
    boolean isRunning();

    // ── Frame output ─────────────────────────────────────────────────────────

    /** "jpeg" (default) or "png" — set before start(). */
    default void setFrameFormat(String format) {}

    /** Optional PWAD/mod file path to load via -file. Null means no PWAD. Set before start(). */
    default void setPwadPath(String path) {}

    /** Current frame as a data URI, or "" before first render. */
    String getCurrentFrame();

    /** Total frames rendered so far. */
    int getFrameNumber();

    // ── Input ────────────────────────────────────────────────────────────────

    /** Updates the pressed key set. Servlet thread writes, game loop reads at 35 Hz. */
    void updatePressedKeys(Set<Integer> pressedKeys);

    // ── Game control ─────────────────────────────────────────────────────────

    /** Sends a cheat string as key events. Handles the W/S bypass for IDSPISPOPD. */
    void sendCheatKeys(String cheat);

    /** Warps to ep/map at the given skill. Thread-safe. */
    void warp(int episode, int map, int skill);

    /**
     * True if the loaded WAD is a commercial title (DOOM2/Final DOOM).
     * Affects map addressing (MAP01-MAP32) and cheat codes (IDCLIP vs IDSPISPOPD).
     */
    boolean isCommercial();

    // ── Audio — drain-and-clear ───────────────────────────────────────────────

    /** Drains and returns pending sound events. Never null. */
    SoundEventDTO[] pollSoundEvents();

    /** Per-slot audio drain for multiplayer. Default delegates to pollSoundEvents(). */
    default SoundEventDTO[] pollSoundEvents(int slot) {
        return pollSoundEvents();
    }

    /** Returns and clears the latest music state. Null if unchanged since last call. */
    MusicStateDTO pollMusicState();

    // ── Sound cache helpers (called once per WAD by SessionManager) ───────────

    /** Returns all DS* lump names from the WAD. Call after start(). */
    String[] discoverSounds();

    /** Extracts a named sound as a WAV byte array. Null if not found. Call after start(). */
    byte[] extractSoundAsWav(String soundName);

    // ── Multiplayer ───────────────────────────────────────────────────────────

    /** Shared-engine multiplayer start (legacy). Default falls back to start(). */
    default void startMultiplayer(String wadPath, String configDir, int width, int height,
                                  int startEp, int startMap, int skill,
                                  int numPlayers) throws Exception {
        start(wadPath, configDir, width, height, startEp, startMap, skill);
    }

    /** P2P deathmatch — independent engine per player, TikcmdBus for lockstep sync. Default falls back to start(). */
    default void startP2P(String wadPath, String configDir, int width, int height,
                          int startEp, int startMap, int skill,
                          int numPlayers, TikcmdBus bus, int playerSlot) throws Exception {
        start(wadPath, configDir, width, height, startEp, startMap, skill);
    }

    /** Per-slot key update. Default routes slot 0 to updatePressedKeys(). */
    default void updatePressedKeys(int slot, Set<Integer> pressedKeys) {
        if (slot == 0) updatePressedKeys(pressedKeys);
    }

    /** Returns the current frame for the given player slot. Defaults to getCurrentFrame(). */
    default String getPlayerFrame(int slot) {
        return getCurrentFrame();
    }

    /** Number of players in this session (1 = single-player). */
    default int getNumPlayers() { return 1; }

    /** True if this session was started in multiplayer mode. */
    default boolean isMultiplayer() { return false; }
}
