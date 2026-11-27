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
import com.doom.common.MusicStateDTO;
import com.doom.common.SoundEventDTO;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Servlet handling DOOM keyboard input and serving game pages.
 *
 * Phase 5.2: Session-aware routing. Every endpoint (except /play, /health, /static/*, music/sf/*)
 * requires a ?session=UUID query parameter identifying the browser's DoomSession.
 *
 * GET  /system/doom/play?wad=NAME&session=UUID  — creates/resumes session, serves player page
 * GET  /system/doom/health                      — module health (no session needed)
 * GET  /system/doom/frame?session=UUID          — current PNG frame as data URI
 * GET  /system/doom/sounds?session=UUID         — JSON list of available sound names
 * GET  /system/doom/sounds/{name}?session=UUID  — individual sound WAV
 * GET  /system/doom/events?session=UUID         — pending sound events as JSON
 * GET  /system/doom/music/status?session=UUID   — music playback status as JSON
 * GET  /system/doom/music/midi?session=UUID     — current track as standard MIDI file
 * GET  /system/doom/music/sf/{name}             — FluidR3_GM soundfont JS (no session)
 * GET  /system/doom/static/{file}               — bundled static assets (no session)
 * POST /system/doom/input?session=UUID          — pressed key state from browser JS
 * POST /system/doom/console?session=UUID        — warp/cheat console command
 *
 * Access control (see {@link AccessControl}):
 * POST /system/doom/auth                        — play password → play token (only if one is required)
 * GET  /system/doom/owner/state                 — setup/login/play-password state for the dashboard
 * POST /system/doom/owner/{setup|login|logout|password|settings|generate|end}
 *                                               — owner actions; same-origin, cookie session
 * GET  /system/doom/admin                       — legacy URL, redirects to the dashboard's Settings
 */
public class DoomInputServlet extends HttpServlet {

    private static final Logger logger = LoggerFactory.getLogger("DOOM.Servlet");

    /**
     * An asset sub-folder of the gateway's user-lib/doom/ directory ("soundfonts", "branding"),
     * resolved from the gateway's real install location; null until the module has initialized.
     */
    private static File assetDir(String name) {
        SessionManager sm = GatewayHook.getSessionManager();
        return sm == null ? null : new File(sm.getBaseDir(), name);
    }

    /** True for a plain file name: no path separators of either OS and no parent references. */
    private static boolean isPlainFileName(String name) {
        return !name.isEmpty() && !name.contains("..") && !name.contains("/") && !name.contains("\\");
    }

    // ── GET dispatcher ────────────────────────────────────────────────────────

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String path = req.getPathInfo();
        if (path == null) path = "/";

        // Owner endpoints first: route matching below uses contains(), and "/owner/..."
        // paths must never fall through to e.g. the "play" route.
        String ownerAction = ownerAction(path);
        if (ownerAction != null) {
            handleOwnerGet(ownerAction, req, resp);
            return;
        }

        if (path.contains("static/")) {
            String fileName = path.substring(path.indexOf("static/") + "static/".length());
            serveStaticResource(fileName, resp);

        } else if (path.contains("music/sf/")) {
            String sfName = path.substring(path.indexOf("music/sf/") + "music/sf/".length());
            serveSoundfontFile(sfName, resp);

        } else if (path.contains("admin")) {
            // The token-in-URL admin page was replaced by the dashboard's Settings panel.
            resp.sendRedirect("/system/doom/?settings=1");

        } else if (path.contains("health")) {
            serveHealth(resp);

        } else if (path.contains("/match")) {
            handleMatchGet(path, req, resp);

        } else if (path.contains("sounds/")) {
            String soundName = path.substring(path.indexOf("sounds/") + "sounds/".length());
            DoomSession session = resolveRunningSession(req, resp);
            if (session == null) return;
            serveSound(session, soundName, resp);

        } else if (path.contains("sounds")) {
            DoomSession session = resolveRunningSession(req, resp);
            if (session == null) return;
            serveSoundList(session, resp);

        } else if (path.contains("events/stream")) {
            DoomSession session = resolveRunningSession(req, resp);
            if (session == null) return;
            streamSoundEvents(session, resp);

        } else if (path.contains("events")) {
            DoomSession session = resolveRunningSession(req, resp);
            if (session == null) return;
            serveSoundEvents(session, resp);

        } else if (path.contains("music/status")) {
            DoomSession session = resolveRunningSession(req, resp);
            if (session == null) return;
            serveMusicStatus(session, resp);

        } else if (path.contains("music/midi")) {
            DoomSession session = resolveRunningSession(req, resp);
            if (session == null) return;
            serveMidiData(session, resp);

        } else if (path.contains("frame/stream")) {
            DoomSession session = resolveRunningSession(req, resp);
            if (session == null) return;
            streamFrames(session, resp);

        } else if (path.contains("frame")) {
            // Pre-check GAME_ENDED before resolveRunningSession: when running=false,
            // get() returns null → 404, blocking the sentinel from ever being served as 410.
            String fSid = req.getParameter("session");
            if (fSid != null) {
                SessionManager fSm = GatewayHook.getSessionManager();
                if (fSm != null) {
                    DoomSession fSession = fSm.getSession(fSid);
                    if (fSession != null && "GAME_ENDED".equals(fSession.getCurrentFrame())) {
                        resp.setStatus(HttpServletResponse.SC_GONE); // 410
                        return;
                    }
                }
            }
            DoomSession session = resolveRunningSession(req, resp);
            if (session == null) return;
            serveFrame(session, resp);

        } else if (path.endsWith("/controls")) {   // getPathInfo() includes the servlet name ("/doom/controls")
            resp.setContentType("text/html; charset=UTF-8");
            resp.setHeader("Cache-Control", "no-cache");
            resp.getWriter().write(DoomControlsPage.getHTML());

        } else if (path.contains("play")) {
            servePlayPage(req, resp);

        } else if (path.contains("keyboard")) {
            resp.setContentType("text/html; charset=UTF-8");
            resp.setHeader("Cache-Control", "no-cache");
            resp.getWriter().write(KeyboardCapturePage.getHTML());

        } else if (path.contains("branding/")) {
            String brandingFile = path.substring(path.indexOf("branding/") + "branding/".length());
            serveBrandingFile(brandingFile, resp);

        } else if (path.equals("/") || path.equals("/doom") || path.equals("/doom/")) {
            serveLandingPage(resp);

        } else {
            resp.setStatus(404);
            resp.getWriter().write("Not found: " + path);
        }
    }

    // ── POST dispatcher ───────────────────────────────────────────────────────

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String path = req.getPathInfo();
        if (path == null) path = "/";

        // Owner endpoints: same-origin only (no CORS header, Origin checked), handled first.
        String ownerAction = ownerAction(path);
        if (ownerAction != null) {
            handleOwnerPost(ownerAction, req, resp);
            return;
        }

        resp.setHeader("Access-Control-Allow-Origin", "*");

        if (path.contains("/match")) {
            handleMatchPost(path, req, resp);
        } else if (path.contains("auth")) {
            handlePlayAuth(req, resp);
        } else if (path.contains("console")) {
            DoomSession session = resolveRunningSession(req, resp);
            if (session == null) return;
            serveConsoleCommand(session, req, resp);
        } else {
            // Default: keyboard input. Sound events are delivered via WebSocket (/doom-ws), with SSE fallback.
            // POST /system/doom/input?session=UUID  →  200 OK (no body)
            // Body: InputMessages lines (key list, m:mouse, opt:...).
            String body = new String(req.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            DoomSession session = resolveSession(req);
            if (session != null) {
                InputMessages.dispatchAll(body, InputMessages.forSession(session));
            }
            resp.setStatus(HttpServletResponse.SC_OK);
        }
    }

    @Override
    protected void doOptions(HttpServletRequest req, HttpServletResponse resp) {
        resp.setHeader("Access-Control-Allow-Origin", "*");
        resp.setHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        resp.setHeader("Access-Control-Allow-Headers", "Content-Type");
        resp.setStatus(200);
    }

    // ── /auth — play password exchange ───────────────────────────────────────

    /**
     * POST /system/doom/auth  (body: plain-text password)
     * Checks the play password and returns a play token as JSON.
     * 200 {"token":"<hex>"} on success, 401 on a wrong password, 429 after too many
     * failures, 503 if not ready. When no play password is required, any request
     * succeeds (older pages may still call this).
     */
    private void handlePlayAuth(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setHeader("Cache-Control", "no-store");
        SessionManager sm = GatewayHook.getSessionManager();
        if (sm == null) { resp.setStatus(503); return; }
        AccessControl ac = sm.getAccess();
        // Separate throttle buckets: behind NAT/Docker every client shares one address, so
        // players mistyping the play password must not lock the owner out (and vice versa).
        String addr = "play:" + req.getRemoteAddr();
        if (ac.isPlayRequired() && ac.isLockedOut(addr)) {
            resp.setStatus(429);
            resp.setContentType("text/plain");
            resp.getWriter().write("Too many attempts - wait a few minutes and try again");
            return;
        }

        String body = new String(req.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        if (!ac.isPlayRequired() || ac.verifyPlayPassword(body)) {
            ac.clearFailures(addr);
            resp.setContentType("application/json");
            resp.getWriter().write("{\"token\":\"" + ac.issuePlayToken() + "\"}");
        } else {
            ac.recordFailure(addr);
            resp.setStatus(401);
            resp.setContentType("text/plain");
            resp.getWriter().write("Incorrect password");
        }
    }

    /**
     * Enforces the play password (if one is required) for pages that START games:
     * single-player (/play) and creating a match. Joining a match needs only its ID.
     * A logged-in owner (cookie) is always allowed. These are browser navigations, so a
     * refusal is a small HTML page pointing back to the dashboard.
     */
    private boolean checkPlayAuth(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        SessionManager sm = GatewayHook.getSessionManager();
        if (sm == null) { resp.setStatus(503); return false; }
        AccessControl ac = sm.getAccess();
        if (ac.playAllowed(req.getParameter("auth")) || ac.isOwner(ownerCookie(req))) return true;
        resp.setStatus(403);
        resp.setContentType("text/html; charset=UTF-8");
        resp.setHeader("Cache-Control", "no-store");
        resp.getWriter().write("<!DOCTYPE html><html><head><meta charset='UTF-8'>"
            + "<title>DOOM — Password required</title></head>"
            + "<body style='background:#0d0d0d;color:#c8c8c8;font-family:monospace;padding:24px'>"
            + "<h2 style='color:#ff4444;letter-spacing:3px'>PASSWORD REQUIRED</h2>"
            + "<p>This gateway requires a play password. Open the "
            + "<a href='/system/doom/' style='color:#ff6b6b'>Ignition DOOM dashboard</a>, "
            + "enter the password, then start or join the game again.</p>"
            + "</body></html>");
        return false;
    }

    // ── /owner/* — master password, first-run setup, settings ───────────────

    private static final String OWNER_COOKIE = "doomOwner";

    /**
     * Returns the action name for /system/doom/owner/{action} requests, else null.
     * Ignition includes the servlet name in getPathInfo(), so the path arrives as
     * "/doom/owner/state"; plain "/owner/state" is accepted too.
     */
    private static String ownerAction(String path) {
        String p = path.startsWith("/doom/") ? path.substring("/doom".length()) : path;
        return p.startsWith("/owner/") ? p.substring("/owner/".length()) : null;
    }

    /** GET /owner/state[?auth=TOKEN] — what the dashboard needs to decide which prompts to show. */
    private void handleOwnerGet(String action, HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setHeader("Cache-Control", "no-store");
        SessionManager sm = GatewayHook.getSessionManager();
        if (sm == null) { resp.setStatus(503); return; }
        if (!action.equals("state")) { resp.setStatus(404); return; }
        AccessControl ac = sm.getAccess();
        boolean owner = ac.isOwner(ownerCookie(req));
        // A logged-in owner never has to type the play password (checkPlayAuth accepts the cookie).
        boolean tokenValid = owner || ac.playAllowed(req.getParameter("auth"));
        StringBuilder j = new StringBuilder("{");
        j.append("\"setupRequired\":").append(!ac.isSetUp());
        j.append(",\"owner\":").append(owner);
        j.append(",\"playRequired\":").append(ac.isPlayRequired());
        j.append(",\"playTokenValid\":").append(tokenValid);
        j.append(",\"persistent\":").append(ac.isPersistent());
        if (owner) {
            j.append(",\"playPassword\":").append(jsonStr(ac.getPlayPassword()));
            j.append(",\"maxSessions\":").append(SessionManager.getMaxSessions());
        }
        j.append("}");
        resp.setContentType("application/json; charset=UTF-8");
        resp.getWriter().write(j.toString());
    }

    /**
     * POST /owner/{setup|login|logout|password|settings|end|generate}, form-encoded.
     * Responds with JSON: {"ok":true,...} or {"error":"message"}.
     */
    private void handleOwnerPost(String action, HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setHeader("Cache-Control", "no-store");
        SessionManager sm = GatewayHook.getSessionManager();
        if (sm == null) { resp.setStatus(503); return; }
        if (!isSameOrigin(req)) { ownerJson(resp, 403, "Cross-origin request rejected."); return; }
        AccessControl ac = sm.getAccess();
        String addr = "owner:" + req.getRemoteAddr();

        switch (action) {
            case "setup": {   // set (first run) or reset (forgotten) the master password with the setup code
                if (ac.isLockedOut(addr)) { ownerJson(resp, 429, "Too many attempts - wait a few minutes."); return; }
                String err = ac.setMasterWithSetupCode(req.getParameter("code"), req.getParameter("password"));
                if (err != null && err.startsWith("Incorrect")) { ac.recordFailure(addr); ownerJson(resp, 401, err); return; }
                if (err != null && !AccessControl.isSaveWarning(err)) { ownerJson(resp, 400, err); return; }
                ac.clearFailures(addr);
                setOwnerCookie(req, resp, ac.createOwnerSession());
                ownerOk(resp, err);
                return;
            }
            case "login": {
                if (!ac.isSetUp()) { ownerJson(resp, 409, "No master password has been set yet."); return; }
                if (ac.isLockedOut(addr)) { ownerJson(resp, 429, "Too many attempts - wait a few minutes."); return; }
                if (!ac.verifyMaster(req.getParameter("password"))) {
                    ac.recordFailure(addr);
                    ownerJson(resp, 401, "Incorrect password.");
                    return;
                }
                ac.clearFailures(addr);
                setOwnerCookie(req, resp, ac.createOwnerSession());
                ownerOk(resp, null);
                return;
            }
            case "logout":
                ac.endOwnerSession(ownerCookie(req));
                clearOwnerCookie(req, resp);
                ownerOk(resp, null);
                return;
            default:
                break;
        }

        // Everything below needs a logged-in owner.
        if (!ac.isOwner(ownerCookie(req))) { ownerJson(resp, 401, "Not logged in."); return; }

        switch (action) {
            case "password": {
                if (ac.isLockedOut(addr)) { ownerJson(resp, 429, "Too many attempts - wait a few minutes."); return; }
                String err = ac.changeMaster(req.getParameter("current"), req.getParameter("password"));
                if (err != null && err.startsWith("Current")) { ac.recordFailure(addr); ownerJson(resp, 401, err); return; }
                if (err != null && !AccessControl.isSaveWarning(err)) { ownerJson(resp, 400, err); return; }
                ac.clearFailures(addr);
                setOwnerCookie(req, resp, ac.createOwnerSession());   // other sessions were logged out
                ownerOk(resp, err);
                return;
            }
            case "settings": {
                String maxStr = req.getParameter("maxSessions");
                if (maxStr != null) {
                    try { SessionManager.setMaxSessions(Integer.parseInt(maxStr.trim())); }
                    catch (NumberFormatException e) { ownerJson(resp, 400, "Max engines must be a number."); return; }
                }
                String required = req.getParameter("playRequired");
                String pw = req.getParameter("playPassword");
                if (pw != null && pw.isBlank()) pw = null;
                String err = required != null
                    ? ac.updatePlay(Boolean.parseBoolean(required), pw)
                    : ac.save();
                if (err != null && !AccessControl.isSaveWarning(err)) { ownerJson(resp, 400, err); return; }
                ownerOk(resp, err);
                return;
            }
            case "generate":
                resp.setContentType("application/json; charset=UTF-8");
                resp.getWriter().write("{\"ok\":true,\"password\":" + jsonStr(ac.generatePlayPassword()) + "}");
                return;
            case "end": {
                String sid = req.getParameter("session");
                String mid = req.getParameter("match");
                if (sid != null && !sid.isBlank()) sm.killSession(sid);
                else if (mid != null && !mid.isBlank()) sm.killMatch(mid);
                else { ownerJson(resp, 400, "Nothing to end."); return; }
                logger.info("Owner ended {} {}", sid != null ? "session" : "match", sid != null ? sid : mid);
                ownerOk(resp, null);
                return;
            }
            default:
                ownerJson(resp, 404, "Unknown action.");
        }
    }

    /** POSTs that change owner state must come from a page served by this gateway. */
    private static boolean isSameOrigin(HttpServletRequest req) {
        String origin = req.getHeader("Origin");
        if (origin == null) return true;            // same-origin GET-style tools; cookie is SameSite=Strict anyway
        String host = req.getHeader("Host");
        try {
            String authority = java.net.URI.create(origin).getRawAuthority();
            return authority != null && host != null && authority.equalsIgnoreCase(host);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String ownerCookie(HttpServletRequest req) {
        jakarta.servlet.http.Cookie[] cookies = req.getCookies();
        if (cookies == null) return null;
        for (jakarta.servlet.http.Cookie c : cookies) {
            if (OWNER_COOKIE.equals(c.getName())) return c.getValue();
        }
        return null;
    }

    // Written by hand: Servlet 5 Cookie has no SameSite attribute.
    private static void setOwnerCookie(HttpServletRequest req, HttpServletResponse resp, String token) {
        resp.addHeader("Set-Cookie", OWNER_COOKIE + "=" + token + "; Path=/system/doom; Max-Age="
            + AccessControl.ownerSessionSeconds() + "; HttpOnly; SameSite=Strict" + (req.isSecure() ? "; Secure" : ""));
    }

    private static void clearOwnerCookie(HttpServletRequest req, HttpServletResponse resp) {
        resp.addHeader("Set-Cookie", OWNER_COOKIE + "=; Path=/system/doom; Max-Age=0; HttpOnly; SameSite=Strict"
            + (req.isSecure() ? "; Secure" : ""));
    }

    private static void ownerOk(HttpServletResponse resp, String warning) throws IOException {
        resp.setContentType("application/json; charset=UTF-8");
        resp.getWriter().write(warning == null ? "{\"ok\":true}" : "{\"ok\":true,\"warning\":" + jsonStr(warning) + "}");
    }

    private static void ownerJson(HttpServletResponse resp, int status, String error) throws IOException {
        resp.setStatus(status);
        resp.setContentType("application/json; charset=UTF-8");
        resp.getWriter().write("{\"error\":" + jsonStr(error) + "}");
    }

    private static String jsonStr(String s) {
        if (s == null) return "null";
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"':  b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '<':  b.append("\\u003c"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c)); else b.append(c);
            }
        }
        return b.append('"').toString();
    }

    // ── /play — session creation/resume ──────────────────────────────────────

    /**
     * Creates or resumes a DOOM session for this browser tab.
     * The session UUID is injected by the server into the page HTML, so the browser
     * always sends back the same ID assigned here.
     *
     * Query params:
     *   ?wad=NAME     WAD file stem (default: DOOM1). Sanitized server-side.
     *   ?session=UUID Browser-generated UUID (new tab → generates one client-side;
     *                 refreshed page → reuses existing one from sessionStorage).
     */
    private void servePlayPage(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!checkPlayAuth(req, resp)) return;
        String sessionId  = req.getParameter("session");
        String wadName    = req.getParameter("wad");
        String pwadName   = req.getParameter("pwad");
        String frameFormat = "jpeg".equalsIgnoreCase(req.getParameter("format")) ? "jpeg" : "png";
        int skill = parseIntParam(req, "skill", 2, 0, 4);
        if (wadName == null || wadName.trim().isEmpty()) wadName = SessionManager.DEFAULT_WAD;
        GameplayOptions options = GameplayOptions.fromParams(req::getParameter);

        // Validate session ID format (UUID v4: 8-4-4-4-12)
        if (sessionId == null || !sessionId.matches(
                "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
            // Send back a page that generates a UUID and redirects to itself with it
            resp.setContentType("text/html; charset=UTF-8");
            resp.setHeader("Cache-Control", "no-cache");
            resp.getWriter().write(buildSessionInitPage(wadName, frameFormat, pwadName, skill, options));
            return;
        }

        SessionManager sm = GatewayHook.getSessionManager();
        if (sm == null) {
            resp.setStatus(503);
            resp.setContentType("text/html; charset=UTF-8");
            resp.getWriter().write(buildErrorPage("DOOM module not initialized",
                "The SessionManager failed to start. Check the gateway log for details."));
            return;
        }

        DoomSession session;
        try {
            session = sm.getOrCreate(sessionId, wadName, 0, 0, skill, frameFormat, pwadName, options);
        } catch (IllegalArgumentException e) {
            resp.setStatus(404);
            resp.setContentType("text/html; charset=UTF-8");
            resp.getWriter().write(buildErrorPage("WAD not found",
                "The WAD file '" + escapeHtml(wadName) + "' was not found on the server. "
                + "Available WADs must be in " + sm.getWadDir()));
            return;
        } catch (Exception e) {
            resp.setStatus(500);
            resp.setContentType("text/html; charset=UTF-8");
            resp.getWriter().write(buildErrorPage("Session start failed",
                "Could not start DOOM: " + escapeHtml(e.getMessage())));
            logger.error("Session start failed for session={} wad={}", sessionId, wadName, e);
            return;
        }

        if (session == null) {
            // At capacity
            resp.setStatus(503);
            resp.setContentType("text/html; charset=UTF-8");
            resp.getWriter().write(buildCapacityPage());
            return;
        }

        resp.setContentType("text/html; charset=UTF-8");
        resp.setHeader("Cache-Control", "no-cache");
        resp.getWriter().write(DoomPlayPage.getHTML(sessionId, wadName));
    }

    private static String escHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static String escUrl(String s) {
        try { return java.net.URLEncoder.encode(s, "UTF-8"); }
        catch (Exception e) { return s; }
    }

    // ── /health ───────────────────────────────────────────────────────────────

    private void serveHealth(HttpServletResponse resp) throws IOException {
        SessionManager sm = GatewayHook.getSessionManager();
        resp.setContentType("application/json; charset=UTF-8");
        resp.setHeader("Cache-Control", "no-store");
        if (sm == null) {
            resp.setStatus(503);
            resp.getWriter().write("{\"status\":\"unavailable\",\"error\":\"SessionManager not initialized\"}");
        } else {
            resp.getWriter().write(sm.getHealthJson());
        }
    }

    // ── /frame ────────────────────────────────────────────────────────────────

    private void serveFrame(DoomSession session, HttpServletResponse resp) throws IOException {
        String sentinel = session.getCurrentFrame();
        if (sentinel == null || sentinel.isEmpty()) {
            resp.setStatus(HttpServletResponse.SC_NO_CONTENT);
            return;
        }
        if ("GAME_ENDED".equals(sentinel)) {
            resp.setStatus(HttpServletResponse.SC_GONE); // 410 — engine exited cleanly
            return;
        }
        byte[] frameBytes = session.getCurrentFrameBytes();
        if (frameBytes == null || frameBytes.length == 0) {
            resp.setStatus(HttpServletResponse.SC_NO_CONTENT);
            return;
        }
        resp.setContentType(session.getFrameContentType());
        resp.setHeader("Cache-Control", "no-store, no-cache, must-revalidate");
        resp.setHeader("Pragma", "no-cache");
        resp.setHeader("Expires", "0");
        resp.getOutputStream().write(frameBytes);
    }

    // ── /sounds ───────────────────────────────────────────────────────────────

    private void serveSoundList(DoomSession session, HttpServletResponse resp) throws IOException {
        SessionManager sm = GatewayHook.getSessionManager();
        if (sm == null) {
            resp.setStatus(503);
            resp.setContentType("application/json; charset=UTF-8");
            resp.getWriter().write("{\"error\":\"SessionManager unavailable\"}");
            return;
        }

        String[] soundNames = sm.getSoundNames(session.getWadPath());

        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < soundNames.length; i++) {
            if (i > 0) json.append(",");
            json.append("\"").append(escapeJson(soundNames[i])).append("\"");
        }
        json.append("]");

        resp.setContentType("application/json; charset=UTF-8");
        // Must not be cached: list is empty until cache is built, non-empty after.
        resp.setHeader("Cache-Control", "no-store, no-cache");
        resp.getWriter().write(json.toString());
    }

    private void serveSound(DoomSession session, String soundName, HttpServletResponse resp)
            throws IOException {
        SessionManager sm = GatewayHook.getSessionManager();
        if (sm == null) {
            resp.setStatus(503);
            resp.getWriter().write("SessionManager unavailable");
            return;
        }

        byte[] wavData = sm.getSoundData(session.getWadPath(), soundName);
        if (wavData == null) {
            resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
            resp.getWriter().write("Sound not found: " + soundName);
            return;
        }

        resp.setContentType("audio/wav");
        resp.setHeader("Cache-Control", "public, max-age=3600");
        resp.setContentLength(wavData.length);
        resp.getOutputStream().write(wavData);
    }

    // ── /events ───────────────────────────────────────────────────────────────

    private void serveSoundEvents(DoomSession session, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json; charset=UTF-8");
        resp.setHeader("Cache-Control", "no-store, no-cache, must-revalidate");
        resp.getWriter().write(buildSoundEventsJson(session.pollSoundEvents()));
    }

    private String buildSoundEventsJson(SoundEventDTO[] events) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < events.length; i++) {
            if (i > 0) sb.append(",");
            SoundEventDTO ev = events[i];
            sb.append("{\"name\":\"").append(escapeJson(ev.sfxName)).append("\",")
              .append("\"volume\":").append(ev.volume).append(",")
              .append("\"separation\":").append(ev.separation).append(",")
              .append("\"pitch\":").append(ev.pitch).append("}");
        }
        return sb.append("]").toString();
    }

    // ── SSE sound+music events stream ─────────────────────────────────────────

    /**
     * GET /system/doom/events/stream?session=UUID
     * Server-Sent Events stream: sound events, music state changes, and latency pings.
     * Eliminates the 50ms heartbeat POST and the 500ms music status poll.
     * Blocks the Jetty thread for the session lifetime (acceptable for MAX_SESSIONS=4).
     */
    private void streamSoundEvents(DoomSession session, HttpServletResponse resp) throws IOException {
        resp.setContentType("text/event-stream; charset=UTF-8");
        resp.setHeader("Cache-Control", "no-cache");
        resp.setHeader("Connection", "close");
        resp.setHeader("X-Accel-Buffering", "no");
        resp.flushBuffer();
        java.io.PrintWriter out = resp.getWriter();

        MusicStateDTO lastSentMusic = null;
        long lastPingMs = 0;

        while (session.isRunning()) {
            boolean wrote = false;

            SoundEventDTO[] events = session.pollSoundEvents();
            if (events.length > 0) {
                out.print("event: sound\ndata: " + buildSoundEventsJson(events) + "\n\n");
                wrote = true;
            }

            MusicStateDTO music = session.peekMusicState();
            if (music != null && music != lastSentMusic) {
                lastSentMusic = music;
                out.print("event: music\ndata: {\"playing\":" + music.playing
                    + ",\"changed\":true,\"looping\":" + music.looping
                    + ",\"volume\":" + music.volume + "}\n\n");
                wrote = true;
            }

            long now = System.currentTimeMillis();
            if (now - lastPingMs >= 2000) {
                out.print("event: ping\ndata: " + now + "\n\n");
                lastPingMs = now;
                wrote = true;
            }

            if (wrote) {
                out.flush();
                if (out.checkError()) break;
            }

            try { Thread.sleep(10); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
        // Notify the client that the session has ended so it can show the overlay,
        // regardless of whether the MJPEG stream fires an error event.
        try { out.print("event: ended\ndata:\n\n"); out.flush(); } catch (Exception ignored) {}
    }

    /**
     * GET /system/doom/match/events/stream?match=ID&session=UUID
     * Same as streamSoundEvents() but for a deathmatch player slot.
     * Waits up to 60s for all engines to start before opening the stream.
     */
    private void streamMatchSoundEvents(MatchSession match, String browserSid,
                                        HttpServletResponse resp) throws IOException {
        int mySlot = browserSid != null ? match.getSlot(browserSid) : -1;
        if (mySlot < 0) return;
        // Wait up to 60 s for this player's engine. (Not for every engine: after another
        // player leaves, the match is no longer fully running, but this player still is.)
        long deadline = System.currentTimeMillis() + 60_000;
        while (!match.isPlayerRunning(mySlot) && match.isAlive() && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
        if (!match.isPlayerRunning(mySlot)) return;

        DoomSession slotSession = match.getSlotSession(browserSid);
        if (slotSession == null) return;

        resp.setContentType("text/event-stream; charset=UTF-8");
        resp.setHeader("Cache-Control", "no-cache");
        resp.setHeader("Connection", "close");
        resp.setHeader("X-Accel-Buffering", "no");
        resp.flushBuffer();
        java.io.PrintWriter out = resp.getWriter();

        MusicStateDTO lastSentMusic = null;
        long lastPingMs = 0;

        while (match.isPlayerRunning(mySlot)) {
            boolean wrote = false;

            SoundEventDTO[] events = slotSession.pollSoundEvents();
            if (events.length > 0) {
                out.print("event: sound\ndata: " + buildSoundEventsJson(events) + "\n\n");
                wrote = true;
            }

            MusicStateDTO music = slotSession.peekMusicState();
            if (music != null && music != lastSentMusic) {
                lastSentMusic = music;
                out.print("event: music\ndata: {\"playing\":" + music.playing
                    + ",\"changed\":true,\"looping\":" + music.looping
                    + ",\"volume\":" + music.volume + "}\n\n");
                wrote = true;
            }

            long now = System.currentTimeMillis();
            if (now - lastPingMs >= 2000) {
                out.print("event: ping\ndata: " + now + "\n\n");
                lastPingMs = now;
                wrote = true;
            }

            if (wrote) {
                out.flush();
                if (out.checkError()) break;
            }

            try { Thread.sleep(10); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
        try { out.print("event: ended\ndata:\n\n"); out.flush(); } catch (Exception ignored) {}
    }

    // ── /music/status ─────────────────────────────────────────────────────────

    /**
     * Returns the current music playback state as JSON.
     * Polls the runner for new state — if a new track has started, changed=true
     * and the browser should immediately fetch /music/midi to get the data.
     */
    private void serveMusicStatus(DoomSession session, HttpServletResponse resp) throws IOException {
        MusicStateDTO state = session.peekMusicState();   // non-null = new track pending MIDI fetch
        MusicStateDTO last  = session.getLastKnownMusic(); // last received state, never cleared

        // changed = new MIDI needs to be fetched (state != null means unconsumed track change)
        // playing/looping/volume fall back to lastKnownMusic so the client keeps playing
        // the current track after the MIDI has been consumed (cachedMusic cleared to null).
        boolean changed = state != null;
        boolean playing = state != null ? state.playing : (last != null && last.playing);
        boolean looping = state != null ? state.looping : (last != null && last.looping);
        int     volume  = state != null ? state.volume  : (last != null ? last.volume : 127);

        String json = "{"
            + "\"playing\":"  + playing + ","
            + "\"changed\":"  + changed  + ","
            + "\"looping\":"  + looping + ","
            + "\"volume\":"   + volume
            + "}";

        resp.setContentType("application/json; charset=UTF-8");
        resp.setHeader("Cache-Control", "no-store, no-cache, must-revalidate");
        resp.getWriter().write(json);
    }

    // ── /music/midi ───────────────────────────────────────────────────────────

    /**
     * Returns the current MIDI file and clears the pending music state.
     * Called by the browser after it sees changed=true in /music/status.
     * MIDI bytes are pre-converted from MUS by SessionRunner (which has access to s.MusReader).
     */
    private void serveMidiData(DoomSession session, HttpServletResponse resp) throws IOException {
        MusicStateDTO state = session.takeMusicState();
        if (state == null || state.midiData == null) {
            resp.setStatus(HttpServletResponse.SC_NO_CONTENT);
            return;
        }

        resp.setContentType("audio/midi");
        resp.setHeader("Cache-Control", "no-store, no-cache, must-revalidate");
        resp.setContentLength(state.midiData.length);
        resp.getOutputStream().write(state.midiData);
    }

    // ── /static/ and /music/sf/ ───────────────────────────────────────────────

    private void serveStaticResource(String fileName, HttpServletResponse resp) throws IOException {
        if (fileName.isEmpty() || fileName.contains("..") || fileName.contains("/")) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return;
        }
        try (InputStream is = getClass().getResourceAsStream("/static/" + fileName)) {
            if (is == null) {
                resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
                resp.getWriter().write("Static resource not found: " + fileName);
                return;
            }
            resp.setContentType(fileName.endsWith(".png") ? "image/png" : "application/javascript; charset=UTF-8");
            resp.setHeader("Cache-Control", "public, max-age=86400");
            byte[] data = is.readAllBytes();
            resp.setContentLength(data.length);
            resp.getOutputStream().write(data);
        }
    }

    private void serveSoundfontFile(String sfName, HttpServletResponse resp) throws IOException {
        if (!isPlainFileName(sfName)) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return;
        }
        File sfDir = assetDir("soundfonts");
        if (sfDir == null) { resp.setStatus(503); return; }
        File sfFile = new File(sfDir, sfName);
        if (!sfFile.exists() || !sfFile.isFile()) {
            resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        resp.setContentType("application/javascript; charset=UTF-8");
        resp.setHeader("Cache-Control", "public, max-age=86400");
        byte[] data = Files.readAllBytes(sfFile.toPath());
        resp.setContentLength(data.length);
        resp.getOutputStream().write(data);
    }

    private void serveLandingPage(HttpServletResponse resp) throws IOException {
        SessionManager sm = GatewayHook.getSessionManager();
        if (sm == null) {
            resp.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            resp.getWriter().write("Gateway module starting\u2026");
            return;
        }
        // Discover branding image files from the filesystem directory
        List<String> brandingFiles = new ArrayList<>();
        File brandingDir = assetDir("branding");
        if (brandingDir != null && brandingDir.isDirectory()) {
            File[] files = brandingDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (!f.isFile()) continue;
                    String name = f.getName();
                    String lower = name.toLowerCase();
                    if (lower.endsWith(".png") || lower.endsWith(".jpg")
                            || lower.endsWith(".jpeg") || lower.endsWith(".svg")) {
                        brandingFiles.add(name);
                    }
                }
                Collections.sort(brandingFiles);
                if (brandingFiles.size() > 3) {
                    brandingFiles = new ArrayList<>(brandingFiles.subList(0, 3));
                }
            }
        }
        resp.setContentType("text/html; charset=UTF-8");
        resp.setHeader("Cache-Control", "no-store, no-cache");
        resp.getWriter().write(DoomLandingPage.getHTML(brandingFiles));
    }

    private void serveBrandingFile(String fileName, HttpServletResponse resp) throws IOException {
        if (!isPlainFileName(fileName)) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return;
        }
        File brandingDir = assetDir("branding");
        if (brandingDir == null) { resp.setStatus(503); return; }
        File f = new File(brandingDir, fileName);
        if (!f.exists() || !f.isFile()) {
            resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        String ct = fileName.endsWith(".svg")  ? "image/svg+xml" :
                    fileName.endsWith(".png")  ? "image/png" :
                    fileName.endsWith(".jpg")  ? "image/jpeg" :
                    fileName.endsWith(".jpeg") ? "image/jpeg" :
                    "application/octet-stream";
        resp.setContentType(ct);
        resp.setHeader("Cache-Control", "public, max-age=86400");
        byte[] data = Files.readAllBytes(f.toPath());
        resp.setContentLength(data.length);
        resp.getOutputStream().write(data);
    }

    // ── /console ──────────────────────────────────────────────────────────────

    /**
     * Handles console commands: warp, map, skill, god, noclip, give, help.
     * Returns JSON: {"ok":true/false, "message":"..."}.
     *
     * "skill N" stores the level in session.pendingSkill (used as default on next warp).
     * "noclip" dispatches IDSPISPOPD (DOOM1) or IDCLIP (DOOM2) automatically.
     */
    private void serveConsoleCommand(DoomSession session, HttpServletRequest req,
                                     HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json; charset=UTF-8");
        resp.setHeader("Cache-Control", "no-store");

        String raw = req.getParameter("command");
        if (raw == null || raw.trim().isEmpty()) {
            resp.getWriter().write("{\"ok\":false,\"message\":\"No command\"}");
            return;
        }

        String[] parts = raw.trim().split("\\s+");
        String cmd = parts[0].toLowerCase();
        String message;
        try {
            switch (cmd) {
                case "warp": {
                    boolean commercial = session.isCommercial();
                    int e, m, skillArg;
                    if (!commercial && parts.length >= 3) {
                        // DOOM1: warp E M [skill]
                        e = Integer.parseInt(parts[1]);
                        m = Integer.parseInt(parts[2]);
                        skillArg = (parts.length >= 4)
                            ? Integer.parseInt(parts[3]) - 1
                            : session.getPendingSkill();
                        message = "Warping to E" + e + "M" + m;
                    } else {
                        // DOOM2: warp M [skill]
                        e = 1;
                        m = Integer.parseInt(parts[1]);
                        skillArg = (parts.length >= 3)
                            ? Integer.parseInt(parts[2]) - 1
                            : session.getPendingSkill();
                        message = "Warping to MAP" + String.format("%02d", m);
                    }
                    session.warp(e, m, skillArg);
                    message += " skill " + (skillArg + 1);
                    break;
                }
                case "map": {
                    if (parts.length < 2) {
                        resp.getWriter().write("{\"ok\":false,\"message\":\"Usage: map ExMy | map MAPnn\"}");
                        return;
                    }
                    String mn = parts[1].toLowerCase();
                    int e, m;
                    if (mn.matches("e\\d+m\\d+")) {
                        int midx = mn.indexOf('m');
                        e = Integer.parseInt(mn.substring(1, midx));
                        m = Integer.parseInt(mn.substring(midx + 1));
                        message = "Warping to E" + e + "M" + m;
                    } else {
                        e = 1;
                        m = Integer.parseInt(mn.replaceAll("[^0-9]", ""));
                        message = "Warping to MAP" + String.format("%02d", m);
                    }
                    session.warp(e, m, session.getPendingSkill());
                    break;
                }
                case "skill": {
                    if (parts.length < 2) {
                        resp.getWriter().write("{\"ok\":false,\"message\":\"Usage: skill 1-5\"}");
                        return;
                    }
                    int s = Integer.parseInt(parts[1]) - 1;
                    s = Math.max(0, Math.min(4, s));
                    session.setPendingSkill(s);
                    String[] names = {"I'm Too Young to Die", "Hey, Not Too Rough",
                                      "Hurt Me Plenty", "Ultra-Violence", "Nightmare!"};
                    message = "Skill set to " + names[s] + " (takes effect on next warp)";
                    break;
                }
                case "god":
                    session.sendCheatKeys("IDDQD");
                    message = "IDDQD — Degreelessness Mode On";
                    break;
                case "noclip": {
                    String cheat = session.isCommercial() ? "IDCLIP" : "IDSPISPOPD";
                    session.sendCheatKeys(cheat);
                    message = cheat;
                    break;
                }
                case "give":
                    if (parts.length > 1 && parts[1].equalsIgnoreCase("all")) {
                        session.sendCheatKeys("IDKFA");
                        message = "IDKFA — All weapons, keys, full ammo & armor";
                    } else {
                        session.sendCheatKeys("IDFA");
                        message = "IDFA — All weapons, full ammo & armor (no keys)";
                    }
                    break;
                case "help":
                    message = "Commands: warp E M [skill] | map ExMy/MAPnn | skill 1-5 | god | noclip | give [all]";
                    break;
                default:
                    resp.getWriter().write("{\"ok\":false,\"message\":\"Unknown command: "
                        + escapeJson(cmd) + "\"}");
                    return;
            }
            resp.getWriter().write("{\"ok\":true,\"message\":\"" + escapeJson(message) + "\"}");
        } catch (NumberFormatException ex) {
            resp.getWriter().write("{\"ok\":false,\"message\":\"Invalid number in command\"}");
        } catch (Exception ex) {
            String err = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
            resp.getWriter().write("{\"ok\":false,\"message\":\"Error: " + escapeJson(err) + "\"}");
        }
    }

    // ── Match routes ──────────────────────────────────────────────────────────

    private void handleMatchGet(String path, HttpServletRequest req,
                                HttpServletResponse resp) throws IOException {
        if (path.contains("/create")) {
            serveMatchCreate(req, resp);
        } else if (path.contains("/join")) {
            serveMatchJoin(req, resp);
        } else if (path.contains("/frame/stream")) {
            MatchSession match = resolveRunningMatch(req, resp);
            if (match == null) return;
            String sid = req.getParameter("session");
            streamMatchFrames(match, sid, resp);

        } else if (path.contains("/frame")) {
            MatchSession match = resolveRunningMatch(req, resp);
            if (match == null) return;
            serveMatchFrame(match, req, resp);
        } else if (path.contains("/status")) {
            MatchSession match = resolveRunningMatch(req, resp);
            if (match == null) return;
            match.touchPlayer(req.getParameter("session"));   // the page polls this: a heartbeat
            serveMatchStatus(match, resp);
        } else if (path.contains("/sounds/")) {
            String soundName = path.substring(path.lastIndexOf("/sounds/") + "/sounds/".length());
            MatchSession match = resolveRunningMatch(req, resp);
            if (match == null) return;
            // Use WAD path directly — engines may not be running yet during lobby
            SessionManager smSnd = GatewayHook.getSessionManager();
            if (smSnd == null) { resp.setStatus(503); resp.getWriter().write("SessionManager unavailable"); return; }
            byte[] wavData = smSnd.getSoundData(match.getWadPath(), soundName);
            if (wavData == null) { resp.setStatus(HttpServletResponse.SC_NOT_FOUND); resp.getWriter().write("Sound not found: " + soundName); return; }
            resp.setContentType("audio/wav");
            resp.setHeader("Cache-Control", "public, max-age=3600");
            resp.setContentLength(wavData.length);
            resp.getOutputStream().write(wavData);
        } else if (path.contains("/sounds")) {
            MatchSession match = resolveRunningMatch(req, resp);
            if (match == null) return;
            // Use WAD path directly — engines may not be running yet during lobby
            SessionManager smList = GatewayHook.getSessionManager();
            if (smList == null) { resp.setStatus(503); resp.setContentType("application/json; charset=UTF-8"); resp.getWriter().write("[]"); return; }
            String[] soundNames = smList.getSoundNames(match.getWadPath());
            StringBuilder sndJson = new StringBuilder("[");
            for (int i = 0; i < soundNames.length; i++) {
                if (i > 0) sndJson.append(",");
                sndJson.append("\"").append(escapeJson(soundNames[i])).append("\"");
            }
            sndJson.append("]");
            resp.setContentType("application/json; charset=UTF-8");
            // Sound LIST must NOT be cached: it starts empty (lobby/cold-cache) and becomes
            // non-empty once the cache is built. A cached empty [] would permanently prevent
            // the browser retry loop from ever seeing the real list.
            resp.setHeader("Cache-Control", "no-store, no-cache");
            resp.getWriter().write(sndJson.toString());
        } else if (path.contains("/events/stream")) {
            MatchSession match = resolveRunningMatch(req, resp);
            if (match == null) return;
            String sid = req.getParameter("session");
            streamMatchSoundEvents(match, sid, resp);

        } else if (path.contains("/events")) {
            MatchSession match = resolveRunningMatch(req, resp);
            if (match == null) return;
            String sid = req.getParameter("session");
            DoomSession slotSession = sid != null ? match.getSlotSession(sid) : null;
            if (slotSession == null) {
                resp.setContentType("application/json; charset=UTF-8");
                resp.setHeader("Cache-Control", "no-store, no-cache, must-revalidate");
                resp.getWriter().write("[]");
                return;
            }
            serveSoundEvents(slotSession, resp);
        } else if (path.contains("/music/status")) {
            MatchSession match = resolveRunningMatch(req, resp);
            if (match == null) return;
            String sid = req.getParameter("session");
            DoomSession slotSession = sid != null ? match.getSlotSession(sid) : match.getEngineSession();
            if (slotSession == null) {
                resp.setContentType("application/json; charset=UTF-8");
                resp.setHeader("Cache-Control", "no-store, no-cache, must-revalidate");
                resp.getWriter().write("{\"playing\":false,\"changed\":false,\"looping\":false,\"volume\":127}");
                return;
            }
            serveMusicStatus(slotSession, resp);
        } else if (path.contains("/music/midi")) {
            MatchSession match = resolveRunningMatch(req, resp);
            if (match == null) return;
            String sid = req.getParameter("session");
            DoomSession slotSession = sid != null ? match.getSlotSession(sid) : match.getEngineSession();
            if (slotSession == null) {
                resp.setStatus(HttpServletResponse.SC_NO_CONTENT);
                return;
            }
            serveMidiData(slotSession, resp);
        } else {
            resp.setStatus(404);
            resp.getWriter().write("Unknown match route: " + path);
        }
    }

    private void handleMatchPost(String path, HttpServletRequest req,
                                 HttpServletResponse resp) throws IOException {
        resp.setHeader("Access-Control-Allow-Origin", "*");
        if (path.contains("/console")) {
            MatchSession match = resolveRunningMatch(req, resp);
            if (match == null) return;
            serveMatchConsoleCommand(match, req, resp);
        } else {
            // Default: keyboard input. Sound events are delivered via WebSocket (/doom-ws), with SSE fallback.
            // POST /match/input?match=ID&session=UUID  →  200 OK (no body)
            // Body: InputMessages lines (key list, m:mouse, opt:...).
            String body = new String(req.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            String matchId = req.getParameter("match");
            String sid     = req.getParameter("session");
            SessionManager sm = GatewayHook.getSessionManager();
            if (sm != null && matchId != null && sid != null) {
                MatchSession match = sm.getMatch(matchId);
                if (match != null) {
                    InputMessages.dispatchAll(body, InputMessages.forMatchPlayer(match, sid));
                }
            }
            resp.setStatus(HttpServletResponse.SC_OK);
        }
    }

    /**
     * GET /match/create?wad=NAME&players=N[&warp=E,M][&skill=S]
     * Creates a new match and redirects the creator to the join page as slot 0.
     */
    private void serveMatchCreate(HttpServletRequest req, HttpServletResponse resp)
            throws IOException {
        if (!checkPlayAuth(req, resp)) return;
        String sessionId = req.getParameter("session");
        if (sessionId == null || !sessionId.matches(
                "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
            resp.setContentType("text/html; charset=UTF-8");
            resp.setHeader("Cache-Control", "no-cache");
            String wadParam    = req.getParameter("wad")     != null ? req.getParameter("wad") : SessionManager.DEFAULT_WAD;
            String plrParam    = req.getParameter("players") != null ? req.getParameter("players") : "2";
            String warpParam   = req.getParameter("warp")    != null ? req.getParameter("warp") : "";
            String sklParam    = req.getParameter("skill")   != null ? req.getParameter("skill") : "";
            String fmtParam    = "jpeg".equalsIgnoreCase(req.getParameter("format")) ? "jpeg" : "";
            String pwadParam   = req.getParameter("pwad")    != null ? req.getParameter("pwad") : "";
            String midParam    = req.getParameter("matchId") != null ? req.getParameter("matchId") : "";
            GameplayOptions optParam = GameplayOptions.fromParams(req::getParameter);
            resp.getWriter().write(buildMatchInitPage(wadParam, plrParam, warpParam, sklParam, fmtParam, pwadParam, midParam, optParam));
            return;
        }

        SessionManager sm = GatewayHook.getSessionManager();
        if (sm == null) {
            resp.setStatus(503);
            resp.setContentType("text/html; charset=UTF-8");
            resp.getWriter().write(buildErrorPage("DOOM module not initialized",
                "The SessionManager failed to start. Check the gateway log."));
            return;
        }

        String wadName    = req.getParameter("wad");
        String pwadName   = req.getParameter("pwad");
        String frameFormat = "jpeg".equalsIgnoreCase(req.getParameter("format")) ? "jpeg" : "png";
        if (wadName == null || wadName.trim().isEmpty()) wadName = SessionManager.DEFAULT_WAD;
        int numPlayers   = parseIntParam(req, "players", 2, 2, 4);
        int skill        = parseIntParam(req, "skill",   2, 0, 4);
        GameplayOptions options = GameplayOptions.fromParams(req::getParameter);

        // Parse warp param: "E,M" or "M"
        int startEp = 0, startMap = 0;
        String warp = req.getParameter("warp");
        if (warp != null && !warp.isBlank()) {
            String[] wp = warp.split(",");
            try {
                if (wp.length >= 2) { startEp = Integer.parseInt(wp[0].trim()); startMap = Integer.parseInt(wp[1].trim()); }
                else                { startEp = 1; startMap = Integer.parseInt(wp[0].trim()); }
            } catch (NumberFormatException ignored) {}
        }

        // Resolve match ID: use caller-supplied custom ID or auto-generate a short one
        String requestedId = req.getParameter("matchId");
        String matchId;
        if (requestedId != null && !requestedId.isBlank()) {
            String normalized = requestedId.trim().toUpperCase();
            if (!normalized.matches("[A-Z0-9\\-]{3,12}")) {
                resp.setStatus(400);
                resp.setContentType("text/html; charset=UTF-8");
                resp.getWriter().write(buildErrorPage("Invalid Match ID",
                    "Match ID must be 3\u201312 letters, numbers, or hyphens. Got: '"
                    + escapeHtml(normalized) + "'"));
                return;
            }
            if (sm.getMatch(normalized) != null) {
                resp.setStatus(409);
                resp.setContentType("text/html; charset=UTF-8");
                resp.getWriter().write(buildErrorPage("Match ID already in use",
                    "A match named &ldquo;" + escapeHtml(normalized) + "&rdquo; already exists. "
                    + "Choose a different ID, or leave the field blank to auto-generate."));
                return;
            }
            matchId = normalized;
        } else {
            matchId = generateMatchId();
            if (sm.getMatch(matchId) != null) matchId = generateMatchId(); // retry once on collision
        }
        logger.info("Creating match: id={} wad={} players={}", matchId, wadName, numPlayers);

        MatchSession match;
        try {
            match = sm.createMatch(matchId, wadName, numPlayers, startEp, startMap, skill, frameFormat, pwadName, options);
        } catch (IllegalArgumentException e) {
            resp.setStatus(404);
            resp.setContentType("text/html; charset=UTF-8");
            resp.getWriter().write(buildErrorPage("WAD not found",
                "WAD '" + escapeHtml(wadName) + "' not found in " + sm.getWadDir()));
            return;
        } catch (Exception e) {
            resp.setStatus(500);
            resp.setContentType("text/html; charset=UTF-8");
            resp.getWriter().write(buildErrorPage("Match start failed",
                "Could not start match: " + escapeHtml(e.getMessage())));
            logger.error("Match create failed: matchId={}", matchId, e);
            return;
        }

        if (match == null) {
            resp.setStatus(503);
            resp.setContentType("text/html; charset=UTF-8");
            resp.getWriter().write(buildCapacityPage());
            return;
        }

        // Assign slot 0 to the creator and redirect to /match/join (carry auth token)
        String authToken = req.getParameter("auth");
        resp.sendRedirect("/system/doom/match/join?match=" + matchId
            + "&session=" + sessionId + "&wad=" + escUrl(wadName)
            + ("jpeg".equals(frameFormat) ? "&format=jpeg" : "")
            + (pwadName != null && !pwadName.isBlank() ? "&pwad=" + escUrl(pwadName) : "")
            + (authToken != null ? "&auth=" + escUrl(authToken) : ""));
    }

    /**
     * GET /match/join?match=ID&session=UUID&wad=NAME
     * Assigns a player slot to this browser and serves the match play page.
     */
    private void serveMatchJoin(HttpServletRequest req, HttpServletResponse resp)
            throws IOException {
        // No play-password check: joining an existing match needs only its match ID.
        // (Starting single-player and creating matches still require the password, if set.)
        String sessionId = req.getParameter("session");
        String matchId   = req.getParameter("match");
        String wadName   = req.getParameter("wad");
        if (wadName == null) wadName = SessionManager.DEFAULT_WAD;

        if (sessionId == null || !sessionId.matches(
                "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
            resp.setContentType("text/html; charset=UTF-8");
            resp.getWriter().write(buildMatchJoinInitPage(matchId, wadName, req.getParameter("auth")));
            return;
        }

        if (matchId == null || matchId.isBlank()) {
            resp.setStatus(400);
            resp.setContentType("text/html; charset=UTF-8");
            resp.getWriter().write(buildErrorPage("Missing match ID", "No ?match= parameter provided."));
            return;
        }

        SessionManager sm = GatewayHook.getSessionManager();
        if (sm == null) {
            resp.setStatus(503);
            resp.setContentType("text/html; charset=UTF-8");
            resp.getWriter().write(buildErrorPage("DOOM module not initialized",
                "The SessionManager failed to start. Check the gateway log."));
            return;
        }

        MatchSession match = sm.getMatch(matchId);
        if (match == null) {
            resp.setStatus(404);
            resp.setContentType("text/html; charset=UTF-8");
            resp.getWriter().write(buildErrorPage("Match not found",
                "Match '" + escapeHtml(matchId) + "' not found or has expired."));
            return;
        }

        int slot = match.assignSlot(sessionId);
        if (slot < 0) {
            resp.setStatus(409);
            resp.setContentType("text/html; charset=UTF-8");
            if (match.getLeftCount() > 0 || match.isLive()) {
                // Like vanilla and the source ports, a netgame's players are fixed once it starts.
                resp.getWriter().write(buildErrorPage("Match in progress",
                    "This match is already under way"
                    + (match.getLeftCount() > 0 ? " and " + match.getLeftCount() + " player(s) have left it" : "")
                    + ". A match can't be joined or rejoined once it has started: start a new match from the dashboard."));
            } else {
                resp.getWriter().write(buildErrorPage("Match full",
                    "All " + match.getNumPlayers() + " player slots are taken for this match."));
            }
            return;
        }
        if (match.hasLeft(slot)) {
            // Same browser tab coming back after leaving: its engine is gone.
            resp.setStatus(409);
            resp.setContentType("text/html; charset=UTF-8");
            resp.getWriter().write(buildErrorPage("You left this match",
                "You left this match, and a match can't be rejoined once it has started. "
                + "Start a new match from the dashboard."));
            return;
        }

        resp.setContentType("text/html; charset=UTF-8");
        resp.setHeader("Cache-Control", "no-cache");
        resp.getWriter().write(DoomPlayPage.getMatchHTML(
            matchId, sessionId, slot, match.getNumPlayers(), match.getWadName(),
            match.getGameplayOptions()));
    }

    /** GET /match/frame?match=ID&session=UUID — serves this player's POV frame. */
    private void serveMatchFrame(MatchSession match, HttpServletRequest req,
                                 HttpServletResponse resp) throws IOException {
        String sid  = req.getParameter("session");
        int slot    = sid != null ? match.getSlot(sid) : 0;
        if (slot < 0) slot = 0;
        String sentinel = match.getPlayerFrame(slot);
        if (sentinel == null || sentinel.isEmpty()) {
            resp.setStatus(HttpServletResponse.SC_NO_CONTENT);
            return;
        }
        if ("GAME_ENDED".equals(sentinel)) {
            resp.setStatus(HttpServletResponse.SC_GONE); // 410 — engine exited cleanly
            return;
        }
        byte[] frameBytes = match.getPlayerFrameBytes(slot);
        if (frameBytes == null || frameBytes.length == 0) {
            resp.setStatus(HttpServletResponse.SC_NO_CONTENT);
            return;
        }
        resp.setContentType(match.getPlayerFrameContentType(slot));
        resp.setHeader("Cache-Control", "no-store, no-cache, must-revalidate");
        resp.setHeader("Pragma", "no-cache");
        resp.setHeader("Expires", "0");
        resp.getOutputStream().write(frameBytes);
    }

    // ── MJPEG push streaming ──────────────────────────────────────────────────

    private static final String MJPEG_BOUNDARY  = "DOOM_FRAME";
    private static final byte[] MJPEG_DELIMITER = ("--" + MJPEG_BOUNDARY + "\r\n").getBytes(StandardCharsets.US_ASCII);

    /**
     * Streams MJPEG frames for a single-player session.
     * Blocks the Jetty thread for the session lifetime (acceptable for MAX_SESSIONS=4).
     * The client sets &lt;img src="/system/doom/frame/stream?session=ID"&gt; and the
     * browser renders each pushed JPEG natively, firing img.onload per frame.
     */
    private void streamFrames(DoomSession session, HttpServletResponse resp) throws IOException {
        resp.setContentType("multipart/x-mixed-replace; boundary=" + MJPEG_BOUNDARY);
        resp.setHeader("Cache-Control", "no-cache");
        resp.setHeader("Connection", "close");
        OutputStream out = resp.getOutputStream();
        int seq = session.getFrameSeq();
        double lagEma = 0.0;
        // Seed cooldown so quality is never adjusted until 5s after stream opens.
        // This also avoids the inflated lag on the very first frame (engine frames
        // produced during JVM/sound-cache warmup inflate newSeq - seq - 1 to ~35–70).
        long lastQualityChangeMs = System.currentTimeMillis();
        boolean firstFrame = true;
        while (session.isRunning()) {
            try {
                byte[] frame = session.waitForNextFrame(seq, 2000);
                if (frame == null) continue;
                int newSeq = session.getFrameSeq();
                if (firstFrame) {
                    // Skip the first-frame lag sample — seq gap reflects connection setup,
                    // not real congestion.
                    firstFrame = false;
                } else {
                    lagEma = 0.8 * lagEma + 0.2 * (newSeq - seq - 1);
                }
                seq = newSeq;
                long now = System.currentTimeMillis();
                if (now - lastQualityChangeMs >= 5000) {
                    float before = session.getCurrentJpegQuality();
                    adaptJpegQuality(session, lagEma);
                    if (session.getCurrentJpegQuality() != before) lastQualityChangeMs = now;
                }
                writeFramePart(out, frame, session.getFrameContentType());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (IOException e) {
                break; // client disconnected
            }
        }
    }

    /**
     * Adjusts JPEG quality based on a frame-lag EMA.
     * Quality steps: 0.85 → 0.75 → 0.65 → 0.55 (floor) on congestion, recovering in reverse.
     * Call-site enforces a 5s cool-down between adjustments to prevent oscillation.
     */
    private static void adaptJpegQuality(DoomSession session, double lagEma) {
        float current = session.getCurrentJpegQuality();
        if (lagEma > 2.5 && current > 0.55f) {
            session.setJpegQuality(Math.max(0.55f, current - 0.10f));
        } else if (lagEma < 0.5 && current < 0.85f) {
            session.setJpegQuality(Math.min(0.85f, current + 0.10f));
        }
    }

    /**
     * Streams MJPEG frames for a specific player slot in a match session.
     */
    private void streamMatchFrames(MatchSession match, String browserSid,
                                   HttpServletResponse resp) throws IOException {
        int slot = browserSid != null ? match.getSlot(browserSid) : 0;
        if (slot < 0) slot = 0;
        // Wait up to 60 s for this player's engine. (Not for every engine: after another
        // player leaves, the match is no longer fully running, but this player still is.)
        long deadline = System.currentTimeMillis() + 60_000;
        while (!match.isPlayerRunning(slot) && match.isAlive() && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
        if (!match.isPlayerRunning(slot)) return;
        resp.setContentType("multipart/x-mixed-replace; boundary=" + MJPEG_BOUNDARY);
        resp.setHeader("Cache-Control", "no-cache");
        resp.setHeader("Connection", "close");
        OutputStream out = resp.getOutputStream();
        int seq = match.getPlayerFrameSeq(slot);
        double lagEma = 0.0;
        // Seed cooldown so quality is never adjusted until 5s after stream opens.
        // This also avoids the inflated lag on the very first frame (engine frames
        // produced during match startup inflate newSeq - seq - 1 artificially).
        long lastQualityChangeMs = System.currentTimeMillis();
        boolean firstFrame = true;
        DoomSession slotSession = match.getSlotSessionByIndex(slot);
        while (match.isPlayerRunning(slot)) {
            try {
                byte[] frame = match.waitForNextFrame(slot, seq, 2000);
                if (frame == null) continue;
                int newSeq = match.getPlayerFrameSeq(slot);
                if (firstFrame) {
                    // Skip the first-frame lag sample — seq gap reflects match startup,
                    // not real congestion.
                    firstFrame = false;
                } else {
                    lagEma = 0.8 * lagEma + 0.2 * (newSeq - seq - 1);
                }
                seq = newSeq;
                if (slotSession != null) {
                    long now = System.currentTimeMillis();
                    if (now - lastQualityChangeMs >= 5000) {
                        float before = slotSession.getCurrentJpegQuality();
                        adaptJpegQuality(slotSession, lagEma);
                        if (slotSession.getCurrentJpegQuality() != before) lastQualityChangeMs = now;
                    }
                }
                writeFramePart(out, frame, match.getPlayerFrameContentType(slot));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (IOException e) {
                break; // client disconnected
            }
        }
    }

    private void writeFramePart(OutputStream out, byte[] frame, String contentType) throws IOException {
        out.write(MJPEG_DELIMITER);
        String headers = "Content-Type: " + contentType + "\r\nContent-Length: " + frame.length + "\r\n\r\n";
        out.write(headers.getBytes(StandardCharsets.US_ASCII));
        out.write(frame);
        out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    /** GET /match/status?match=ID&session=UUID — match state as JSON. */
    private void serveMatchStatus(MatchSession match, HttpServletResponse resp)
            throws IOException {
        String json = "{"
            + "\"running\":"  + match.isLive()  + ","
            + "\"players\":"  + match.getNumPlayers() + ","
            + "\"left\":"     + match.getLeftCount() + ","
            + "\"joined\":"   + match.getJoinedCount() + ","
            + "\"matchId\":\"" + escapeJson(match.getMatchId()) + "\","
            + "\"wad\":\""    + escapeJson(match.getWadName()) + "\""
            + "}";
        resp.setContentType("application/json; charset=UTF-8");
        resp.setHeader("Cache-Control", "no-store, no-cache, must-revalidate");
        resp.getWriter().write(json);
    }

    /** POST /match/console?match=ID&session=UUID — same commands as single-player console. */
    private void serveMatchConsoleCommand(MatchSession match, HttpServletRequest req,
                                          HttpServletResponse resp) throws IOException {
        // Reuse single-player console logic against the match's engine session
        serveConsoleCommand(match.getEngineSession(), req, resp);
    }

    // ── Match resolution helper ───────────────────────────────────────────────

    /**
     * Resolves the running MatchSession for this request.
     * Requires ?match=ID query param. Writes an error and returns null on failure.
     */
    private MatchSession resolveRunningMatch(HttpServletRequest req, HttpServletResponse resp)
            throws IOException {
        String matchId = req.getParameter("match");
        if (matchId == null || matchId.isBlank()) {
            resp.setStatus(400);
            resp.setContentType("text/plain");
            resp.getWriter().write("Missing ?match= parameter");
            return null;
        }
        SessionManager sm = GatewayHook.getSessionManager();
        if (sm == null) {
            resp.setStatus(503);
            resp.setContentType("text/plain");
            resp.getWriter().write("DOOM module not initialized");
            return null;
        }
        MatchSession match = sm.getMatch(matchId);
        if (match == null) {
            resp.setStatus(404);
            resp.setContentType("text/plain");
            resp.getWriter().write("Match not found or expired: " + matchId);
            return null;
        }
        return match;
    }

    // ── Match HTML page builders ──────────────────────────────────────────────

    private static String buildMatchInitPage(String wad, String players, String warp, String skill, String format, String pwad, String matchId,
                                             GameplayOptions options) {
        return "<!DOCTYPE html><html><head><meta charset='UTF-8'>"
            + "<title>DOOM — Creating match...</title></head><body style='background:#1a1a1a;color:#0f0;font-family:monospace;padding:20px'>"
            + "<p>Initializing match...</p>"
            + "<script>\n"
            + "function uuid4() { return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g,function(c){var r=Math.random()*16|0,v=c=='x'?r:(r&0x3|0x8);return v.toString(16);}); }\n"
            + "var sid = sessionStorage.getItem('doomSessionId') || uuid4();\n"
            + "sessionStorage.setItem('doomSessionId', sid);\n"
            + "var auth = sessionStorage.getItem('doomPlayToken') || '';\n"
            + "window.location.replace('/system/doom/match/create?session='+sid"
            + "+'&wad='+" + escapeJsString(wad)
            + "+'&players='+" + escapeJsString(players)
            + (warp.isEmpty()    ? "" : "+'&warp='+"    + escapeJsString(warp))
            + (skill.isEmpty()   ? "" : "+'&skill='+"   + escapeJsString(skill))
            + (format.isEmpty()  ? "" : "+'&format='+"  + escapeJsString(format))
            + (pwad.isEmpty()    ? "" : "+'&pwad='+"    + escapeJsString(pwad))
            + (matchId.isEmpty() ? "" : "+'&matchId='+" + escapeJsString(matchId))
            + "+'" + options.toQuery() + "'"
            + "+(auth?'&auth='+encodeURIComponent(auth):'')"
            + ");\n"
            + "</script></body></html>";
    }

    private static final String MATCH_ID_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static String generateMatchId() {
        java.util.Random rng = new java.util.Random();
        StringBuilder sb = new StringBuilder(6);
        for (int i = 0; i < 6; i++) sb.append(MATCH_ID_CHARS.charAt(rng.nextInt(MATCH_ID_CHARS.length())));
        return sb.toString();
    }

    private static String buildMatchJoinInitPage(String matchId, String wadName, String authToken) {
        // Auth token is server-injected so it survives into a fresh browser tab where sessionStorage is empty.
        String safeAuth = (authToken != null && !authToken.isEmpty()) ? escUrl(authToken) : "";
        return "<!DOCTYPE html><html><head><meta charset='UTF-8'>"
            + "<title>DOOM — Joining match...</title></head><body style='background:#1a1a1a;color:#0f0;font-family:monospace;padding:20px'>"
            + "<p>Joining match...</p>"
            + "<script>\n"
            + "function uuid4() { return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g,function(c){var r=Math.random()*16|0,v=c=='x'?r:(r&0x3|0x8);return v.toString(16);}); }\n"
            + "var sid = sessionStorage.getItem('doomSessionId') || uuid4();\n"
            + "sessionStorage.setItem('doomSessionId', sid);\n"
            + "var _serverAuth = " + escapeJsString(safeAuth) + ";\n"
            + "var auth = _serverAuth || sessionStorage.getItem('doomPlayToken') || '';\n"
            + "if (auth) sessionStorage.setItem('doomPlayToken', auth);\n"
            + "window.location.replace('/system/doom/match/join?session='+sid"
            + "+'&match='+" + escapeJsString(matchId != null ? matchId : "")
            + "+'&wad='+"   + escapeJsString(wadName)
            + "+(auth?'&auth='+encodeURIComponent(auth):'')"
            + ");\n"
            + "</script></body></html>";
    }

    // ── Session resolution helpers ────────────────────────────────────────────

    /**
     * Resolves the running DoomSession for this request.
     * Writes an error response and returns null if the session cannot be resolved.
     * Suitable for all endpoints that require an active game session.
     */
    private DoomSession resolveRunningSession(HttpServletRequest req, HttpServletResponse resp)
            throws IOException {
        String sid = req.getParameter("session");
        if (sid == null || sid.isBlank()) {
            resp.setStatus(400);
            resp.setContentType("text/plain");
            resp.getWriter().write("Missing ?session= parameter");
            return null;
        }

        SessionManager sm = GatewayHook.getSessionManager();
        if (sm == null) {
            resp.setStatus(503);
            resp.setContentType("text/plain");
            resp.getWriter().write("DOOM module not initialized");
            return null;
        }

        DoomSession session = sm.get(sid);
        if (session == null) {
            resp.setStatus(404);
            resp.setContentType("text/plain");
            resp.getWriter().write("Session not found or expired: " + sid
                + " — reload the page to start a new session");
            return null;
        }
        return session;
    }

    /**
     * Like resolveRunningSession() but does NOT write an error response.
     * Used for the input POST where we silently no-op if the session is gone.
     */
    private DoomSession resolveSession(HttpServletRequest req) {
        String sid = req.getParameter("session");
        if (sid == null) return null;
        SessionManager sm = GatewayHook.getSessionManager();
        if (sm == null) return null;
        return sm.get(sid);
    }

    // ── HTML page builders ────────────────────────────────────────────────────

    /**
     * Serves a lightweight redirect page that generates a session UUID in the browser
     * and redirects back to /play with it. This handles the first-visit case where
     * the browser doesn't have a session ID yet.
     */
    private static String buildSessionInitPage(String wadName, String frameFormat, String pwadName, int skill,
                                               GameplayOptions options) {
        String fmtParam  = "jpeg".equals(frameFormat) ? "+'&format=jpeg'" : "";
        String pwadParam = (pwadName != null && !pwadName.isBlank())
            ? "+'&pwad='+encodeURIComponent(" + escapeJsString(pwadName) + ")" : "";
        return "<!DOCTYPE html><html><head><meta charset='UTF-8'>"
            + "<title>DOOM — Starting session...</title></head><body style='background:#1a1a1a;color:#0f0;font-family:monospace;padding:20px'>"
            + "<p>Initializing DOOM session...</p>"
            + "<script>\n"
            + "function uuid4() {\n"
            + "  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, function(c) {\n"
            + "    var r = Math.random()*16|0, v = c=='x' ? r : (r&0x3|0x8);\n"
            + "    return v.toString(16);\n"
            + "  });\n"
            + "}\n"
            + "var sid = sessionStorage.getItem('doomSessionId') || uuid4();\n"
            + "sessionStorage.setItem('doomSessionId', sid);\n"
            + "var wad = " + escapeJsString(wadName) + ";\n"
            + "var auth = sessionStorage.getItem('doomPlayToken') || '';\n"
            + "window.location.replace('/system/doom/play?session=' + sid + '&wad=' + encodeURIComponent(wad) + '&skill=" + skill + "'" + fmtParam + pwadParam
            + " + '" + options.toQuery() + "'"
            + " + (auth ? '&auth=' + encodeURIComponent(auth) : ''));\n"
            + "</script></body></html>";
    }

    private static String buildCapacityPage() {
        return "<!DOCTYPE html><html><head><meta charset='UTF-8'>"
            + "<title>DOOM — Server at capacity</title></head>"
            + "<body style='background:#1a1a1a;color:#f00;font-family:monospace;padding:20px'>"
            + "<h2>DOOM: Server at capacity</h2>"
            + "<p>Maximum " + SessionManager.getMaxSessions() + " concurrent sessions active.</p>"
            + "<p>Try again later or close another tab.</p>"
            + "<p><a href='javascript:location.reload()' style='color:#f00'>Retry</a></p>"
            + "</body></html>";
    }

    private static String buildErrorPage(String title, String detail) {
        return "<!DOCTYPE html><html><head><meta charset='UTF-8'>"
            + "<title>DOOM — " + escapeHtml(title) + "</title></head>"
            + "<body style='background:#1a1a1a;color:#f88;font-family:monospace;padding:20px'>"
            + "<h2>" + escapeHtml(title) + "</h2>"
            + "<p>" + escapeHtml(detail) + "</p>"
            + "</body></html>";
    }

    // ── Misc helpers ──────────────────────────────────────────────────────────

    /** Parses an integer query param with a default and inclusive min/max clamp. */
    private static int parseIntParam(HttpServletRequest req, String name,
                                     int defaultVal, int min, int max) {
        String v = req.getParameter(name);
        if (v == null) return defaultVal;
        try { return Math.max(min, Math.min(max, Integer.parseInt(v.trim()))); }
        catch (NumberFormatException e) { return defaultVal; }
    }

    // ── Escaping helpers ──────────────────────────────────────────────────────

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "");
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    /** Wraps a Java string as a JS string literal (single-quoted). */
    private static String escapeJsString(String s) {
        if (s == null) return "''";
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }
}
