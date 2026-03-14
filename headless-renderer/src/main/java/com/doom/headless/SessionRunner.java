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

package com.doom.headless;

import com.doom.common.FrameData;
import com.doom.common.ISessionRunner;
import com.doom.common.MusicStateDTO;
import com.doom.common.SoundEventDTO;
import com.doom.common.TikcmdBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * ISessionRunner implementation — the actual headless engine wrapper.
 *
 * Loaded by a child URLClassLoader (one per browser session). Mocha DOOM's statics
 * are isolated per child CL, so multiple tabs don't step on each other.
 */
public class SessionRunner implements ISessionRunner {

    private static final Logger logger = LoggerFactory.getLogger("DOOM.SessionRunner");

    // ── Frame output ─────────────────────────────────────────────────────────
    /** Sentinel only: "" (no frame), "GAME_ENDED". Never holds a data URI. */
    private final AtomicReference<String> currentFrame      = new AtomicReference<>("");
    /** Raw JPEG or PNG bytes of the latest rendered frame. */
    private final AtomicReference<byte[]> currentFrameBytes = new AtomicReference<>(null);
    private volatile String               frameContentType  = "image/jpeg";
    /** Notified each time a new encoded frame is stored, or when the session ends. */
    private final Object                  frameLock         = new Object();
    private volatile int                  encodedFrameSeq   = 0;
    private final AtomicInteger           frameCounter      = new AtomicInteger(0);
    private final FrameEncoder            encoder           = new FrameEncoder(0.85f);
    private volatile boolean              usePng            = false;
    private volatile String               pwadPath          = null;

    @Override
    public void setFrameFormat(String format) {
        this.usePng = "png".equalsIgnoreCase(format);
        this.frameContentType = this.usePng ? "image/png" : "image/jpeg";
    }

    @Override
    public void setPwadPath(String path) {
        this.pwadPath = path;
    }

    // ── Multiplayer (P2P) ────────────────────────────────────────────────────
    private volatile int      numPlayers = 1;
    private volatile boolean  p2pMode    = false;
    private TikcmdBus         p2pBus     = null;
    private int               p2pSlot    = 0;

    // ── Input ────────────────────────────────────────────────────────────────
    private final AtomicReference<Set<Integer>> currentPressedKeys =
        new AtomicReference<>(Collections.emptySet());
    private Set<Integer> prevKeys = new HashSet<>();

    // ── Audio ────────────────────────────────────────────────────────────────
    private final ConcurrentLinkedQueue<SoundEventDTO> soundQueue  = new ConcurrentLinkedQueue<>();
    private final AtomicReference<MusicStateDTO>       latestMusic = new AtomicReference<>(null);

    // ── Async frame encoding (encoder thread decoupled from game thread) ─────
    /**
     * Latest raw FrameData to encode. Written by the game thread (in onFrameRendered),
     * consumed by the encoder thread. AtomicReference acts as a size-1 "latest frame"
     * queue: the encoder always encodes the most recent frame; older ones are dropped.
     */
    private final AtomicReference<FrameData> pendingRawFrame = new AtomicReference<>(null);
    private Thread               encoderThread;
    private volatile boolean     encoderRunning = false;

    // ── Lifecycle ────────────────────────────────────────────────────────────
    private final CountDownLatch initLatch   = new CountDownLatch(1);
    private volatile boolean     initialized = false;
    private volatile boolean     running     = false;
    private Thread               gameThread;
    /** Set to true once the player enters a level; used to detect End Game title-screen return. */
    private volatile boolean     wasInGame   = false;

    // ── Engine (erased to Object — no compile-time dep on engine types from gateway) ──
    private Object doomMain; // actual type: doom.DoomMain<byte[], byte[]>

    // ── Key mapping (JS keyCode → ScanCode, inlined to avoid gateway compile dep) ──
    private static final Map<Integer, g.Signals.ScanCode> KEY_MAP = buildKeyMap();

