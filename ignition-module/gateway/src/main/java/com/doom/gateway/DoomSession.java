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

package com.doom.gateway;

import com.doom.common.ISessionRunner;
import com.doom.common.MusicStateDTO;
import com.doom.common.SoundEventDTO;
import com.doom.common.TikcmdBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URL;
import java.util.Set;

/**
 * Wrapper for a single browser session's engine instance.
 *
 * Constructor stores params only — ClassLoader and SessionRunner are
 * created lazily in start(), called by SessionManager on first connect.
 */
public class DoomSession {

    private static final Logger logger = LoggerFactory.getLogger("DOOM.Session");

    private final String sessionId;
    private final String wadPath;
    private final URL    rendererJarUrl;

    private SessionClassLoader classLoader;
    private ISessionRunner     runner;
    private volatile boolean   started = false;
    private volatile boolean   stopped = false;

    /** Milliseconds since epoch of last client activity — used by the reaper. */
    private volatile long lastActivityMs = System.currentTimeMillis();

    /** Milliseconds since epoch when this session was created. */
    private final long startTimeMs = System.currentTimeMillis();

    /**
     * Skill level set by the console "skill N" command; used as default for the next warp.
     * HMP (skill 2) is the DOOM default.
     */
    private volatile int pendingSkill = 2;

    /** Frame encoding format: "jpeg" (default) or "png". Set before start(). */
    private volatile String frameFormat = "jpeg";

    /** Optional PWAD/mod file path passed to the engine via -file. Null = no PWAD. */
    private volatile String pwadPath = null;

    public void setFrameFormat(String format) { this.frameFormat = format; }

    public void setPwadPath(String path) { this.pwadPath = path; }

    /**
     * Latest music state drained from the runner but not yet served to the browser.
     * Non-null means the browser has not yet fetched the MIDI for this track change.
     * Cleared by {@link #takeMusicState()}.
     */
    private volatile MusicStateDTO cachedMusic = null;

    /**
     * Last music state received from the runner, never cleared.
     * Used by serveMusicStatus to report playing=true even after cachedMusic is consumed
     * (i.e., after the browser has fetched /music/midi but the track is still playing).
     */
    private volatile MusicStateDTO lastKnownMusic = null;

    /** Stores session params. No class loading, no engine init — that's all deferred to start(). */
    public DoomSession(String sessionId, String wadPath, URL rendererJarUrl) {
        this.sessionId       = sessionId;
        this.wadPath         = wadPath;
        this.rendererJarUrl  = rendererJarUrl;
        logger.info("DoomSession created: sessionId={} wad={}", sessionId, wadPath);
    }

    /** Creates the child CL, loads SessionRunner, starts engine. Blocks until init completes. */
    public synchronized void start(int startEp, int startMap, int skill) throws Exception {
        if (started) {
            logger.warn("DoomSession.start() called on already-started session: {}", sessionId);
            return;
        }
        logger.info("DoomSession.start() — session={}, ep={}, map={}, skill={}",
            sessionId, startEp, startMap, skill);

        try {
            // 1. Create child ClassLoader here (deferred from constructor)
            classLoader = new SessionClassLoader(
                new URL[]{ rendererJarUrl },
                DoomSession.class.getClassLoader()
            );
            logger.info("SessionClassLoader created for session {} — CL={}",
                sessionId, classLoader);

            // 2. Load SessionRunner via reflection (keeps gateway.jar free of engine imports)
            Class<?> runnerClass = classLoader.loadClass("com.doom.headless.SessionRunner");
            logger.info("SessionRunner class loaded — CL={}", runnerClass.getClassLoader());

            runner = (ISessionRunner) runnerClass.getDeclaredConstructor().newInstance();
            logger.info("SessionRunner instantiated for session {}", sessionId);
            runner.setFrameFormat(frameFormat);
            runner.setPwadPath(pwadPath);

            // 3. Start the engine (blocks until init completes or 30s timeout)
            String configDir = "/tmp/doom-session-" + sessionId + "/";
            runner.start(wadPath, configDir, 640, 400, startEp, startMap, skill);

            started = true;
            logger.info("DoomSession fully started: session={}", sessionId);

        } catch (Exception e) {
            logger.error("DoomSession.start() FAILED for session {}: {}", sessionId, e.getMessage(), e);
            // Best-effort cleanup so resources aren't leaked
            if (runner != null) {
                try { runner.stop(); } catch (Exception ex) {
                    logger.warn("Error stopping runner during cleanup", ex);
                }
            }
            if (classLoader != null) {
                try { classLoader.close(); } catch (Exception ex) {
                    logger.warn("Error closing classloader during cleanup", ex);
                }
            }
            runner      = null;
            classLoader = null;
            throw e;
        }
    }

