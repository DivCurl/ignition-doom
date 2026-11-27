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

import com.doom.common.GameplayOptions;
import com.doom.common.TikcmdBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A multiplayer match — N independent DoomSession instances sharing a TikcmdBus.
 *
 * Created by SessionManager with no engines running. Engines don't start until all
 * player slots are filled via assignSlot(). Browsers poll /match/status until
 * isFullyRunning() is true, then the lobby overlay drops and the game begins.
 */
public class MatchSession {

    private static final Logger logger = LoggerFactory.getLogger("DOOM.MatchSession");

    private final String matchId;
    private final int    numPlayers;
    private final String wadPath;
    private final URL    rendererJarUrl;

    // Stored at start() time for deferred engine creation
    private volatile int    startEp     = 0;
    private volatile int    startMap    = 0;
    private volatile int    skill       = 2;
    private volatile String frameFormat = "jpeg";
    private volatile String pwadPath    = null;

    public void setFrameFormat(String format) { this.frameFormat = format; }

    public void setPwadPath(String path) { this.pwadPath = path; }

    /**
     * Match rules chosen by the host (ZDoom "allow freelook" / "allow autoaim"
     * style). They change the simulation, so every engine gets the same values.
     */
    private volatile GameplayOptions gameplayOptions = GameplayOptions.DEFAULTS;

    public void setGameplayOptions(GameplayOptions options) {
        if (options != null) this.gameplayOptions = options;
    }

    public GameplayOptions getGameplayOptions() { return gameplayOptions; }

    /** Each player's crosshair choice (display only), kept until their engine exists. */
    private final ConcurrentHashMap<String, Boolean> crosshairByBrowser = new ConcurrentHashMap<>();

    // N independent engines — one per player slot (null until startEngines() fires)
    private volatile DoomSession[]  engines = null;
    private volatile TikcmdBus      bus     = null;

    /** browser session UUID → player slot (0..numPlayers-1) */
    private final ConcurrentHashMap<String, Integer> slotByBrowser = new ConcurrentHashMap<>();
    /** player slot → browser session UUID */
    private final String[] browserBySlot;

    private volatile boolean enginesStarted = false;
    private volatile boolean stopped        = false;
    private final long createTimeMs = System.currentTimeMillis();

    // ── Player departure ──────────────────────────────────────────────────────
    /**
     * How long a player's browser may be gone (tab closed, network lost) before they
     * are taken out of the match. Long enough to survive a page reload.
     */
    static final long LEAVE_GRACE_MS = 15_000;

    /** slot → its engine has reached the running state at least once */
    private final boolean[] slotWasRunning;
    /** slot → departure already reported to the TikcmdBus */
    private final boolean[] slotLeft;
    /** browser session → open WebSocket count (a reload briefly has two) */
    private final ConcurrentHashMap<String, Integer> openSockets = new ConcurrentHashMap<>();
    /** browser session → last HTTP sign of life (status poll, input POST) */
    private final ConcurrentHashMap<String, Long> lastSeenMs = new ConcurrentHashMap<>();

    public MatchSession(String matchId, String wadPath, int numPlayers, URL rendererJarUrl) {
        this.matchId        = matchId;
        this.wadPath        = wadPath;
        this.numPlayers     = Math.max(2, Math.min(4, numPlayers));
        this.rendererJarUrl = rendererJarUrl;
        this.browserBySlot  = new String[this.numPlayers];
        this.slotWasRunning = new boolean[this.numPlayers];
        this.slotLeft       = new boolean[this.numPlayers];
        logger.info("MatchSession created: matchId={}, wad={}, slots={}", matchId, wadPath, numPlayers);
    }

    /** A player's WebSocket opened. */
    public void socketOpened(String browserSessionId) {
        if (browserSessionId == null) return;
        openSockets.merge(browserSessionId, 1, Integer::sum);
        touchPlayer(browserSessionId);
    }

    /** A player's WebSocket closed; they count as gone after {@link #LEAVE_GRACE_MS} without a new one. */
    public void socketClosed(String browserSessionId) {
        if (browserSessionId == null) return;
        openSockets.computeIfPresent(browserSessionId, (k, n) -> n > 1 ? n - 1 : null);
        touchPlayer(browserSessionId);
    }

    /** Any sign of life from a player's page (status poll, input POST). */
    public void touchPlayer(String browserSessionId) {
        if (browserSessionId != null && slotByBrowser.containsKey(browserSessionId)) {
            lastSeenMs.put(browserSessionId, System.currentTimeMillis());
        }
    }

    private boolean browserPresent(int slot, long now) {
        String b = browserBySlot[slot];
        if (b == null) return false;
        if (openSockets.getOrDefault(b, 0) > 0) return true;
        Long seen = lastSeenMs.get(b);
        return seen != null && now - seen < LEAVE_GRACE_MS;
    }

