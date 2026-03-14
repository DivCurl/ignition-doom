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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Manages the lifecycle of all DOOM game sessions.
 *
 * Responsibilities:
 * - Create / retrieve sessions (lazy, triggered by browser request)
 * - Enforce the per-server session cap (MAX_SESSIONS)
 * - WAD discovery and path resolution
 * - Per-WAD sound cache (initialized once per unique WAD path)
 * - Background reaper for idle session cleanup
 *
 * CRITICAL: The constructor does NOT start any engine and does NOT load any
 * engine classes. Sessions are created lazily in getOrCreate() when a browser
 * first connects — never at module startup.
 *
 * Phase 5.2: Session isolation via ClassLoader-per-session.
 */
public class SessionManager {

    private static final Logger logger = LoggerFactory.getLogger("DOOM.SessionManager");

    public static final int    MAX_SESSIONS    = 4;
    public static final long   IDLE_TIMEOUT_MS = 10L * 60 * 1000;  // 10 minutes
    public static final String BASE_DIR        = "/usr/local/bin/ignition/user-lib/doom/";
    public static final String WAD_DIR         = BASE_DIR + "iwads/";
    public static final String PWAD_DIR        = BASE_DIR + "pwads/";
    public static final String DEFAULT_WAD     = "DOOM1";

    private final URL    headlessRendererJar;
    private final String adminToken;
    private volatile String playPassword;
    private final ConcurrentHashMap<String, Boolean> playTokens = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, DoomSession>          sessions   = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, MatchSession>         matches    = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Map<String, byte[]>>  soundCaches        = new ConcurrentHashMap<>();
    /** Guards against concurrent cache builds per WAD path — only one thread builds at a time. */
    private final ConcurrentHashMap<String, Boolean>              soundCacheBuilding = new ConcurrentHashMap<>();
    private final ScheduledExecutorService reaper;
    private final long startTimeMs = System.currentTimeMillis();

