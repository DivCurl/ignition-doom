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
 */
public class DoomInputServlet extends HttpServlet {

    private static final Logger logger = LoggerFactory.getLogger("DOOM.Servlet");

    /** Soundfont instrument JS files on the container filesystem (populated by setup script). */
    private static final String SOUNDFONTS_DIR =
        "/usr/local/bin/ignition/user-lib/doom/soundfonts/";

    /** Branding image assets on the container filesystem (populated by setup script). */
    private static final String BRANDING_DIR =
        "/usr/local/bin/ignition/user-lib/doom/branding/";

    // ── GET dispatcher ────────────────────────────────────────────────────────

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String path = req.getPathInfo();
        if (path == null) path = "/";

        if (path.contains("static/")) {
            String fileName = path.substring(path.indexOf("static/") + "static/".length());
            serveStaticResource(fileName, resp);

        } else if (path.contains("music/sf/")) {
            String sfName = path.substring(path.indexOf("music/sf/") + "music/sf/".length());
            serveSoundfontFile(sfName, resp);

        } else if (path.contains("admin/kill")) {
            serveAdminKill(req, resp);

        } else if (path.contains("admin")) {
            serveAdminPage(req, resp);

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
        resp.setHeader("Access-Control-Allow-Origin", "*");

        String path = req.getPathInfo();
        if (path == null) path = "/";

        if (path.contains("/match")) {
            handleMatchPost(path, req, resp);
        } else if (path.contains("admin/regen")) {
            serveAdminRegen(req, resp);
        } else if (path.contains("auth")) {
            handlePlayAuth(req, resp);
        } else if (path.contains("console")) {
            DoomSession session = resolveRunningSession(req, resp);
            if (session == null) return;
            serveConsoleCommand(session, req, resp);
        } else if (path.contains("input/stream")) {
            handleStreamingInput(req, resp);
        } else {
            // Default: keyboard input. Sound events are delivered via SSE (/events/stream).
            // POST /system/doom/input?session=UUID  →  200 OK (no body)
            String body = new String(req.getInputStream().readAllBytes()).trim();
            Set<Integer> currentKeys = new HashSet<>();
            if (!body.isEmpty()) {
                for (String s : body.split(",")) {
                    try { currentKeys.add(Integer.parseInt(s.trim())); }
                    catch (NumberFormatException ignored) {}
                }
            }
            DoomSession session = resolveSession(req);
            if (session != null) {
                session.updatePressedKeys(currentKeys);
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
     * Validates the play password and returns a short-lived play token as JSON.
     * 200 {"token":"<hex>"} on success, 401 on bad password, 503 if not ready.
     */
    private void handlePlayAuth(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setHeader("Cache-Control", "no-store");
        SessionManager sm = GatewayHook.getSessionManager();
        if (sm == null) { resp.setStatus(503); return; }

        String body = new String(req.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        if (sm.validatePlayPassword(body)) {
            String token = sm.issuePlayToken();
            resp.setContentType("application/json");
            resp.getWriter().write("{\"token\":\"" + token + "\"}");
        } else {
            resp.setStatus(401);
            resp.setContentType("text/plain");
            resp.getWriter().write("Incorrect password");
        }
    }

    /**
     * Validates the ?auth= play token on a request.
     * Writes 403 and returns false if the token is missing or invalid.
     */
    private boolean checkPlayAuth(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        SessionManager sm = GatewayHook.getSessionManager();
        if (sm == null) { resp.setStatus(503); return false; }
        String token = req.getParameter("auth");
        if (!sm.validatePlayToken(token)) {
            resp.setStatus(403);
            resp.setContentType("application/json");
            resp.getWriter().write("{\"error\":\"auth_required\"}");
            return false;
        }
        return true;
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

        // Validate session ID format (UUID v4: 8-4-4-4-12)
        if (sessionId == null || !sessionId.matches(
                "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
            // Send back a page that generates a UUID and redirects to itself with it
            resp.setContentType("text/html; charset=UTF-8");
            resp.setHeader("Cache-Control", "no-cache");
            resp.getWriter().write(buildSessionInitPage(wadName, frameFormat, pwadName, skill));
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
            session = sm.getOrCreate(sessionId, wadName, 0, 0, skill, frameFormat, pwadName);
        } catch (IllegalArgumentException e) {
            resp.setStatus(404);
            resp.setContentType("text/html; charset=UTF-8");
            resp.getWriter().write(buildErrorPage("WAD not found",
                "The WAD file '" + escapeHtml(wadName) + "' was not found on the server. "
                + "Available WADs must be in " + SessionManager.WAD_DIR));
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

    // ── /admin  ───────────────────────────────────────────────────────────────

    private void serveAdminPage(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        SessionManager sm = GatewayHook.getSessionManager();
        if (sm == null) {
            resp.setStatus(503);
            resp.getWriter().write("DOOM module not initialized");
            return;
        }
        String token = req.getParameter("token");
        if (!sm.validateAdminToken(token)) {
            resp.setStatus(403);
            resp.setContentType("text/html; charset=UTF-8");
            resp.getWriter().write(buildAdminForbiddenPage());
            return;
        }
        String killedId = req.getParameter("killed");
        String regenDone = req.getParameter("regen");
        resp.setContentType("text/html; charset=UTF-8");
        resp.setHeader("Cache-Control", "no-cache, no-store");
        resp.getWriter().write(buildAdminPage(sm, token, killedId, regenDone != null));
    }

    private void serveAdminRegen(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        SessionManager sm = GatewayHook.getSessionManager();
        if (sm == null) { resp.setStatus(503); return; }
        String token = req.getParameter("token");
        if (!sm.validateAdminToken(token)) {
            resp.setStatus(403);
            resp.setContentType("text/html; charset=UTF-8");
            resp.getWriter().write(buildAdminForbiddenPage());
            return;
        }
        sm.regeneratePlayPassword();
        resp.sendRedirect("/system/doom/admin?token=" + token + "&regen=1");
    }

    private void serveAdminKill(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        SessionManager sm = GatewayHook.getSessionManager();
        if (sm == null) {
            resp.setStatus(503);
            resp.getWriter().write("DOOM module not initialized");
            return;
        }
        String token = req.getParameter("token");
        if (!sm.validateAdminToken(token)) {
            resp.setStatus(403);
            resp.setContentType("text/html; charset=UTF-8");
            resp.getWriter().write(buildAdminForbiddenPage());
            return;
        }
        String sessionId = req.getParameter("session");
        if (sessionId == null || sessionId.isBlank()) {
            resp.setStatus(400);
            resp.getWriter().write("Missing ?session= parameter");
            return;
        }
        sm.killSession(sessionId);
        logger.info("Admin terminated session: {}", sessionId);
        // Redirect back to admin page with a notice
        resp.sendRedirect("/system/doom/admin?token=" + token + "&killed=" + escUrl(sessionId));
    }

    private String buildAdminPage(SessionManager sm, String token, String killedId, boolean regenDone) {
        long uptimeSec = sm.getUptimeSec();
        long uptimeMin = uptimeSec / 60;
        long uptimeHr  = uptimeMin / 60;
        java.util.List<DoomSession> active = sm.getActiveSessions();

        StringBuilder h = new StringBuilder();
        h.append("<!DOCTYPE html><html><head><title>Ignition DOOM \u2013 Session Admin</title><style>\n");
        h.append("*{box-sizing:border-box}\n");
        h.append("body{background:#0d0d0d;color:#c8c8c8;font-family:'Courier New',monospace;padding:24px;margin:0}\n");
        // Header: flex column — row1: logo+title, row2: attribution
        h.append(".header{display:flex;flex-direction:column;border-bottom:1px solid #333;padding-bottom:14px;margin-bottom:6px}\n");
        h.append(".header-row{display:flex;align-items:center;gap:14px}\n");
        h.append(".header-logo{height:120px;width:auto;border-radius:3px;opacity:0.92}\n");
        h.append(".header-text{display:flex;flex-direction:column;justify-content:center}\n");
        h.append(".header-title{color:#ff4444;font-size:28px;font-weight:bold;text-transform:uppercase;letter-spacing:6px;line-height:1;margin:0 0 6px 0}\n");
        h.append(".header-desc{color:#999;font-size:12px;letter-spacing:2px;text-transform:uppercase}\n");
        h.append(".stats{color:#999;font-size:12px;margin-bottom:20px;margin-top:8px}\n");
        h.append(".notice{color:#ffff44;background:#332200;border:1px solid #664400;padding:7px 14px;margin-bottom:16px;font-size:13px;display:inline-block}\n");
        h.append("table{border-collapse:collapse;width:100%}\n");
        h.append("th{color:#ff4444;text-align:left;padding:8px 14px;border-bottom:2px solid #ff4444;text-transform:uppercase;letter-spacing:2px;font-size:11px}\n");
        h.append("td{padding:8px 14px;border-bottom:1px solid #282828;font-size:13px;vertical-align:middle}\n");
        h.append("tr:hover td{background:#141414}\n");
        h.append(".wad{color:#ffcc00}\n");
        h.append(".sid{color:#99aacc;font-size:11px;word-break:break-all}\n");
        h.append(".idle{color:#aaa}\n");
        h.append(".kill-link{background:#6b0000;color:#ffaaaa;border:1px solid #aa0000;padding:4px 14px;text-decoration:none;font-size:11px;letter-spacing:1px;text-transform:uppercase}\n");
        h.append(".kill-link:hover{background:#cc0000;color:#fff}\n");
        h.append(".empty{color:#888;padding:24px 14px}\n");
        h.append(".refresh{color:#777;font-size:12px;text-decoration:none;margin-left:16px}\n");
        h.append(".refresh:hover{color:#aaa}\n");
        h.append(".regen-btn{background:#1a1a00;color:#ffcc00;border:1px solid #665500;padding:4px 12px;font-family:'Courier New',monospace;font-size:11px;letter-spacing:1px;text-transform:uppercase;cursor:pointer;margin-left:12px}\n");
        h.append(".regen-btn:hover{background:#332200;color:#ffe066}\n");
        h.append("</style></head><body>\n");

        // Header block
        h.append("<div class='header'>\n");
        h.append("  <div class='header-row'>\n");
        h.append("    <img class='header-logo' src='/system/doom/static/doom-logo.png' alt='DOOM'>\n");
        h.append("    <div class='header-text'>\n");
        h.append("      <div class='header-title'>Ignition DOOM</div>\n");
        h.append("      <div class='header-desc'>Session Manager</div>\n");
        h.append("    </div>\n");
        h.append("  </div>\n");
        h.append("</div>\n");

        // Credentials info
        if (regenDone) {
            h.append("<div class='notice'>Play password regenerated. All existing browser tokens have been revoked.</div><br>\n");
        }
        h.append("<div class='stats' style='margin-bottom:8px;display:flex;align-items:center;gap:0'>")
         .append("<span style='color:#888'>Play password:</span>&nbsp;")
         .append("<code style='color:#ffcc00;letter-spacing:2px'>").append(escHtml(sm.getPlayPassword())).append("</code>")
         .append("<form method='POST' action='/system/doom/admin/regen?token=").append(escHtml(token)).append("' style='display:inline;margin:0'>")
         .append("<button type='submit' class='regen-btn'>Regenerate</button>")
         .append("</form>")
         .append("</div>\n");

        // Stats bar
        h.append("<div class='stats'>Active: ").append(active.size()).append(" / ").append(SessionManager.MAX_SESSIONS);
        h.append("&nbsp;&nbsp;|&nbsp;&nbsp;Uptime: ");
        if (uptimeHr > 0) h.append(uptimeHr).append("h ");
        h.append(uptimeMin % 60).append("m ").append(uptimeSec % 60).append("s");
        h.append("&nbsp;&nbsp;");
        h.append("<a class='refresh' href='/system/doom/admin?token=").append(escHtml(token)).append("'>[Refresh]</a>");
        h.append("</div>\n");

        if (killedId != null && !killedId.isBlank()) {
            h.append("<div class='notice'>Session terminated: ").append(escHtml(killedId)).append("</div><br>\n");
        }

        h.append("<table>\n");
        h.append("<tr><th>Session ID</th><th>WAD</th><th>Idle</th><th>Uptime</th><th>Action</th></tr>\n");
        if (active.isEmpty()) {
            h.append("<tr><td class='empty' colspan='5'>No active sessions</td></tr>\n");
        } else {
            for (DoomSession s : active) {
                long idleSec = s.getIdleMs() / 1000;
                long upSec   = s.getUptimeMs() / 1000;
                h.append("<tr>");
                h.append("<td class='sid'>").append(escHtml(s.getSessionId())).append("</td>");
                h.append("<td class='wad'>").append(escHtml(s.getWadName())).append("</td>");
                h.append("<td class='idle'>").append(idleSec / 60).append("m ").append(idleSec % 60).append("s</td>");
                h.append("<td class='idle'>").append(upSec / 60).append("m ").append(upSec % 60).append("s</td>");
                h.append("<td><a class='kill-link' href='/system/doom/admin/kill?token=")
                    .append(escHtml(token)).append("&session=").append(escHtml(s.getSessionId()))
                    .append("'>Terminate</a></td>");
                h.append("</tr>\n");
            }
        }
        h.append("</table>\n");
        h.append("</body></html>");
        return h.toString();
    }

    private String buildAdminForbiddenPage() {
        return "<!DOCTYPE html><html><head><title>DOOM Admin - Forbidden</title></head>"
            + "<body style='background:#0d0d0d;color:#ff4444;font-family:monospace;padding:24px'>"
            + "<h2>403 FORBIDDEN</h2><p>Valid admin token required.</p>"
            + "<p style='color:#555;font-size:12px'>Find the token in the gateway log:<br>"
            + "<code>grep 'Admin token' &lt;gateway-log&gt;</code></p>"
            + "</body></html>";
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
    }

    /**
     * GET /system/doom/match/events/stream?match=ID&session=UUID
     * Same as streamSoundEvents() but for a deathmatch player slot.
     * Waits up to 60s for all engines to start before opening the stream.
     */
    private void streamMatchSoundEvents(MatchSession match, String browserSid,
                                        HttpServletResponse resp) throws IOException {
        long deadline = System.currentTimeMillis() + 60_000;
        while (!match.isFullyRunning() && match.isAlive() && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
        if (!match.isFullyRunning()) return;

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

        while (match.isFullyRunning()) {
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
            resp.setContentType("application/javascript; charset=UTF-8");
            resp.setHeader("Cache-Control", "public, max-age=86400");
            byte[] data = is.readAllBytes();
            resp.setContentLength(data.length);
            resp.getOutputStream().write(data);
        }
    }

    private void serveSoundfontFile(String sfName, HttpServletResponse resp) throws IOException {
        if (sfName.isEmpty() || sfName.contains("..") || sfName.contains("/")) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return;
        }
        File sfFile = new File(SOUNDFONTS_DIR, sfName);
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
        File brandingDir = new File(BRANDING_DIR);
        if (brandingDir.isDirectory()) {
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
        resp.getWriter().write(DoomLandingPage.getHTML("0.9.0", brandingFiles));
    }

    private void serveBrandingFile(String fileName, HttpServletResponse resp) throws IOException {
        if (fileName.isEmpty() || fileName.contains("..") || fileName.contains("/")) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return;
        }
        File f = new File(BRANDING_DIR, fileName);
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
        } else if (path.contains("input/stream")) {
            handleMatchStreamingInput(req, resp);
        } else {
            // Default: keyboard input. Sound events are delivered via SSE (/match/events/stream).
            // POST /match/input?match=ID&session=UUID  →  200 OK (no body)
            String body = new String(req.getInputStream().readAllBytes()).trim();
            Set<Integer> currentKeys = new HashSet<>();
            if (!body.isEmpty()) {
                for (String s : body.split(",")) {
                    try { currentKeys.add(Integer.parseInt(s.trim())); }
                    catch (NumberFormatException ignored) {}
                }
            }
            String matchId = req.getParameter("match");
            String sid     = req.getParameter("session");
            SessionManager sm = GatewayHook.getSessionManager();
            if (sm != null && matchId != null && sid != null) {
                MatchSession match = sm.getMatch(matchId);
                if (match != null) {
                    match.updatePressedKeys(sid, currentKeys);
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
            resp.getWriter().write(buildMatchInitPage(wadParam, plrParam, warpParam, sklParam, fmtParam, pwadParam, midParam));
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
            match = sm.createMatch(matchId, wadName, numPlayers, startEp, startMap, skill, frameFormat, pwadName);
        } catch (IllegalArgumentException e) {
            resp.setStatus(404);
            resp.setContentType("text/html; charset=UTF-8");
            resp.getWriter().write(buildErrorPage("WAD not found",
                "WAD '" + escapeHtml(wadName) + "' not found in " + SessionManager.WAD_DIR));
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
        if (!checkPlayAuth(req, resp)) return;
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
            resp.getWriter().write(buildErrorPage("Match full",
                "All " + match.getNumPlayers() + " player slots are taken for this match."));
            return;
        }

        resp.setContentType("text/html; charset=UTF-8");
        resp.setHeader("Cache-Control", "no-cache");
        resp.getWriter().write(DoomPlayPage.getMatchHTML(
            matchId, sessionId, slot, match.getNumPlayers(), match.getWadName()));
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
        while (session.isRunning()) {
            try {
                byte[] frame = session.waitForNextFrame(seq, 2000);
                if (frame == null) continue;
                seq = session.getFrameSeq();
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
     * Streams MJPEG frames for a specific player slot in a match session.
     */
    private void streamMatchFrames(MatchSession match, String browserSid,
                                   HttpServletResponse resp) throws IOException {
        int slot = browserSid != null ? match.getSlot(browserSid) : 0;
        if (slot < 0) slot = 0;
        // Wait up to 60 s for all engines to start before opening the stream
        long deadline = System.currentTimeMillis() + 60_000;
        while (!match.isFullyRunning() && match.isAlive() && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
        if (!match.isFullyRunning()) return;
        resp.setContentType("multipart/x-mixed-replace; boundary=" + MJPEG_BOUNDARY);
        resp.setHeader("Cache-Control", "no-cache");
        resp.setHeader("Connection", "close");
        OutputStream out = resp.getOutputStream();
        int seq = match.getPlayerFrameSeq(slot);
        while (match.isFullyRunning()) {
            try {
                byte[] frame = match.waitForNextFrame(slot, seq, 2000);
                if (frame == null) continue;
                seq = match.getPlayerFrameSeq(slot);
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
            + "\"running\":"  + match.isFullyRunning()  + ","
            + "\"players\":"  + match.getNumPlayers() + ","
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

    private static String buildMatchInitPage(String wad, String players, String warp, String skill, String format, String pwad, String matchId) {
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

    // ── Streaming input handlers (Option C) ──────────────────────────────────

    /**
     * POST /system/doom/input/stream?session=UUID
     * Accepts a persistent chunked-encoded request body. Each newline-delimited
     * line is a comma-separated list of pressed keyCodes; the server updates the
     * session's key state on every line. Runs until the client disconnects or the
     * session ends. Requires browser support for fetch() with duplex:'half'.
     */
    private void handleStreamingInput(HttpServletRequest req, HttpServletResponse resp)
            throws IOException {
        DoomSession session = resolveSession(req);
        if (session == null) { resp.setStatus(404); return; }
        resp.setStatus(200);
        resp.setContentType("text/plain");
        resp.flushBuffer();
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(req.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!session.isRunning()) break;
                Set<Integer> keys = new HashSet<>();
                if (!line.trim().isEmpty()) {
                    for (String s : line.split(",")) {
                        try { keys.add(Integer.parseInt(s.trim())); }
                        catch (NumberFormatException ignored) {}
                    }
                }
                session.updatePressedKeys(keys);
            }
        } catch (IOException ignored) { /* client disconnected */ }
    }

    /**
     * POST /system/doom/match/input/stream?match=ID&session=UUID
     * Same as handleStreamingInput but for a deathmatch slot session.
     */
    private void handleMatchStreamingInput(HttpServletRequest req, HttpServletResponse resp)
            throws IOException {
        String matchId = req.getParameter("match");
        String sid     = req.getParameter("session");
        SessionManager sm = GatewayHook.getSessionManager();
        if (sm == null || matchId == null || sid == null) { resp.setStatus(400); return; }
        MatchSession match = sm.getMatch(matchId);
        if (match == null) { resp.setStatus(404); return; }
        resp.setStatus(200);
        resp.setContentType("text/plain");
        resp.flushBuffer();
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(req.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!match.isRunning()) break;
                Set<Integer> keys = new HashSet<>();
                if (!line.trim().isEmpty()) {
                    for (String s : line.split(",")) {
                        try { keys.add(Integer.parseInt(s.trim())); }
                        catch (NumberFormatException ignored) {}
                    }
                }
                match.updatePressedKeys(sid, keys);
            }
        } catch (IOException ignored) { /* client disconnected */ }
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
    private static String buildSessionInitPage(String wadName, String frameFormat, String pwadName, int skill) {
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
            + "window.location.replace('/system/doom/play?session=' + sid + '&wad=' + encodeURIComponent(wad) + '&skill=" + skill + "'" + fmtParam + pwadParam + " + (auth ? '&auth=' + encodeURIComponent(auth) : ''));\n"
            + "</script></body></html>";
    }

    private static String buildCapacityPage() {
        return "<!DOCTYPE html><html><head><meta charset='UTF-8'>"
            + "<title>DOOM — Server at capacity</title></head>"
            + "<body style='background:#1a1a1a;color:#f00;font-family:monospace;padding:20px'>"
            + "<h2>DOOM: Server at capacity</h2>"
            + "<p>Maximum " + SessionManager.MAX_SESSIONS + " concurrent sessions active.</p>"
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