    private static Map<Integer, g.Signals.ScanCode> buildKeyMap() {
        Map<Integer, g.Signals.ScanCode> m = new HashMap<>();
        // WASD mapped to arrow scan codes so both WASD and arrow keys control movement
        m.put(87, g.Signals.ScanCode.SC_UP);    // W → forward
        m.put(83, g.Signals.ScanCode.SC_DOWN);  // S → backward
        m.put(65, g.Signals.ScanCode.SC_A);     // A → strafe left
        m.put(68, g.Signals.ScanCode.SC_D);     // D → strafe right
        // Arrow keys
        m.put(37, g.Signals.ScanCode.SC_LEFT);
        m.put(38, g.Signals.ScanCode.SC_UP);
        m.put(39, g.Signals.ScanCode.SC_RIGHT);
        m.put(40, g.Signals.ScanCode.SC_DOWN);
        // Letters (excluding W, S — handled specially in sendCheatKeys and above)
        m.put(66, g.Signals.ScanCode.SC_B);  m.put(67, g.Signals.ScanCode.SC_C);
        m.put(69, g.Signals.ScanCode.SC_E);  m.put(70, g.Signals.ScanCode.SC_F);
        m.put(71, g.Signals.ScanCode.SC_G);  m.put(72, g.Signals.ScanCode.SC_H);
        m.put(73, g.Signals.ScanCode.SC_I);  m.put(74, g.Signals.ScanCode.SC_J);
        m.put(75, g.Signals.ScanCode.SC_K);  m.put(76, g.Signals.ScanCode.SC_L);
        m.put(77, g.Signals.ScanCode.SC_M);  m.put(78, g.Signals.ScanCode.SC_N);
        m.put(79, g.Signals.ScanCode.SC_O);  m.put(80, g.Signals.ScanCode.SC_P);
        m.put(81, g.Signals.ScanCode.SC_Q);  m.put(82, g.Signals.ScanCode.SC_R);
        m.put(84, g.Signals.ScanCode.SC_T);  m.put(85, g.Signals.ScanCode.SC_U);
        m.put(86, g.Signals.ScanCode.SC_V);  m.put(88, g.Signals.ScanCode.SC_X);
        m.put(89, g.Signals.ScanCode.SC_Y);  m.put(90, g.Signals.ScanCode.SC_Z);
        // Digits
        m.put(48, g.Signals.ScanCode.SC_0);  m.put(49, g.Signals.ScanCode.SC_1);
        m.put(50, g.Signals.ScanCode.SC_2);  m.put(51, g.Signals.ScanCode.SC_3);
        m.put(52, g.Signals.ScanCode.SC_4);  m.put(53, g.Signals.ScanCode.SC_5);
        m.put(54, g.Signals.ScanCode.SC_6);  m.put(55, g.Signals.ScanCode.SC_7);
        m.put(56, g.Signals.ScanCode.SC_8);  m.put(57, g.Signals.ScanCode.SC_9);
        // Special keys
        m.put(32,  g.Signals.ScanCode.SC_SPACE);
        m.put(17,  g.Signals.ScanCode.SC_LCTRL);
        m.put(16,  g.Signals.ScanCode.SC_LSHIFT);
        m.put(18,  g.Signals.ScanCode.SC_LALT);
        m.put(27,  g.Signals.ScanCode.SC_ESCAPE);
        m.put(13,  g.Signals.ScanCode.SC_ENTER);
        m.put(9,   g.Signals.ScanCode.SC_TAB);
        m.put(8,   g.Signals.ScanCode.SC_BACKSPACE);
        // F-keys
        m.put(112, g.Signals.ScanCode.SC_F1);   m.put(113, g.Signals.ScanCode.SC_F2);
        m.put(114, g.Signals.ScanCode.SC_F3);   m.put(115, g.Signals.ScanCode.SC_F4);
        m.put(116, g.Signals.ScanCode.SC_F5);   m.put(117, g.Signals.ScanCode.SC_F6);
        m.put(118, g.Signals.ScanCode.SC_F7);   m.put(119, g.Signals.ScanCode.SC_F8);
        m.put(120, g.Signals.ScanCode.SC_F9);   m.put(121, g.Signals.ScanCode.SC_F10);
        m.put(122, g.Signals.ScanCode.SC_F11);  m.put(123, g.Signals.ScanCode.SC_F12);
        return Collections.unmodifiableMap(m);
    }

    // ── ISessionRunner: Lifecycle ────────────────────────────────────────────