    /**
     * Stops this session and releases all resources.
     * Safe to call multiple times.
     */
    public void stop() {
        if (stopped) return;
        stopped = true;
        logger.info("DoomSession.stop() — session={}", sessionId);

        if (runner != null) {
            try { runner.stop(); }
            catch (Exception e) { logger.warn("Error stopping SessionRunner for {}", sessionId, e); }
            runner = null;
        }
        if (classLoader != null) {
            try { classLoader.close(); }
            catch (Exception e) { logger.warn("Error closing ClassLoader for {}", sessionId, e); }
            classLoader = null;
        }
        logger.info("DoomSession stopped: session={}", sessionId);
    }

    /** Updates the last-activity timestamp (called on every client request). */
    public void touch() {
        lastActivityMs = System.currentTimeMillis();
    }

    /** Returns true if this session has been idle longer than the given threshold. */
    public boolean isIdle(long thresholdMs) {
        return System.currentTimeMillis() - lastActivityMs > thresholdMs;
    }

    // ── Pending skill (console "skill N" command) ─────────────────────────────

    public int  getPendingSkill()      { return pendingSkill; }
    public void setPendingSkill(int s) { pendingSkill = s; }

    // ── Music state cache (two-step fetch: status poll → MIDI download) ───────

    /**
     * Polls the runner for a new music state snapshot. If a new snapshot arrives it is
     * cached; otherwise the existing cached snapshot is kept. Returns the current cached
     * state (non-null ↔ browser should fetch /music/midi). Thread-safe via volatile.
     */
    public MusicStateDTO peekMusicState() {
        if (runner == null) return cachedMusic;
        try {
            MusicStateDTO latest = runner.pollMusicState();
            if (latest != null) {
                cachedMusic = latest;
                lastKnownMusic = latest;
            }
        } catch (Exception e) {
            logger.warn("peekMusicState error for session {}", sessionId, e);
        }
        return cachedMusic;
    }

    /**
     * Returns the cached music state and clears it (drain semantics).
     * Called when the browser downloads /music/midi. After this call,
     * peekMusicState() returns null until the next track change.
     * lastKnownMusic is NOT cleared — it continues to reflect playing state.
     */
    public MusicStateDTO takeMusicState() {
        MusicStateDTO m = cachedMusic;
        cachedMusic = null;
        return m;
    }

    /** Last music state received, never cleared. Used to report playing=true after MIDI consumed. */
    public MusicStateDTO getLastKnownMusic() { return lastKnownMusic; }

    // ── Delegation to ISessionRunner ─────────────────────────────────────────

    public boolean isRunning() {
        return started && !stopped && runner != null && runner.isRunning();
    }

    public String getCurrentFrame() {
        if (runner == null) return "";
        try { return runner.getCurrentFrame(); }
        catch (Exception e) { logger.warn("getCurrentFrame error: {}", e.getMessage()); return ""; }
    }

    public byte[] getCurrentFrameBytes() {
        if (runner == null) return null;
        try { return runner.getCurrentFrameBytes(); }
        catch (Exception e) { return null; }
    }

    public String getFrameContentType() {
        if (runner == null) return "image/jpeg";
        try { return runner.getFrameContentType(); }
        catch (Exception e) { return "image/jpeg"; }
    }

    public int getFrameSeq() {
        if (runner == null) return 0;
        try { return runner.getFrameSeq(); }
        catch (Exception e) { return 0; }
    }

    public byte[] waitForNextFrame(int afterSeq, long timeoutMs) throws InterruptedException {
        if (runner == null) return null;
        return runner.waitForNextFrame(afterSeq, timeoutMs);
    }

    public int getFrameNumber() {
        if (runner == null) return 0;
        try { return runner.getFrameNumber(); }
        catch (Exception e) { return 0; }
    }

    public void updatePressedKeys(Set<Integer> keys) {
        if (runner == null) return;
        try { runner.updatePressedKeys(keys); }
        catch (Exception e) { logger.warn("updatePressedKeys error: {}", e.getMessage()); }
    }

    // ── P2P multiplayer delegation (Phase 6.3) ───────────────────────────────