    /** True if the player in this slot has left the match. */
    public boolean hasLeft(int slot) {
        synchronized (slotLeft) {
            return slot >= 0 && slot < slotLeft.length && slotLeft[slot];
        }
    }

    /** Number of players who have left the match. */
    public int getLeftCount() {
        int n = 0;
        synchronized (slotLeft) {
            for (boolean l : slotLeft) if (l) n++;
        }
        return n;
    }

    /**
     * True while this player's own engine is running. Streams to a player depend on
     * this, not on {@link #isFullyRunning()}: after someone else leaves, the match is
     * no longer "fully" running but the remaining players are still playing.
     */
    public boolean isPlayerRunning(int slot) {
        if (stopped) return false;
        DoomSession e = getSlotSessionByIndex(slot);
        return e != null && e.isRunning();
    }

    /** Number of this match's engines currently running (one per player still in the game). */
    public int countRunningEngines() {
        if (stopped || engines == null) return 0;
        int n = 0;
        for (DoomSession e : engines) {
            if (e != null && e.isRunning()) n++;
        }
        return n;
    }

    /** Any running engine of this match, or null. */
    public DoomSession anyRunningEngine() {
        DoomSession[] eng = engines;
        if (stopped || eng == null) return null;
        for (DoomSession e : eng) {
            if (e != null && e.isRunning()) return e;
        }
        return null;
    }

    /** True while the match is under way: engines started, not stopped, someone still playing. */
    public boolean isLive() {
        if (stopped || engines == null) return false;
        for (DoomSession e : engines) {
            if (e != null && e.isRunning()) return true;
        }
        return false;
    }