    @Override
    public void start(String wadPath, String configDir, int width, int height,
                      int startEp, int startMap, int skill) throws Exception {
        logger.info("SessionRunner.start() — wadPath={}, configDir={}, {}x{}, E{}M{}, skill={}",
            wadPath, configDir, width, height, startEp, startMap, skill);

        startEncoderThread();

        gameThread = new Thread(
            () -> runGameLoop(wadPath, configDir, width, height, startEp, startMap, skill),
            "DOOM-GameThread"
        );
        gameThread.setDaemon(true);
        gameThread.start();

        // Block until engine init completes or times out
        boolean timedOut;
        try {
            timedOut = !initLatch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Exception("Interrupted while waiting for DOOM engine init", e);
        }

        if (timedOut) {
            gameThread.interrupt();
            throw new Exception("DOOM engine initialization timed out (30s) — check gateway log");
        }
        if (!initialized) {
            throw new Exception("DOOM engine initialization failed — check gateway log");
        }

        logger.info("SessionRunner.start() complete — engine running");
    }

    @Override
    public void stop() {
        logger.info("SessionRunner.stop() called");
        running = false;

        encoderRunning = false;
        if (encoderThread != null) {
            encoderThread.interrupt();
            try { encoderThread.join(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }

        if (gameThread != null && gameThread.isAlive()) {
            gameThread.interrupt();
            try {
                gameThread.join(5000);
                if (gameThread.isAlive()) {
                    logger.warn("DOOM game thread did not stop within 5 seconds");
                } else {
                    logger.info("DOOM game thread stopped cleanly");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        // Clear headless callback in case thread didn't clean it up
        v.renderers.BufferedRenderer.setHeadlessCallback(null);
        logger.info("SessionRunner.stop() complete");
    }

    @Override
    public boolean isRunning() {
        return running && initialized;
    }

    // ── ISessionRunner: Frame output ─────────────────────────────────────────

    @Override
    public String getCurrentFrame() {
        if ("GAME_ENDED".equals(currentFrame.get())) return "GAME_ENDED";
        return currentFrameBytes.get() != null ? "READY" : "";
    }

    @Override
    public byte[] getCurrentFrameBytes() {
        return currentFrameBytes.get();
    }

    @Override
    public String getFrameContentType() {
        return frameContentType;
    }

    @Override
    public int getFrameSeq() {
        return encodedFrameSeq;
    }

    @Override
    public byte[] waitForNextFrame(int afterSeq, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        synchronized (frameLock) {
            while (encodedFrameSeq <= afterSeq && running && !"GAME_ENDED".equals(currentFrame.get())) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) return null;
                frameLock.wait(remaining);
            }
        }
        return currentFrameBytes.get();
    }

    @Override
    public int getFrameNumber() {
        return frameCounter.get();
    }

    // ── ISessionRunner: Input ────────────────────────────────────────────────

    @Override
    public void updatePressedKeys(Set<Integer> pressedKeys) {
        currentPressedKeys.set(new HashSet<>(pressedKeys));
    }

    // ── ISessionRunner: Game control ─────────────────────────────────────────

    @Override
    @SuppressWarnings("unchecked")
    public void sendCheatKeys(String cheat) {
        if (doomMain == null || !initialized) return;
        doom.DoomMain<byte[], byte[]> dm = (doom.DoomMain<byte[], byte[]>) doomMain;
        for (char c : cheat.toUpperCase().toCharArray()) {
            if (c >= 'A' && c <= 'Z') {
                // W (87) and S (83) are remapped to SC_UP/SC_DOWN for WASD movement.
                // Cheat detector checks event.data2 (ASCII char code); SC_DOWN.data2 ≠ 's'.
                // Bypass KEY_MAP for these two letters: use SC_W/SC_S letter scan codes directly.
                if (c == 'W') {
                    dm.PostEvent(g.Signals.ScanCode.SC_W.doomEventDown);
                    dm.PostEvent(g.Signals.ScanCode.SC_W.doomEventUp);
                } else if (c == 'S') {
                    dm.PostEvent(g.Signals.ScanCode.SC_S.doomEventDown);
                    dm.PostEvent(g.Signals.ScanCode.SC_S.doomEventUp);
                } else {
                    injectKeyEvent(dm, (int) c, true);
                    injectKeyEvent(dm, (int) c, false);
                }
            }
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public void warp(int episode, int map, int skill) {
        if (doomMain == null || !initialized) {
            logger.warn("Cannot warp: engine not initialized");
            return;
        }
        doom.DoomMain<byte[], byte[]> dm = (doom.DoomMain<byte[], byte[]>) doomMain;
        defines.skill_t skillEnum = defines.skill_t.values()[Math.max(0, Math.min(4, skill))];
        dm.DeferedInitNew(skillEnum, episode, map);
        logger.info("Warp queued: E{}M{} skill={}", episode, map, skill);
    }

    @Override
    @SuppressWarnings("unchecked")
    public boolean isCommercial() {
        if (doomMain == null || !initialized) return false;
        try {
            doom.DoomMain<byte[], byte[]> dm = (doom.DoomMain<byte[], byte[]>) doomMain;
            return dm.isCommercial();
        } catch (Exception e) {
            return false;
        }
    }

    // ── ISessionRunner: Audio ────────────────────────────────────────────────

    @Override
    public SoundEventDTO[] pollSoundEvents() {
        if (soundQueue.isEmpty()) return new SoundEventDTO[0];
        SoundEventDTO[] events = soundQueue.toArray(new SoundEventDTO[0]);
        soundQueue.clear();
        return events;
    }

    /** Per-slot drain: in P2P mode each engine has its own queue; delegate to single-player. */
    @Override
    public SoundEventDTO[] pollSoundEvents(int slot) {
        return pollSoundEvents();
    }

    @Override
    public MusicStateDTO pollMusicState() {
        return latestMusic.getAndSet(null);
    }

    // ── ISessionRunner: Sound cache helpers ──────────────────────────────────

    @Override
    @SuppressWarnings("unchecked")
    public String[] discoverSounds() {
        if (doomMain == null || !initialized) return new String[0];
        try {
            doom.DoomMain<byte[], byte[]> dm = (doom.DoomMain<byte[], byte[]>) doomMain;
            return new DoomSoundExtractor(dm.wadLoader).discoverAllSounds();
        } catch (Exception e) {
            logger.warn("Error discovering sounds", e);
            return new String[0];
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public byte[] extractSoundAsWav(String soundName) {
        if (doomMain == null || !initialized) return null;
        try {
            doom.DoomMain<byte[], byte[]> dm = (doom.DoomMain<byte[], byte[]>) doomMain;
            return new DoomSoundExtractor(dm.wadLoader)
                .extractSoundAsWAV("DS" + soundName.toUpperCase());
        } catch (Exception e) {
            logger.warn("Error extracting sound: {}", soundName, e);
            return null;
        }
    }

    // ── ISessionRunner: Multiplayer ──────────────────────────────────────────

    @Override
    public void startMultiplayer(String wadPath, String configDir, int width, int height,
                                 int startEp, int startMap, int skill,
                                 int numPlayersArg) throws Exception {
        // Shared-simulation multiplayer removed in Phase 6.3; delegate to single-player start.
        start(wadPath, configDir, width, height, startEp, startMap, skill);
    }

    @Override
    public void startP2P(String wadPath, String configDir, int width, int height,
                         int startEp, int startMap, int skill,
                         int numPlayersArg, TikcmdBus bus, int playerSlot) throws Exception {
        this.numPlayers = Math.max(2, Math.min(4, numPlayersArg));
        this.p2pBus     = bus;
        this.p2pSlot    = playerSlot;
        this.p2pMode    = true;

        logger.info("SessionRunner.startP2P() — slot={} of {} players, wadPath={}",
                playerSlot, this.numPlayers, wadPath);

        startEncoderThread();

        gameThread = new Thread(
            () -> runGameLoop(wadPath, configDir, width, height, startEp, startMap, skill),
            "DOOM-P2P-Slot" + playerSlot
        );
        gameThread.setDaemon(true);
        gameThread.start();

        boolean timedOut;
        try {
            timedOut = !initLatch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Exception("Interrupted while waiting for DOOM P2P engine init (slot " + playerSlot + ")", e);
        }
        if (timedOut) {
            gameThread.interrupt();
            throw new Exception("DOOM P2P engine init timed out (30s) — slot " + playerSlot);
        }
        if (!initialized) {
            throw new Exception("DOOM P2P engine init failed — slot " + playerSlot + " — check gateway log");
        }
        logger.info("SessionRunner.startP2P() complete — slot {} running", playerSlot);
    }

    @Override
    public void updatePressedKeys(int slot, Set<Integer> pressedKeys) {
        // In P2P mode each engine owns one slot; route everything through the single-player path.
        updatePressedKeys(pressedKeys);
    }

    @Override
    public String getPlayerFrame(int slot) {
        return getCurrentFrame();
    }

    @Override
    public int getNumPlayers() { return numPlayers; }

    @Override
    public boolean isMultiplayer() { return p2pMode; }

    // ── Private: Encoder thread ───────────────────────────────────────────────

    /**
     * Starts the background JPEG encoder thread.
     * The game thread writes raw FrameData to pendingRawFrame; this thread encodes it
     * and updates currentFrame. Decoupling encoding from the game loop prevents JPEG
     * compression latency (~50-100ms) from blocking TikcmdBus synchronization.
     */
    private void startEncoderThread() {
        encoderRunning = true;
        encoderThread = new Thread(() -> {
            while (encoderRunning) {
                FrameData fd = pendingRawFrame.getAndSet(null);
                if (fd != null) {
                    try {
                        currentFrameBytes.set(usePng ? encoder.toPNG(fd) : encoder.toJPEG(fd));
                        synchronized (frameLock) { encodedFrameSeq++; frameLock.notifyAll(); }
                    } catch (Exception e) {
                        logger.error("Error encoding frame", e);
                    }
                } else {
                    try {
                        Thread.sleep(5);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }, "doom-encoder");
        encoderThread.setDaemon(true);
        encoderThread.start();
    }

    // ── Private: Game loop (runs on dedicated game thread) ───────────────────

    @SuppressWarnings("unchecked")
    private void runGameLoop(String wadPath, String configDir, int width, int height,
                             int startEp, int startMap, int skill) {
        logger.info("DOOM game thread starting — WAD={}, {}x{}", wadPath, width, height);
        try {
            // Set thread context CL to this child CL so reflective lookups resolve here
            Thread.currentThread().setContextClassLoader(getClass().getClassLoader());

            // Register headless frame callback before engine init
            v.renderers.BufferedRenderer.setHeadlessCallback(this::onFrameRendered);
            logger.info("Headless frame callback registered on thread: {}",
                Thread.currentThread().getName());

            // CRITICAL: AWT headless must be set before Engine init to prevent window creation
            System.setProperty("java.awt.headless", "true");

            // Build CLI args
            ArrayList<String> argList = new ArrayList<>(Arrays.asList(
                "-iwad",   wadPath,
                "-width",  String.valueOf(width),
                "-height", String.valueOf(height),
                "-nogui",
                "-config", configDir + "doom.cfg"
            ));
            if (startEp > 0) {
                argList.addAll(Arrays.asList(
                    "-warp", String.valueOf(startEp), String.valueOf(startMap),
                    "-skill", String.valueOf(skill + 1)   // CLI is 1-based
                ));
            }
            if (pwadPath != null && !pwadPath.isEmpty()) {
                argList.addAll(Arrays.asList("-file", pwadPath));
                logger.info("PWAD loaded: {}", pwadPath);
            }
            String[] args = argList.toArray(new String[0]);
            logger.info("DOOM args: {}", Arrays.toString(args));

            // Create session-private config directory
            new File(configDir).mkdirs();
            logger.info("Config dir prepared: {}", configDir);

            // Seed config: screenblocks=10 removes the border frame around the play area.
            // Default is 9 (one tick below full-screen). Writing this before initializeWithArgs()
            // so the engine reads it on first load via the -config argument.
            File cfgFile = new File(configDir + "doom.cfg");
            if (!cfgFile.exists()) {
                try (java.io.PrintWriter cfgWriter = new java.io.PrintWriter(cfgFile)) {
                    cfgWriter.println("screenblocks 10");
                } catch (java.io.IOException ex) {
                    logger.warn("Failed to write doom.cfg defaults: {}", ex.getMessage());
                }
            }

            // Initialize engine singleton (scoped to THIS child ClassLoader)
            logger.info("Calling Engine.initializeWithArgs() — this CL: {}",
                getClass().getClassLoader());
            mochadoom.Engine.initializeWithArgs(args);
            logger.info("Engine.initializeWithArgs() returned — engine init complete");

            // Get DoomMain reference
            doomMain = mochadoom.Engine.getDoomMain();
            logger.info("DoomMain acquired: {}", doomMain.getClass().getName());

            // Remap movement keys to arrow scan codes (WASD + arrows both work)
            doom.DoomMain<byte[], byte[]> dm = (doom.DoomMain<byte[], byte[]>) doomMain;
            dm.key_up          = g.Signals.ScanCode.SC_UP.ordinal();
            dm.key_down        = g.Signals.ScanCode.SC_DOWN.ordinal();
            dm.key_left        = g.Signals.ScanCode.SC_LEFT.ordinal();
            dm.key_right       = g.Signals.ScanCode.SC_RIGHT.ordinal();
            dm.key_strafeleft  = g.Signals.ScanCode.SC_A.ordinal();
            dm.key_straferight = g.Signals.ScanCode.SC_D.ordinal();
            logger.info("Key bindings remapped (arrows + WASD)");

            // ── P2P multiplayer setup (after init, before setupLoop) ─────────
            if (p2pMode && numPlayers > 1 && p2pBus != null) {
                doom.P2PNetDriver<byte[], byte[]> drv =
                    new doom.P2PNetDriver<>(dm, numPlayers, p2pSlot, p2pBus);
                dm.systemNetworking = drv;
                drv.setupForGame();
                logger.info("P2PNetDriver installed: slot {} of {} players", p2pSlot, numPlayers);
            }

            // Auto-warp to MAP01/E1M1 when a PWAD is loaded without an explicit warp target.
            // Matches typical source port behaviour: -file mymod.wad drops straight into the first map.
            //
            // Cannot use DeferedInitNew() here: setupLoop() checks autostart before entering DoomLoop().
            // When autostart==false it calls StartTitle() which sets gameaction=ga_nothing, wiping any
            // deferred action we queued. Setting autostart=true (with the desired skill/ep/map) makes
            // setupLoop() call InitNew() directly instead, bypassing StartTitle() entirely.
            if (pwadPath != null && !pwadPath.isEmpty() && startEp == 0) {
                dm.startskill   = defines.skill_t.values()[Math.max(0, Math.min(4, skill))];
                dm.startepisode = 1;
                dm.startmap     = 1;
                dm.autostart    = true;
                logger.info("PWAD auto-warp: autostart=true, E1M1/MAP01, skill {}", skill + 1);
            }

            // Signal successful initialization to start()
            running     = true;
            initialized = true;
            initLatch.countDown();
            logger.info("Engine initialized — entering game loop");

            // Blocks until thread is interrupted or DOOM quit is called
            dm.setupLoop();
            logger.info("DOOM game loop exited normally");

        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            if (msg.contains("quit requested") || msg.contains("interrupted")) {
                logger.info("DOOM game thread: clean exit ({})", msg);
            } else {
                logger.error("DOOM game thread error: {}", msg, e);
            }
        } finally {
            running = false;
            // Set sentinel so the browser detects a clean engine exit (e.g. End Game menu action).
            // Only set if the engine fully initialized — avoids false positives on startup failure.
            if (initialized) {
                currentFrame.set("GAME_ENDED");
                synchronized (frameLock) { frameLock.notifyAll(); }
            }
            // Release latch so start() doesn't hang if init never completed
            initLatch.countDown();
            v.renderers.BufferedRenderer.setHeadlessCallback(null);
            logger.info("DOOM game thread finished");
        }
    }

    // ── Private: Frame callback (35 Hz, game thread) ─────────────────────────

    @SuppressWarnings("unchecked")
    private void onFrameRendered(FrameData frameData) {
        // Detect End Game: player was in a level and the game has returned to the title screen.
        // M_EndGameResponse calls StartTitle() which transitions gamestate → GS_DEMOSCREEN but
        // never exits DoomLoop(), so the finally block never runs. We detect the transition here
        // and push the GAME_ENDED sentinel so the browser shows the overlay immediately.
        // This also fires on natural game completion (e.g. after the DOOM2 finale).
        if (doomMain != null) {
            doom.DoomMain<byte[], byte[]> dm = (doom.DoomMain<byte[], byte[]>) doomMain;
            defines.gamestate_t gs = dm.gamestate;
            if (gs == defines.gamestate_t.GS_LEVEL) {
                wasInGame = true;
            } else if (wasInGame && gs == defines.gamestate_t.GS_DEMOSCREEN) {
                logger.info("Game returned to title screen after playing — signalling GAME_ENDED");
                wasInGame = false;
                currentFrame.set("GAME_ENDED");
                synchronized (frameLock) { frameLock.notifyAll(); }
                running = false;
                return;
            }
        }

        processInputEvents();
        collectSoundEvents();
        collectMusicState();

        // Copy raw pixels — engine reuses frameData.framebuffer on the next frame.
        // Hand off to encoder thread; do NOT encode here (blocks game loop / TikcmdBus).
        byte[] fbCopy  = Arrays.copyOf(frameData.framebuffer, frameData.framebuffer.length);
        byte[] palCopy = Arrays.copyOf(frameData.palette,     frameData.palette.length);
        pendingRawFrame.set(new FrameData(fbCopy, palCopy, frameData.width, frameData.height));

        int frame = frameCounter.getAndIncrement();
        if (frame % (35 * 5) == 0) {
            logP2PStatus(frame);
        }
    }

    /** Logs P2P gametic heartbeat every 5 seconds in multiplayer mode. */
    @SuppressWarnings("unchecked")
    private void logP2PStatus(int frame) {
        if (!p2pMode || doomMain == null) return;
        try {
            doom.DoomMain<byte[], byte[]> dm = (doom.DoomMain<byte[], byte[]>) doomMain;
            // gametic is public on DoomStatus; maketic/nettics are package-private (logged by P2PNetDriver)
            logger.debug("[P2P slot={}] heartbeat: frame={}, gametic={}", p2pSlot, frame, dm.gametic);
        } catch (Exception e) {
            // ignore — DoomMain fields may be in flux during startup
        }
    }

    @SuppressWarnings("unchecked")
    private void processInputEvents() {
        if (doomMain == null) return;
        doom.DoomMain<byte[], byte[]> dm = (doom.DoomMain<byte[], byte[]>) doomMain;
        Set<Integer> current = currentPressedKeys.get();

        for (Integer kc : current) {
            if (!prevKeys.contains(kc)) injectKeyEvent(dm, kc, true);
        }
        for (Integer kc : prevKeys) {
            if (!current.contains(kc)) injectKeyEvent(dm, kc, false);
        }
        prevKeys = new HashSet<>(current);
    }

    private void injectKeyEvent(doom.DoomMain<byte[], byte[]> dm, int jsKeyCode, boolean pressed) {
        g.Signals.ScanCode sc = KEY_MAP.get(jsKeyCode);
        if (sc != null) {
            dm.PostEvent(pressed ? sc.doomEventDown : sc.doomEventUp);
        }
    }

    @SuppressWarnings("unchecked")
    private void collectSoundEvents() {
        if (doomMain == null) return;
        try {
            doom.DoomMain<byte[], byte[]> dm = (doom.DoomMain<byte[], byte[]>) doomMain;
            if (dm.soundDriver instanceof s.HeadlessSoundDriver) {
                s.HeadlessSoundDriver drv = (s.HeadlessSoundDriver) dm.soundDriver;
                for (s.HeadlessSoundDriver.SoundEvent ev : drv.pollSoundEvents()) {
                    // In P2P mode AdjustSoundParams already ran relative to this engine's
                    // consoleplayer, so vol/sep are correct without any recalculation.
                    soundQueue.offer(new SoundEventDTO(ev.sfxName, ev.volume, ev.separation, ev.pitch));
                }
            }
        } catch (Exception e) {
            logger.warn("Error collecting sound events", e);
        }
    }

    @SuppressWarnings("unchecked")
    private void collectMusicState() {
        if (doomMain == null) return;
        try {
            doom.DoomMain<byte[], byte[]> dm = (doom.DoomMain<byte[], byte[]>) doomMain;
            if (dm.music instanceof s.HeadlessMusicDriver) {
                s.HeadlessMusicDriver drv = (s.HeadlessMusicDriver) dm.music;
                if (drv.hasChanged()) {
                    // Pre-convert MUS → standard MIDI here (in the child CL where s.MusReader is available).
                    // The gateway cannot reference s.MusReader, so we pass ready-made MIDI bytes in the DTO.
                    byte[] midiData = null;
                    byte[] musData  = drv.getCurrentMusData();
                    if (musData != null) {
                        try {
                            java.io.ByteArrayInputStream  bis  = new java.io.ByteArrayInputStream(musData);
                            javax.sound.midi.Sequence     seq  = s.MusReader.getSequence(bis);
                            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                            javax.sound.midi.MidiSystem.write(seq, 1, baos);
                            midiData = baos.toByteArray();
                            logger.debug("MUS→MIDI conversion: {} MUS bytes → {} MIDI bytes",
                                musData.length, midiData.length);
                        } catch (Exception e) {
                            logger.warn("MUS→MIDI conversion failed", e);
                        }
                    }
                    latestMusic.set(new MusicStateDTO(midiData, drv.isPlaying(), drv.isLooping(), drv.getVolume()));
                    drv.clearChanged();
                }
            }
        } catch (Exception e) {
            logger.warn("Error collecting music state", e);
        }
    }
}