    /**
     * Creates the SessionManager. Stores the renderer JAR URL and starts the reaper.
     * Does NOT start any DOOM engine.
     */
    public SessionManager(URL headlessRendererJar) {
        this.headlessRendererJar = headlessRendererJar;

        SecureRandom rng = new SecureRandom();

        // Generate a random 16-hex-char admin token. Printed once to the gateway log
        // so the administrator can read it and use it to access /system/doom/admin.
        byte[] tokenBytes = new byte[8];
        rng.nextBytes(tokenBytes);
        this.adminToken = bytesToHex(tokenBytes);

        // Generate a random 12-hex-char play password. Required by browser clients to
        // start or join any game session. Printed once to the gateway log on startup.
        byte[] pwBytes = new byte[6];
        rng.nextBytes(pwBytes);
        this.playPassword = bytesToHex(pwBytes).toUpperCase();

        logger.info("SessionManager init — headless-renderer.jar: {}", headlessRendererJar);
        logger.info("SessionManager init — WAD directory:          {}", WAD_DIR);
        logger.info("SessionManager init — max sessions:           {}", MAX_SESSIONS);
        logger.info("SessionManager init — idle timeout:           {} ms", IDLE_TIMEOUT_MS);
        logger.info("DOOM Admin token:    {}  (use at /system/doom/admin?token=...)", adminToken);
        logger.info("DOOM Play password:  {}  (required to start/join games at /system/doom)", playPassword);

        reaper = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "DOOM-SessionReaper");
            t.setDaemon(true);
            return t;
        });
        reaper.scheduleAtFixedRate(this::reapExpiredSessions, 60, 60, TimeUnit.SECONDS);
        logger.info("SessionManager ready");
    }

    /**
     * Returns an existing running session or creates a new one.
     * Returns null if the server is at capacity (caller should respond 503).
     *
     * @param sessionId  Browser-generated UUID
     * @param wadName    WAD filename stem (e.g. "DOOM1", "DOOM2", "TNT")
     * @param startEp    Episode (0 = main menu, 1–4 for DOOM1)
     * @param startMap   Map number
     * @param skill      Skill level (0–4)
     */
    public DoomSession getOrCreate(String sessionId, String wadName,
                                   int startEp, int startMap, int skill) throws Exception {
        return getOrCreate(sessionId, wadName, startEp, startMap, skill, "jpeg", null);
    }

    public DoomSession getOrCreate(String sessionId, String wadName,
                                   int startEp, int startMap, int skill,
                                   String frameFormat) throws Exception {
        return getOrCreate(sessionId, wadName, startEp, startMap, skill, frameFormat, null);
    }

    public DoomSession getOrCreate(String sessionId, String wadName,
                                   int startEp, int startMap, int skill,
                                   String frameFormat, String pwadName) throws Exception {
        // Return existing running session
        DoomSession existing = sessions.get(sessionId);
        if (existing != null && existing.isRunning()) {
            existing.touch();
            logger.debug("Returning existing session: {}", sessionId);
            return existing;
        }

        // Session cap check (single-player sessions + multiplayer matches share the limit)
        long active = countActiveEngines();
        if (active >= MAX_SESSIONS) {
            logger.warn("Session cap reached ({}/{}), rejecting session {}", active, MAX_SESSIONS, sessionId);
            return null;
        }

        // Resolve WAD path
        String wadPath = resolveWad(wadName != null ? wadName : DEFAULT_WAD);
        if (wadPath == null) {
            throw new IllegalArgumentException("WAD not found: " + wadName
                + " (expected in " + WAD_DIR + ")");
        }

        // Resolve optional PWAD path
        String pwadPath = (pwadName != null && !pwadName.isBlank()) ? resolvePwad(pwadName) : null;

        logger.info("Creating new session: id={}, wad={}, E{}M{} skill={} format={} pwad={}",
            sessionId, wadPath, startEp, startMap, skill, frameFormat, pwadPath != null ? pwadPath : "(none)");

        // Create and start session
        DoomSession session = new DoomSession(sessionId, wadPath, headlessRendererJar);
        session.setFrameFormat(frameFormat);
        session.setPwadPath(pwadPath);
        sessions.put(sessionId, session);
        try {
            session.start(startEp, startMap, skill);
        } catch (Exception e) {
            sessions.remove(sessionId);
            throw e;
        }

        logger.info("Session created successfully: {} (active sessions: {})",
            sessionId, sessions.values().stream().filter(DoomSession::isRunning).count());

        // Proactively warm the sound cache in the background so it is ready before the
        // browser requests /sounds. Without this, the first /sounds request blocks a
        // Jetty thread for the full cache-build duration (10–60 s on a cold JVM).
        final String warmWadPath = wadPath;
        Thread soundWarmThread = new Thread(() -> {
            logger.info("Warming sound cache for session {} (wad={})", sessionId, warmWadPath);
            getSoundCache(warmWadPath);
        }, "DOOM-SoundWarm-" + sessionId);
        soundWarmThread.setDaemon(true);
        soundWarmThread.start();

        return session;
    }

    // ── Match management ──────────────────────────────────────────────────────

    /**
     * Creates a new multiplayer match, starts its shared engine, and returns it.
     * The match ID must be unique (caller generates a UUID).
     * Returns null if the server is at capacity (caller should respond 503).
     *
     * @param matchId    Unique match identifier (UUID)
     * @param wadName    WAD stem (e.g. "DOOM2")
     * @param numPlayers 2–4 players
     * @param startEp    Episode (0 = main menu)
     * @param startMap   Map number
     * @param skill      Skill level (0–4)
     */
    public MatchSession createMatch(String matchId, String wadName, int numPlayers,
                                    int startEp, int startMap, int skill) throws Exception {
        return createMatch(matchId, wadName, numPlayers, startEp, startMap, skill, "jpeg", null);
    }

    public MatchSession createMatch(String matchId, String wadName, int numPlayers,
                                    int startEp, int startMap, int skill,
                                    String frameFormat) throws Exception {
        return createMatch(matchId, wadName, numPlayers, startEp, startMap, skill, frameFormat, null);
    }

    public MatchSession createMatch(String matchId, String wadName, int numPlayers,
                                    int startEp, int startMap, int skill,
                                    String frameFormat, String pwadName) throws Exception {
        // Session cap: reserve numPlayers slots so single-player sessions aren't displaced
        // once engines start. P2P engines start deferred (after all players join).
        long activeCount = countActiveEngines();
        if (activeCount + numPlayers > MAX_SESSIONS) {
            logger.warn("Session cap reached ({}/{}, need {}), rejecting match {}",
                activeCount, MAX_SESSIONS, numPlayers, matchId);
            return null;
        }

        String wadPath = resolveWad(wadName != null ? wadName : DEFAULT_WAD);
        if (wadPath == null) {
            throw new IllegalArgumentException("WAD not found: " + wadName + " (expected in " + WAD_DIR + ")");
        }

        // Resolve optional PWAD path
        String pwadPath = (pwadName != null && !pwadName.isBlank()) ? resolvePwad(pwadName) : null;

        logger.info("Creating match: id={}, wad={}, players={}, E{}M{} skill={} format={} pwad={}",
            matchId, wadPath, numPlayers, startEp, startMap, skill, frameFormat,
            pwadPath != null ? pwadPath : "(none)");
        MatchSession match = new MatchSession(matchId, wadPath, numPlayers, headlessRendererJar);
        match.setFrameFormat(frameFormat);
        match.setPwadPath(pwadPath);
        matches.put(matchId, match);
        // Store game params; engines are started once all players join via assignSlot()
        match.start(startEp, startMap, skill);
        logger.info("Match created: {} (lobby — engines start when full)", matchId);

        // Proactively warm the sound cache once all engines are running, so the first browser
        // to request /match/sounds doesn't encounter a cold-cache miss on first load.
        final String wWadPath = wadPath;
        final String wMatchId = matchId;
        Thread soundWarmThread = new Thread(() -> {
            for (int i = 0; i < 120; i++) { // up to 60s (120 × 500ms)
                try { Thread.sleep(500); } catch (InterruptedException e) { return; }
                MatchSession m = matches.get(wMatchId);
                if (m == null || !m.isAlive()) return;
                if (m.isFullyRunning()) {
                    logger.info("Warming sound cache for match {} (wad={})", wMatchId, wWadPath);
                    getSoundCache(wWadPath);
                    return;
                }
            }
            logger.warn("Sound cache warm-up timed out for match {}", wMatchId);
        }, "DOOM-SoundWarm-" + matchId);
        soundWarmThread.setDaemon(true);
        soundWarmThread.start();

        return match;
    }

    /**
     * Returns a match by ID (including lobby phase), or null if not found / stopped.
     * Touches the match to reset its idle timer.
     */
    public MatchSession getMatch(String matchId) {
        if (matchId == null) return null;
        MatchSession m = matches.get(matchId);
        if (m != null && m.isAlive()) {
            m.touch();
            return m;
        }
        return null;
    }

    /**
     * Forcibly terminates a match by ID.
     */
    public void killMatch(String matchId) {
        MatchSession match = matches.remove(matchId);
        if (match == null) {
            logger.warn("Admin kill: match not found: {}", matchId);
            return;
        }
        logger.info("Admin kill: terminating match {}", matchId);
        try { match.stop(); }
        catch (Exception e) { logger.warn("Error stopping admin-killed match {}", matchId, e); }
    }

    /** Returns the total number of active engine instances (single-player + matches). */
    public long countActiveEngines() {
        long sp = sessions.values().stream().filter(DoomSession::isRunning).count();
        long mp = matches.values().stream().filter(MatchSession::isFullyRunning).count();
        return sp + mp;
    }

    /**
     * Returns an existing session by ID, or null if not found / not running.
     */
    public DoomSession get(String sessionId) {
        if (sessionId == null) return null;
        DoomSession s = sessions.get(sessionId);
        if (s != null && s.isRunning()) {
            s.touch();
            return s;
        }
        return null;
    }

    /**
     * Returns a session by ID regardless of running state.
     * Used to detect GAME_ENDED sentinel after the session has stopped.
     */
    public DoomSession getSession(String sessionId) {
        if (sessionId == null) return null;
        return sessions.get(sessionId);
    }

    /**
     * Returns the sound data for a given WAD path and sound name.
     * Returns null if the cache isn't built yet or the sound doesn't exist.
     */
    public byte[] getSoundData(String wadPath, String soundName) {
        Map<String, byte[]> cache = getSoundCache(wadPath);
        if (cache == null) {
            logger.warn("getSoundData: no cache for WAD '{}' — sound='{}' returning null", wadPath, soundName);
            return null;
        }
        byte[] data = cache.get(soundName.toLowerCase());
        if (data == null) {
            logger.warn("getSoundData: '{}' not found in cache ({} entries) for WAD '{}'",
                soundName, cache.size(), wadPath);
        }
        return data;
    }

    /**
     * Returns all sound names available for a given WAD path.
     * Returns empty array if the cache isn't built yet.
     */
    public String[] getSoundNames(String wadPath) {
        Map<String, byte[]> cache = getSoundCache(wadPath);
        if (cache == null || cache.isEmpty()) {
            logger.info("getSoundNames: cache not ready for WAD '{}' → returning [] (building={})",
                wadPath, soundCacheBuilding.containsKey(wadPath));
            return new String[0];
        }
        String[] names = cache.keySet().toArray(new String[0]);
        logger.debug("getSoundNames: returning {} sounds for WAD '{}'", names.length, wadPath);
        return names;
    }

    /**
     * Returns JSON describing current state. Used by the /health endpoint.
     */
    public String getHealthJson() {
        long uptimeSec = (System.currentTimeMillis() - startTimeMs) / 1000;
        List<DoomSession> active = getActiveSessions();

        // List available WADs
        List<String> wads = new ArrayList<>();
        File wadDir = new File(WAD_DIR);
        if (wadDir.isDirectory()) {
            File[] files = wadDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.getName().toLowerCase().endsWith(".wad")) wads.add(f.getName());
                }
            }
        }

        // List available PWADs
        List<String> pwads = new ArrayList<>();
        File pwadDir = new File(PWAD_DIR);
        if (pwadDir.isDirectory()) {
            File[] pwadFiles = pwadDir.listFiles();
            if (pwadFiles != null) {
                for (File f : pwadFiles) {
                    String lower = f.getName().toLowerCase();
                    if (f.isFile() && (lower.endsWith(".wad") || lower.endsWith(".pk3") || lower.endsWith(".pk7"))) {
                        pwads.add(f.getName());
                    }
                }
                pwads.sort(String.CASE_INSENSITIVE_ORDER);
            }
        }

        StringBuilder json = new StringBuilder();
        json.append("{");
        json.append("\"status\":\"ok\",");
        json.append("\"uptimeSeconds\":").append(uptimeSec).append(",");
        json.append("\"activeSessions\":").append(active.size()).append(",");
        json.append("\"maxSessions\":").append(MAX_SESSIONS).append(",");
        json.append("\"headlessRendererJar\":\"").append(esc(headlessRendererJar.toString())).append("\",");
        json.append("\"wadDirectory\":\"").append(esc(WAD_DIR)).append("\",");
        json.append("\"availableWads\":[");
        for (int i = 0; i < wads.size(); i++) {
            if (i > 0) json.append(",");
            json.append("\"").append(esc(wads.get(i))).append("\"");
        }
        json.append("],");
        json.append("\"availablePwads\":[");
        for (int i = 0; i < pwads.size(); i++) {
            if (i > 0) json.append(",");
            String pwadName = pwads.get(i);
            WadInspector.WadInfo info = WadInspector.inspect(new java.io.File(PWAD_DIR, pwadName));
            json.append("{\"name\":\"").append(esc(pwadName)).append("\"");
            if (info.iwad     != null) json.append(",\"iwad\":\"").append(esc(info.iwad)).append("\"");
            if (info.firstMap != null) json.append(",\"firstMap\":\"").append(esc(info.firstMap)).append("\"");
            json.append("}");
        }
        json.append("],");
        json.append("\"sessions\":[");
        for (int i = 0; i < active.size(); i++) {
            if (i > 0) json.append(",");
            DoomSession s = active.get(i);
            json.append("{");
            json.append("\"id\":\"").append(esc(s.getSessionId())).append("\",");
            json.append("\"wad\":\"").append(esc(s.getWadName())).append("\",");
            json.append("\"idleMs\":").append(s.getIdleMs()).append(",");
            json.append("\"uptimeMs\":").append(s.getUptimeMs());
            json.append("}");
        }
        json.append("],");

        // Active matches
        List<MatchSession> activeMatches = new ArrayList<>();
        for (MatchSession m : matches.values()) {
            if (m.isAlive()) activeMatches.add(m);
        }
        json.append("\"activeMatches\":").append(activeMatches.size()).append(",");
        json.append("\"matches\":[");
        for (int i = 0; i < activeMatches.size(); i++) {
            if (i > 0) json.append(",");
            MatchSession m = activeMatches.get(i);
            json.append("{");
            json.append("\"id\":\"").append(esc(m.getMatchId())).append("\",");
            json.append("\"wad\":\"").append(esc(m.getWadName())).append("\",");
            json.append("\"players\":").append(m.getNumPlayers()).append(",");
            json.append("\"joined\":").append(m.getJoinedCount()).append(",");
            json.append("\"idleMs\":").append(m.getIdleMs()).append(",");
            json.append("\"uptimeMs\":").append(m.getUptimeMs());
            json.append("}");
        }
        json.append("]");

        json.append("}");
        return json.toString();
    }

    /** Shuts down all sessions, matches, and the reaper thread. */
    public void shutdown() {
        logger.info("SessionManager shutting down ({} sessions, {} matches)",
            sessions.size(), matches.size());
        reaper.shutdownNow();
        for (DoomSession session : sessions.values()) {
            try { session.stop(); }
            catch (Exception e) { logger.warn("Error stopping session {}", session.getSessionId(), e); }
        }
        for (MatchSession match : matches.values()) {
            try { match.stop(); }
            catch (Exception e) { logger.warn("Error stopping match {}", match.getMatchId(), e); }
        }
        sessions.clear();
        matches.clear();
        soundCaches.clear();
        logger.info("SessionManager shutdown complete");
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Resolves a WAD name to an absolute file path in WAD_DIR.
     * Tries: NAME.WAD, name.wad (case-insensitive fallback).
     * Returns null if not found.
     */
    private String resolveWad(String wadName) {
        // Sanitize: strip path separators to prevent traversal
        wadName = wadName.replaceAll("[/\\\\]", "").trim();
        if (wadName.isEmpty()) return null;

        File dir = new File(WAD_DIR);
        if (!dir.isDirectory()) {
            logger.warn("WAD directory not found: {}", WAD_DIR);
            return null;
        }

        // Try exact name with .WAD extension first, then .wad
        String upper = wadName.toUpperCase();
        for (String ext : new String[]{".WAD", ".wad"}) {
            File candidate = new File(dir, upper + ext);
            if (!candidate.exists()) candidate = new File(dir, wadName + ext);
            if (candidate.exists() && candidate.isFile()) {
                logger.info("Resolved WAD: {} → {}", wadName, candidate.getAbsolutePath());
                return candidate.getAbsolutePath();
            }
        }

        // Case-insensitive scan
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                String fn = f.getName().toLowerCase();
                if (fn.equals(upper.toLowerCase() + ".wad") && f.isFile()) {
                    logger.info("Resolved WAD (case-insensitive): {} → {}", wadName, f.getAbsolutePath());
                    return f.getAbsolutePath();
                }
            }
        }

        logger.warn("WAD not found: '{}' in {}", wadName, WAD_DIR);
        return null;
    }

    /**
     * Resolves a PWAD filename to an absolute file path in PWAD_DIR.
     * Accepts the full filename including extension (e.g. "mypwad.wad", "cool.pk3").
     * Returns null if not found or if PWAD_DIR doesn't exist.
     */
    private String resolvePwad(String pwadFilename) {
        if (pwadFilename == null || pwadFilename.isBlank()) return null;
        // Sanitize: strip path separators to prevent traversal
        pwadFilename = pwadFilename.replaceAll("[/\\\\]", "").trim();
        if (pwadFilename.isEmpty()) return null;

        File dir = new File(PWAD_DIR);
        if (!dir.isDirectory()) {
            logger.debug("PWAD directory not found: {}", PWAD_DIR);
            return null;
        }

        // Exact match first
        File candidate = new File(dir, pwadFilename);
        if (candidate.exists() && candidate.isFile()) {
            logger.info("Resolved PWAD: {} → {}", pwadFilename, candidate.getAbsolutePath());
            return candidate.getAbsolutePath();
        }

        // Case-insensitive scan
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.getName().equalsIgnoreCase(pwadFilename) && f.isFile()) {
                    logger.info("Resolved PWAD (case-insensitive): {} → {}", pwadFilename, f.getAbsolutePath());
                    return f.getAbsolutePath();
                }
            }
        }

        logger.warn("PWAD not found: '{}' in {}", pwadFilename, PWAD_DIR);
        return null;
    }

    /**
     * Returns the first running engine (single-player session or match engine session)
     * that uses the given WAD path. Used for sound cache bootstrapping.
     */
    private DoomSession findAnyEngineForWad(String wadPath) {
        for (DoomSession s : sessions.values()) {
            if (wadPath.equals(s.getWadPath()) && s.isRunning()) return s;
        }
        for (MatchSession m : matches.values()) {
            if (wadPath.equals(m.getWadPath()) && m.isFullyRunning()) {
                DoomSession eng = m.getEngineSession();
                if (eng != null && eng.isRunning()) return eng;
            }
        }
        return null;
    }

    /**
     * Returns the sound cache for a WAD path, initializing it if needed.
     *
     * Non-blocking: only ONE thread builds the cache at a time (guarded by soundCacheBuilding).
     * If a build is already in progress, returns null immediately so the caller can retry.
     * Subsequent calls after the cache is built return immediately from soundCaches.
     *
     * This prevents servlet request threads from blocking for the full cache-build duration
     * (150+ WAD sound extractions, potentially 1-30 seconds on cold start).
     */
    private Map<String, byte[]> getSoundCache(String wadPath) {
        // Fast path: already built
        Map<String, byte[]> existing = soundCaches.get(wadPath);
        if (existing != null) return existing;

        // Compete to become the builder. If another thread already claimed the slot, return null
        // immediately so the caller (browser retry or warm-up thread) retries in 500 ms.
        if (soundCacheBuilding.putIfAbsent(wadPath, Boolean.TRUE) != null) {
            logger.debug("Sound cache build in progress for {} — caller should retry", wadPath);
            return null;
        }

        try {
            // Double-check: a concurrent build may have completed while we waited for putIfAbsent
            existing = soundCaches.get(wadPath);
            if (existing != null) return existing;

            DoomSession session = findAnyEngineForWad(wadPath);
            if (session == null) {
                logger.warn("No running session found for WAD: {} — sound cache deferred", wadPath);
                return null;
            }

            logger.info("Initializing sound cache for WAD: {}", wadPath);
            Map<String, byte[]> cache = new ConcurrentHashMap<>();
            try {
                String[] soundNames = session.discoverSounds();
                logger.info("Discovered {} sounds in WAD {}", soundNames.length, wadPath);
                for (String name : soundNames) {
                    byte[] wav = session.extractSoundAsWav(name);
                    if (wav != null) cache.put(name.toLowerCase(), wav);
                }
                logger.info("Sound cache ready: {} sounds for {}", cache.size(), wadPath);
            } catch (Exception e) {
                logger.error("Error building sound cache for {}", wadPath, e);
            }

            // Only store if we got something (don't replace a populated cache with empty)
            if (!cache.isEmpty()) {
                soundCaches.putIfAbsent(wadPath, cache);
            }
            return soundCaches.getOrDefault(wadPath, cache);
        } finally {
            // Always release the building flag so a subsequent call can retry if this one failed
            soundCacheBuilding.remove(wadPath);
        }
    }

    /** Background task: stops and removes sessions/matches that have been idle too long. */
    private void reapExpiredSessions() {
        try {
            // Reap single-player sessions
            List<String> toRemove = new ArrayList<>();
            for (Map.Entry<String, DoomSession> entry : sessions.entrySet()) {
                DoomSession s = entry.getValue();
                if (!s.isRunning() || s.isIdle(IDLE_TIMEOUT_MS)) {
                    toRemove.add(entry.getKey());
                }
            }
            for (String id : toRemove) {
                DoomSession s = sessions.remove(id);
                if (s != null) {
                    logger.info("Reaping idle/dead session: {}", id);
                    try { s.stop(); }
                    catch (Exception e) { logger.warn("Error stopping reaped session {}", id, e); }
                }
            }

            // Reap multiplayer matches
            List<String> matchesToRemove = new ArrayList<>();
            for (Map.Entry<String, MatchSession> entry : matches.entrySet()) {
                MatchSession m = entry.getValue();
                if (!m.isAlive() || m.isIdle(IDLE_TIMEOUT_MS)) {
                    matchesToRemove.add(entry.getKey());
                }
            }
            for (String id : matchesToRemove) {
                MatchSession m = matches.remove(id);
                if (m != null) {
                    logger.info("Reaping idle/dead match: {}", id);
                    try { m.stop(); }
                    catch (Exception e) { logger.warn("Error stopping reaped match {}", id, e); }
                }
            }

            int total = toRemove.size() + matchesToRemove.size();
            if (total > 0) {
                logger.info("Reaped {} engine(s) ({} sessions, {} matches); {} sessions + {} matches remaining",
                    total, toRemove.size(), matchesToRemove.size(), sessions.size(), matches.size());
            }
        } catch (Exception e) {
            logger.error("Error in session reaper", e);
        }
    }

    /**
     * Validates an admin token using a timing-safe comparison.
     * Returns false if the token is null, empty, or incorrect.
     */
    public boolean validateAdminToken(String token) {
        if (token == null || token.isEmpty()) return false;
        return MessageDigest.isEqual(
            token.getBytes(StandardCharsets.UTF_8),
            adminToken.getBytes(StandardCharsets.UTF_8)
        );
    }

    /** Returns the admin token (used by servlet to embed it in redirect URLs). */
    public String getAdminToken() { return adminToken; }

    /** Returns the play password (displayed on the admin page). */
    public String getPlayPassword() { return playPassword; }

    /** Validates a play password supplied by the browser. Timing-safe comparison. */
    public boolean validatePlayPassword(String pw) {
        if (pw == null || pw.isEmpty()) return false;
        return MessageDigest.isEqual(
            pw.toUpperCase().getBytes(StandardCharsets.UTF_8),
            playPassword.getBytes(StandardCharsets.UTF_8)
        );
    }

    /**
     * Issues a play token after successful password validation.
     * Tokens remain valid until {@link #regeneratePlayPassword()} is called.
     */
    public String issuePlayToken() {
        String token = UUID.randomUUID().toString().replace("-", "");
        playTokens.put(token, Boolean.TRUE);
        return token;
    }

    /** Returns true if the token was issued by this manager and has not been revoked. */
    public boolean validatePlayToken(String token) {
        if (token == null || token.isEmpty()) return false;
        return playTokens.containsKey(token);
    }

    /**
     * Generates a new play password and revokes all existing play tokens.
     * Returns the new password so the caller can display or log it.
     */
    public String regeneratePlayPassword() {
        byte[] pwBytes = new byte[6];
        new SecureRandom().nextBytes(pwBytes);
        playPassword = bytesToHex(pwBytes).toUpperCase();
        playTokens.clear();
        logger.info("DOOM Play password regenerated: {}  (all existing tokens revoked)", playPassword);
        return playPassword;
    }

    /** Returns seconds elapsed since this SessionManager was created. */
    public long getUptimeSec() { return (System.currentTimeMillis() - startTimeMs) / 1000; }

    /**
     * Forcibly terminates a session by ID and removes it from the session map.
     * Safe to call even if the session is already stopped or not found.
     */
    public void killSession(String sessionId) {
        DoomSession session = sessions.remove(sessionId);
        if (session == null) {
            logger.warn("Admin kill: session not found: {}", sessionId);
            return;
        }
        logger.info("Admin kill: terminating session {}", sessionId);
        try { session.stop(); }
        catch (Exception e) { logger.warn("Error stopping admin-killed session {}", sessionId, e); }
        logger.info("Admin kill: session {} terminated", sessionId);
    }

    /**
     * Returns a snapshot of all active sessions as a list of info maps.
     * Used by getHealthJson() and the admin page.
     */
    public List<DoomSession> getActiveSessions() {
        List<DoomSession> result = new ArrayList<>();
        for (DoomSession s : sessions.values()) {
            if (s.isRunning()) result.add(s);
        }
        return result;
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