    /**
     * Starts this session as one player slot in a P2P deathmatch match.
     * Each player gets their own independent engine; they share {@code bus}
     * for lockstep tikcmd exchange.
     *
     * @param startEp   Episode (0 = main menu)
     * @param startMap  Map number
     * @param skill     Skill level (0=ITYTD … 4=NM)
     * @param numPlayers Total player count in the match
     * @param bus       Shared TikcmdBus (created by MatchSession)
     * @param playerSlot This engine's slot index (0 = host, 1..N-1 = guests)
     */
    public synchronized void startP2P(int startEp, int startMap, int skill,
                                      int numPlayers, TikcmdBus bus, int playerSlot) throws Exception {
        if (started) {
            logger.warn("startP2P() called on already-started session: {}", sessionId);
            return;
        }
        logger.info("DoomSession.startP2P() — session={}, slot={} of {}, ep={}, map={}, skill={}",
            sessionId, playerSlot, numPlayers, startEp, startMap, skill);

        try {
            classLoader = new SessionClassLoader(
                new URL[]{ rendererJarUrl },
                DoomSession.class.getClassLoader()
            );
            Class<?> runnerClass = classLoader.loadClass("com.doom.headless.SessionRunner");
            runner = (ISessionRunner) runnerClass.getDeclaredConstructor().newInstance();
            logger.info("SessionRunner instantiated for P2P session {}, slot={}", sessionId, playerSlot);
            runner.setFrameFormat(frameFormat);
            runner.setPwadPath(pwadPath);

            String configDir = "/tmp/doom-session-" + sessionId + "/";
            runner.startP2P(wadPath, configDir, 640, 400, startEp, startMap, skill,
                            numPlayers, bus, playerSlot);

            started = true;
            logger.info("DoomSession P2P started: session={}, slot={}", sessionId, playerSlot);

        } catch (Exception e) {
            logger.error("DoomSession.startP2P() FAILED for {}, slot={}: {}",
                sessionId, playerSlot, e.getMessage(), e);
            if (runner != null) { try { runner.stop(); } catch (Exception ex) {
                logger.warn("Error stopping runner during P2P cleanup", ex); } }
            if (classLoader != null) { try { classLoader.close(); } catch (Exception ex) {
                logger.warn("Error closing classloader during P2P cleanup", ex); } }
            runner = null; classLoader = null;
            throw e;
        }
    }

    public void sendCheatKeys(String cheat) {
        if (runner == null) return;
        try { runner.sendCheatKeys(cheat); }
        catch (Exception e) { logger.warn("sendCheatKeys error: {}", e.getMessage()); }
    }

    public void warp(int episode, int map, int skill) {
        if (runner == null) return;
        try { runner.warp(episode, map, skill); }
        catch (Exception e) { logger.warn("warp error: {}", e.getMessage()); }
    }

    public boolean isCommercial() {
        if (runner == null) return false;
        try { return runner.isCommercial(); }
        catch (Exception e) { return false; }
    }

    public SoundEventDTO[] pollSoundEvents() {
        if (runner == null) return new SoundEventDTO[0];
        try { return runner.pollSoundEvents(); }
        catch (Exception e) { return new SoundEventDTO[0]; }
    }

    public MusicStateDTO pollMusicState() {
        if (runner == null) return null;
        try { return runner.pollMusicState(); }
        catch (Exception e) { return null; }
    }

    public String[] discoverSounds() {
        if (runner == null) return new String[0];
        try { return runner.discoverSounds(); }
        catch (Exception e) { logger.warn("discoverSounds error", e); return new String[0]; }
    }

    public byte[] extractSoundAsWav(String soundName) {
        if (runner == null) return null;
        try { return runner.extractSoundAsWav(soundName); }
        catch (Exception e) { logger.warn("extractSoundAsWav error: {}", soundName, e); return null; }
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public String getSessionId()    { return sessionId; }
    public String getWadPath()      { return wadPath; }
    public long   getLastActivity() { return lastActivityMs; }
    public long   getStartTimeMs()  { return startTimeMs; }
    public long   getIdleMs()       { return System.currentTimeMillis() - lastActivityMs; }
    public long   getUptimeMs()     { return System.currentTimeMillis() - startTimeMs; }

    /** Returns just the WAD stem (e.g. "DOOM1") extracted from the full wadPath. */
    public String getWadName() {
        if (wadPath == null) return "?";
        String name = new java.io.File(wadPath).getName();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
