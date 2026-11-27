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

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Deque;
import java.util.Iterator;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owner (master) password, optional play password, and persisted settings.
 *
 * <ul>
 *   <li><b>Master password</b> — set by the gateway owner on first visit; unlocks the
 *       settings panel. Stored as a salted PBKDF2 hash, never in plain text.</li>
 *   <li><b>Setup code</b> — random, regenerated at every module start and printed to the
 *       gateway log. Required to set the master password the first time, and to reset a
 *       forgotten one, so only someone with access to the gateway log can claim ownership.
 *       Rotated after each successful use.</li>
 *   <li><b>Play password</b> — optional (off by default) shared code players enter to start
 *       or join games. Stored in plain text so the owner can view and share it.</li>
 * </ul>
 *
 * Settings persist in {@code doom-settings.properties} next to the WAD folders, so they
 * survive gateway restarts and module upgrades. If the file cannot be written, settings
 * still apply until the next restart and {@link #isPersistent()} reports false.
 */
public class AccessControl {

    private static final Logger logger = LoggerFactory.getLogger("DOOM.Access");

    /** Alphabet for generated codes: no 0/O, 1/I/L, so codes are easy to read aloud. */
    private static final char[] CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789".toCharArray();

    public static final int MIN_MASTER_LENGTH = 8;
    public static final int MIN_PLAY_LENGTH   = 6;
    public static final int MAX_PASSWORD_LENGTH = 128;

    private static final int    PBKDF2_ITERATIONS = 600_000;   // OWASP 2023 for PBKDF2-HMAC-SHA256
    private static final long   OWNER_SESSION_MS  = 12L * 60 * 60 * 1000;
    private static final int    MAX_OWNER_SESSIONS = 32;
    private static final int    MAX_FAILURES      = 5;
    private static final long   FAILURE_WINDOW_MS = 5L * 60 * 1000;

    private final SecureRandom rng = new SecureRandom();
    private final File settingsFile;

    // ── persisted ──
    private volatile String  masterHash;       // base64, null = not set up yet
    private volatile String  masterSalt;       // base64
    private volatile int     masterIterations = PBKDF2_ITERATIONS;
    private volatile boolean playRequired;     // default false
    private volatile String  playPassword;     // null until one is set
    private volatile boolean persistent = true;

    // ── in memory ──
    private volatile String setupCode;
    private final Map<String, Long>    ownerSessions = new ConcurrentHashMap<>();   // token → expiry
    private final Map<String, Boolean> playTokens    = new ConcurrentHashMap<>();
    private final Map<String, Deque<Long>> failures  = new ConcurrentHashMap<>();   // remote addr → times

    public AccessControl(File settingsFile) {
        this.settingsFile = settingsFile;
        load();
        rotateSetupCode();
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Setup code
    // ═════════════════════════════════════════════════════════════════════════

    private void rotateSetupCode() {
        setupCode = generateCode(12);
        logger.info("DOOM setup code: {}  (enter it at /system/doom to {} the master password)",
            setupCode, isSetUp() ? "reset" : "set");
    }

    public boolean isSetUp() { return masterHash != null; }

    /**
     * Sets (first run) or resets (forgotten) the master password. Requires the setup code
     * from the gateway log. Returns null on success, or an error message.
     */
    public synchronized String setMasterWithSetupCode(String code, String newPassword) {
        if (!equalsCodeIgnoringFormat(code, setupCode)) return "Incorrect setup code.";
        String err = checkLength(newPassword, MIN_MASTER_LENGTH, "Master password");
        if (err != null) return err;
        boolean wasSetUp = isSetUp();
        storeMaster(newPassword);
        ownerSessions.clear();                 // a reset logs everyone else out
        logger.info("DOOM master password {} using the setup code", wasSetUp ? "reset" : "set");
        rotateSetupCode();                     // single use
        return save();
    }

    /** Changes the master password; caller must already be logged in. Returns null or an error. */
    public synchronized String changeMaster(String current, String newPassword) {
        if (!verifyMaster(current)) return "Current password is incorrect.";
        String err = checkLength(newPassword, MIN_MASTER_LENGTH, "Master password");
        if (err != null) return err;
        storeMaster(newPassword);
        ownerSessions.clear();
        logger.info("DOOM master password changed");
        return save();
    }

    private void storeMaster(String password) {
        byte[] salt = new byte[16];
        rng.nextBytes(salt);
        masterSalt = Base64.getEncoder().encodeToString(salt);
        masterIterations = PBKDF2_ITERATIONS;
        masterHash = Base64.getEncoder().encodeToString(pbkdf2(password, salt, masterIterations));
    }

    public boolean verifyMaster(String password) {
        if (masterHash == null || password == null || password.isEmpty()) return false;
        byte[] expected = Base64.getDecoder().decode(masterHash);
        byte[] actual = pbkdf2(password, Base64.getDecoder().decode(masterSalt), masterIterations);
        return MessageDigest.isEqual(expected, actual);
    }

    private static byte[] pbkdf2(String password, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, 256);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("PBKDF2 unavailable", e);
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Owner sessions (cookie)
    // ═════════════════════════════════════════════════════════════════════════

    public String createOwnerSession() {
        long now = System.currentTimeMillis();
        ownerSessions.values().removeIf(exp -> exp < now);
        if (ownerSessions.size() >= MAX_OWNER_SESSIONS) {          // drop the oldest
            ownerSessions.entrySet().stream()
                .min(Map.Entry.comparingByValue())
                .ifPresent(e -> ownerSessions.remove(e.getKey()));
        }
        String token = randomHex(32);
        ownerSessions.put(token, now + OWNER_SESSION_MS);
        return token;
    }

    public boolean isOwner(String token) {
        if (token == null) return false;
        Long exp = ownerSessions.get(token);
        if (exp == null) return false;
        if (exp < System.currentTimeMillis()) { ownerSessions.remove(token); return false; }
        return true;
    }

    public void endOwnerSession(String token) { if (token != null) ownerSessions.remove(token); }

    public static long ownerSessionSeconds() { return OWNER_SESSION_MS / 1000; }

    // ═════════════════════════════════════════════════════════════════════════
    // Play password
    // ═════════════════════════════════════════════════════════════════════════

    public boolean isPlayRequired() { return playRequired; }
    public String  getPlayPassword() { return playPassword; }

    /**
     * Updates the play password settings (owner only). {@code password} may be null to keep the
     * current one. Turning the requirement on with no password set generates one.
     * Any change revokes all issued play tokens. Returns null or an error message.
     */
    public synchronized String updatePlay(boolean required, String password) {
        if (password != null) {
            password = password.trim();
            String err = checkLength(password, MIN_PLAY_LENGTH, "Play password");
            if (err != null) return err;
        }
        boolean changed = required != playRequired
            || (password != null && !password.equals(playPassword));
        if (password != null) playPassword = password;
        if (required && playPassword == null) playPassword = generatePlayPassword();
        playRequired = required;
        if (changed) {
            playTokens.clear();
            logger.info("DOOM play password {} (existing player tokens revoked)",
                required ? "required" : "not required");
        }
        return save();
    }

    /** Checks a play password. Case-insensitive; dashes and spaces are ignored. */
    public boolean verifyPlayPassword(String pw) {
        String expected = playPassword;
        return expected != null && equalsCodeIgnoringFormat(pw, expected);
    }

    public String issuePlayToken() {
        String token = randomHex(16);
        playTokens.put(token, Boolean.TRUE);
        return token;
    }

    /** True if games may start for a request carrying this token. */
    public boolean playAllowed(String token) {
        return !playRequired || (token != null && playTokens.containsKey(token));
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Brute-force throttling (per remote address)
    // ═════════════════════════════════════════════════════════════════════════

    /** True if this address has too many recent failures and must wait. */
    public boolean isLockedOut(String addr) {
        Deque<Long> d = failures.get(addr);
        if (d == null) return false;
        synchronized (d) {
            prune(d);
            return d.size() >= MAX_FAILURES;
        }
    }

    public void recordFailure(String addr) {
        Deque<Long> d = failures.computeIfAbsent(addr, k -> new ArrayDeque<>());
        synchronized (d) { prune(d); d.addLast(System.currentTimeMillis()); }
        logger.warn("DOOM: failed password/code attempt from {}", addr);
        if (failures.size() > 1000) failures.clear();                 // bound memory
    }

    public void clearFailures(String addr) { failures.remove(addr); }

    private static void prune(Deque<Long> d) {
        long cutoff = System.currentTimeMillis() - FAILURE_WINDOW_MS;
        for (Iterator<Long> it = d.iterator(); it.hasNext(); ) { if (it.next() < cutoff) it.remove(); else break; }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Persistence
    // ═════════════════════════════════════════════════════════════════════════

    public boolean isPersistent() { return persistent; }

    private void load() {
        if (!settingsFile.isFile()) {
            logger.info("DOOM settings: {} not found — first run (play password off)", settingsFile);
            return;
        }
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(settingsFile.toPath())) {
            p.load(in);
        } catch (IOException e) {
            logger.error("DOOM settings: could not read {} — using defaults", settingsFile, e);
            return;
        }
        masterHash = blankToNull(p.getProperty("master.hash"));
        masterSalt = blankToNull(p.getProperty("master.salt"));
        masterIterations = parseInt(p.getProperty("master.iterations"), PBKDF2_ITERATIONS);
        if (masterHash == null || masterSalt == null) { masterHash = null; masterSalt = null; }
        playRequired = Boolean.parseBoolean(p.getProperty("play.required", "false"));
        playPassword = blankToNull(p.getProperty("play.password"));
        if (playRequired && playPassword == null) playRequired = false;
        int max = parseInt(p.getProperty("maxSessions"), -1);
        if (max > 0) SessionManager.setMaxSessions(max);
        logger.info("DOOM settings loaded from {} (master password {}, play password {})",
            settingsFile, isSetUp() ? "set" : "not set", playRequired ? "required" : "off");
    }

    /** Writes all settings atomically. Returns null on success or an error message. */
    public synchronized String save() {
        Properties p = new Properties();
        if (masterHash != null) {
            p.setProperty("master.hash", masterHash);
            p.setProperty("master.salt", masterSalt);
            p.setProperty("master.iterations", Integer.toString(masterIterations));
        }
        p.setProperty("play.required", Boolean.toString(playRequired));
        if (playPassword != null) p.setProperty("play.password", playPassword);
        p.setProperty("maxSessions", Integer.toString(SessionManager.getMaxSessions()));
        try {
            Path dir = settingsFile.getParentFile().toPath();
            Files.createDirectories(dir);
            Path tmp = Files.createTempFile(dir, ".doom-settings", ".tmp");
            try {
                restrictToOwner(tmp);
                try (OutputStream out = Files.newOutputStream(tmp)) {
                    p.store(out, "Ignition DOOM settings - managed by the module (master password is a PBKDF2 hash)");
                }
                Files.move(tmp, settingsFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(tmp);
            }
            persistent = true;
            return null;
        } catch (IOException | RuntimeException e) {
            persistent = false;
            logger.error("DOOM settings: could not save {} — changes apply until the gateway restarts", settingsFile, e);
            return SAVE_WARNING_PREFIX + "could not write " + settingsFile + " - changes will be lost on restart.";
        }
    }

    private static final String SAVE_WARNING_PREFIX = "Saved for now, but ";

    /** True if an error string from this class only means "applied, but not persisted". */
    public static boolean isSaveWarning(String msg) {
        return msg != null && msg.startsWith(SAVE_WARNING_PREFIX);
    }

    private static void restrictToOwner(Path f) {
        try { Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rw-------")); }
        catch (UnsupportedOperationException | IOException ignored) { /* non-POSIX filesystem */ }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Helpers
    // ═════════════════════════════════════════════════════════════════════════

    /** A strong play password: 16 chars, ~79 bits, formatted XXXX-XXXX-XXXX-XXXX. */
    public String generatePlayPassword() { return generateCode(16); }

    /** Random code from {@link #CODE_ALPHABET}, grouped in fours with dashes. */
    private String generateCode(int length) {
        StringBuilder sb = new StringBuilder(length + length / 4);
        for (int i = 0; i < length; i++) {
            if (i > 0 && i % 4 == 0) sb.append('-');
            sb.append(CODE_ALPHABET[rng.nextInt(CODE_ALPHABET.length)]);
        }
        return sb.toString();
    }

    private static boolean equalsCodeIgnoringFormat(String a, String b) {
        if (a == null || b == null) return false;
        byte[] x = normalizeCode(a).getBytes(StandardCharsets.UTF_8);
        byte[] y = normalizeCode(b).getBytes(StandardCharsets.UTF_8);
        return x.length > 0 && MessageDigest.isEqual(x, y);
    }

    private static String normalizeCode(String s) {
        return s.replaceAll("[\\s-]", "").toUpperCase();
    }

    private static String checkLength(String pw, int min, String what) {
        if (pw == null || pw.length() < min) return what + " must be at least " + min + " characters.";
        if (pw.length() > MAX_PASSWORD_LENGTH) return what + " is too long.";
        return null;
    }

    private String randomHex(int bytes) {
        byte[] b = new byte[bytes];
        rng.nextBytes(b);
        StringBuilder sb = new StringBuilder(bytes * 2);
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s.trim(); }

    private static int parseInt(String s, int dflt) {
        try { return s == null ? dflt : Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return dflt; }
    }
}