    /**
     * Watches each player's engine and browser. A player leaves when their engine
     * stops (End Game, Quit, error) or their browser has been gone for
     * {@link #LEAVE_GRACE_MS}. The departure goes to the TikcmdBus, which lets every
     * remaining engine drop that player at the same tic instead of waiting forever.
     */
    private void monitorPlayers() {
        while (!stopped) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                return;
            }
            DoomSession[] eng = engines;
            if (eng == null) continue;
            long now = System.currentTimeMillis();
            for (int s = 0; s < eng.length; s++) {
                DoomSession e = eng[s];
                if (e == null || slotLeft[s]) continue;
                if (e.isRunning()) {
                    if (!slotWasRunning[s]) {
                        slotWasRunning[s] = true;
                        // Engines start in parallel and may take a while: count presence from now.
                        String b = browserBySlot[s];
                        if (b != null) lastSeenMs.put(b, now);
                    } else if (!browserPresent(s, now)) {
                        playerLeft(s, "browser gone for " + (LEAVE_GRACE_MS / 1000) + " s");
                    }
                } else if (slotWasRunning[s]) {
                    playerLeft(s, "engine stopped (End Game, Quit or error)");
                }
            }
            if (getLeftCount() >= numPlayers) {
                logger.info("Match {}: every player has left — stopping", matchId);
                stop();
            }
        }
    }

    private void playerLeft(int slot, String reason) {
        synchronized (slotLeft) {
            if (slotLeft[slot]) return;
            slotLeft[slot] = true;
        }
        logger.info("Match {}: player {} left ({})", matchId, slot + 1, reason);
        TikcmdBus b = bus;
        if (b != null) b.playerDisconnected(slot);
        DoomSession e = engines != null ? engines[slot] : null;
        if (e != null) {
            try { e.stop(); }
            catch (Exception ex) { logger.warn("Error stopping engine of departed player {} in {}", slot + 1, matchId, ex); }
        }
    }

    /** Stores game params. Engines don't start until all slots fill via assignSlot(). */
    public synchronized void start(int startEp, int startMap, int skill) {
        this.startEp  = startEp;
        this.startMap = startMap;
        this.skill    = skill;
        logger.info("MatchSession.start() params stored: matchId={}, ep={}, map={}, skill={}",
            matchId, startEp, startMap, skill);
    }

    /**
     * Assigns the given browser session to the next available player slot.
     * Returns the assigned slot number, or -1 if the match is full.
     * Idempotent: if the browser session already has a slot, returns it.
     * When the last slot is filled, fires startEngines() in background threads.
     */
    public synchronized int assignSlot(String browserSessionId) {
        // Already assigned?
        Integer existing = slotByBrowser.get(browserSessionId);
        if (existing != null) return existing;

        // Find first open slot
        for (int i = 0; i < numPlayers; i++) {
            if (browserBySlot[i] == null) {
                browserBySlot[i] = browserSessionId;
                slotByBrowser.put(browserSessionId, i);
                logger.info("Match {} — assigned slot {} to browser session {}", matchId, i, browserSessionId);
                // If all slots are now filled, start all engines in parallel
                if (isFull() && !enginesStarted) {
                    enginesStarted = true;
                    startEngines();
                }
                return i;
            }
        }
        return -1; // full
    }

    /**
     * Starts N independent DOOM engines in parallel, one per player slot.
     * Each engine is started on its own background thread (startP2P() blocks ~5-30s per engine).
     * Called once when the last browser session joins the match.
     */
    private void startEngines() {
        logger.info("MatchSession.startEngines() — matchId={}, players={}", matchId, numPlayers);
        TikcmdBus newBus = new TikcmdBus(numPlayers);
        DoomSession[] newEngines = new DoomSession[numPlayers];
        this.bus     = newBus;
        this.engines = newEngines;

        for (int slot = 0; slot < numPlayers; slot++) {
            final int s = slot;
            Thread t = new Thread(() -> {
                String engineId = matchId + "-slot" + s;
                DoomSession engine = new DoomSession(engineId, wadPath, rendererJarUrl);
                engine.setFrameFormat(frameFormat);
                engine.setPwadPath(pwadPath);
                engine.setGameplayOptions(gameplayOptions);
                engine.setCrosshair(crosshairForSlot(s));
                newEngines[s] = engine;
                try {
                    engine.startP2P(startEp, startMap, skill, numPlayers, newBus, s);
                    logger.info("MatchSession engine ready: matchId={}, slot={}", matchId, s);
                } catch (Exception e) {
                    logger.error("MatchSession engine FAILED: matchId={}, slot={}: {}", matchId, s, e.getMessage(), e);
                }
            }, "DOOM-Match-" + matchId + "-Slot" + s);
            t.setDaemon(true);
            t.start();
        }

        Thread monitor = new Thread(this::monitorPlayers, "DOOM-Match-" + matchId + "-Players");
        monitor.setDaemon(true);
        monitor.start();
    }

    /** Returns true if all N engines exist and are running. */
    public boolean isFullyRunning() {
        if (stopped || engines == null) return false;
        for (DoomSession e : engines) {
            if (e == null || !e.isRunning()) return false;
        }
        return true;
    }

    /**
     * Stops all engines and closes the TikcmdBus.
     * Safe to call multiple times.
     */
    public void stop() {
        if (stopped) return;
        stopped = true;
        logger.info("MatchSession.stop() — matchId={}", matchId);
        if (bus != null) {
            try { bus.close(); }
            catch (Exception e) { logger.warn("Error closing TikcmdBus for {}", matchId, e); }
        }
        DoomSession[] eng = engines;
        if (eng != null) {
            for (int i = 0; i < eng.length; i++) {
                if (eng[i] != null) {
                    try { eng[i].stop(); }
                    catch (Exception e) {
                        logger.warn("Error stopping engine slot {} for match {}", i, matchId, e);
                    }
                }
            }
        }
        logger.info("MatchSession stopped: matchId={}", matchId);
    }

    /** @deprecated Prefer isFullyRunning() for game-state checks. Used by reaper. */
    public boolean isRunning() {
        return isFullyRunning();
    }

    /** True if this match has not been explicitly stopped (even during lobby). */
    public boolean isAlive() {
        return !stopped;
    }

    // ── Per-slot accessors ────────────────────────────────────────────────────

    /** Returns the DoomSession for the given browser session ID, or null if not assigned/started. */
    public DoomSession getSlotSession(String browserSessionId) {
        int slot = getSlot(browserSessionId);
        if (slot < 0) return null;
        DoomSession[] eng = engines;
        if (eng == null || slot >= eng.length) return null;
        return eng[slot];
    }

    /** Returns the DoomSession for the given slot index, or null if not yet started. */
    public DoomSession getSlotSessionByIndex(int slot) {
        DoomSession[] eng = engines;
        if (eng == null || slot < 0 || slot >= eng.length) return null;
        return eng[slot];
    }

    /** Returns the frame sentinel for the given player slot ("", "READY", or "GAME_ENDED"). */
    public String getPlayerFrame(int slot) {
        DoomSession s = getSlotSessionByIndex(slot);
        return s != null ? s.getCurrentFrame() : "";
    }

    /** Returns raw frame bytes for the given player slot, or null if not yet available. */
    public byte[] getPlayerFrameBytes(int slot) {
        DoomSession s = getSlotSessionByIndex(slot);
        return s != null ? s.getCurrentFrameBytes() : null;
    }

    /** Returns the MIME type for the given player slot's frames. */
    public String getPlayerFrameContentType(int slot) {
        DoomSession s = getSlotSessionByIndex(slot);
        return s != null ? s.getFrameContentType() : "image/jpeg";
    }

    /** Current encoded frame sequence for the given slot (for use with waitForNextFrame). */
    public int getPlayerFrameSeq(int slot) {
        DoomSession s = getSlotSessionByIndex(slot);
        return s != null ? s.getFrameSeq() : 0;
    }

    /** Blocks until a new encoded frame is available for the given slot. Returns null on timeout or session end. */
    public byte[] waitForNextFrame(int slot, int afterSeq, long timeoutMs) throws InterruptedException {
        DoomSession s = getSlotSessionByIndex(slot);
        return s != null ? s.waitForNextFrame(afterSeq, timeoutMs) : null;
    }

    /** Updates pressed keys for the browser session (routed to that slot's engine). */
    public void updatePressedKeys(String browserSessionId, Set<Integer> keys) {
        DoomSession s = getSlotSession(browserSessionId);
        if (s != null) s.updatePressedKeys(keys);
    }

    /** Routes mouse input to the browser session's engine. */
    public void addMouseInput(String browserSessionId, int dx, int dy, int buttons, int wheel) {
        DoomSession s = getSlotSession(browserSessionId);
        if (s != null) s.addMouseInput(dx, dy, buttons, wheel);
    }

    /** Sets a player's crosshair; remembered if their engine hasn't started yet. */
    public void setCrosshair(String browserSessionId, boolean on) {
        if (browserSessionId == null) return;
        crosshairByBrowser.put(browserSessionId, on);
        DoomSession s = getSlotSession(browserSessionId);
        if (s != null) s.setCrosshair(on);
    }

    private boolean crosshairForSlot(int slot) {
        for (java.util.Map.Entry<String, Integer> e : slotByBrowser.entrySet()) {
            if (e.getValue() != null && e.getValue() == slot) {
                return Boolean.TRUE.equals(crosshairByBrowser.get(e.getKey()));
            }
        }
        return false;
    }

    /** Returns the player slot for a browser session, or -1 if not assigned. */
    public int getSlot(String browserSessionId) {
        Integer slot = slotByBrowser.get(browserSessionId);
        return slot != null ? slot : -1;
    }

    /** Returns true if all player slots have been assigned. */
    public boolean isFull() {
        for (String s : browserBySlot) {
            if (s == null) return false;
        }
        return true;
    }

    /** Returns the number of currently assigned (joined) slots. */
    public int getJoinedCount() {
        int count = 0;
        for (String s : browserBySlot) {
            if (s != null) count++;
        }
        return count;
    }

    // ── Delegated helpers ─────────────────────────────────────────────────────

    /**
     * A representative engine (sound cache, WAD checks): a running one if any, since
     * player 1 may have left; otherwise engine[0]. Null until engines start.
     */
    DoomSession getEngineSession() {
        DoomSession running = anyRunningEngine();
        if (running != null) return running;
        DoomSession[] eng = engines;
        return eng != null && eng.length > 0 ? eng[0] : null;
    }

    public boolean isCommercial() {
        DoomSession s = getEngineSession();
        return s != null && s.isCommercial();
    }

    public void touch() {
        DoomSession[] eng = engines;
        if (eng != null) {
            for (DoomSession s : eng) {
                if (s != null) s.touch();
            }
        }
    }

    public boolean isIdle(long threshMs) {
        // Consider idle only if all running engines are idle
        DoomSession[] eng = engines;
        if (eng == null) {
            // Still in lobby — idle based on create time
            return System.currentTimeMillis() - createTimeMs > threshMs;
        }
        for (DoomSession s : eng) {
            if (s != null && !s.isIdle(threshMs)) return false;
        }
        return true;
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public String  getMatchId()    { return matchId; }
    public int     getNumPlayers() { return numPlayers; }
    public boolean isStarted()     { return enginesStarted; }
    public boolean isStopped()     { return stopped; }
    public long    getUptimeMs()   { return System.currentTimeMillis() - createTimeMs; }
    public String  getWadName() {
        if (wadPath == null) return "?";
        String name = new java.io.File(wadPath).getName();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
    public String getWadPath()  { return wadPath; }

    public long getIdleMs() {
        DoomSession[] eng = engines;
        if (eng == null) return System.currentTimeMillis() - createTimeMs;
        long maxIdle = 0;
        for (DoomSession s : eng) {
            if (s != null) maxIdle = Math.max(maxIdle, s.getIdleMs());
        }
        return maxIdle;
    }

    /** Returns a snapshot of slot assignments: slot → browser UUID (may be null). */
    public List<String> getSlotAssignments() {
        List<String> result = new ArrayList<>();
        Collections.addAll(result, browserBySlot);
        return result;
    }
}
