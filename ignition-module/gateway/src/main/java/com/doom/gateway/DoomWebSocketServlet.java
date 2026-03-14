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
import org.eclipse.jetty.ee10.websocket.server.JettyWebSocketServlet;
import org.eclipse.jetty.ee10.websocket.server.JettyWebSocketServletFactory;
import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketError;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketOpen;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.servlet.ServletException;
import java.util.HashSet;
import java.util.Set;

/**
 * WebSocket endpoint for DOOM input + audio events.
 *
 * Replaces the SSE /events/stream + POST /input + duplex streaming input with a
 * single bidirectional WebSocket channel per browser session.
 *
 * Single-player:  ws://.../system/doom-ws?session=UUID
 * Deathmatch:     ws://.../system/doom-ws?match=MATCH_ID&session=SESSION_ID
 *
 * Protocol:
 *   Client→Server  "87,65"          — comma-separated pressed keycodes (empty = no keys)
 *   Client→Server  "ping:1234"      — RTT probe; server echoes "pong:1234"
 *
 *   Server→Client  "pong:1234"      — RTT echo
 *   Server→Client  "sound:[{...}]"  — sound events JSON array
 *   Server→Client  "music:{...}"    — music state change JSON
 */
public class DoomWebSocketServlet extends JettyWebSocketServlet {

    private static final Logger logger = LoggerFactory.getLogger("DOOM.WebSocket");

    @Override
    public void init() throws ServletException {
        logger.info("DOOM WS: init() context={}", getServletContext().getContextPath());
        super.init();
        logger.info("DOOM WS: init() complete — servlet ready");
    }

    @Override
    protected void configure(JettyWebSocketServletFactory factory) {
        factory.setIdleTimeout(java.time.Duration.ofSeconds(120));
        factory.setCreator((req, resp) -> {
            // req is JettyServerUpgradeRequest — query params available via getParameter()
            String sessionId = req.getHttpServletRequest().getParameter("session");
            String matchId   = req.getHttpServletRequest().getParameter("match");
            return new DoomWebSocketHandler(sessionId, matchId);
        });
    }

    // ── WebSocket handler ─────────────────────────────────────────────────────

    @WebSocket
    public static class DoomWebSocketHandler {

        private static final Logger log = LoggerFactory.getLogger("DOOM.WebSocket.Handler");

        private final String sessionId;
        private final String matchId;

        private volatile Session wsSession;
        private volatile boolean closed = false;

        /** Push thread: polls for sound/music events and sends them over WS. */
        private Thread pushThread;

        DoomWebSocketHandler(String sessionId, String matchId) {
            this.sessionId = sessionId;
            this.matchId   = matchId;
        }

        @OnWebSocketOpen
        public void onOpen(Session session) {
            this.wsSession = session;
            log.debug("WS open: session={} match={}", sessionId, matchId);

            pushThread = new Thread(this::pushLoop, "DOOM-WSPush-" + sessionId);
            pushThread.setDaemon(true);
            pushThread.start();
        }

        @OnWebSocketMessage
        public void onMessage(String message) {
            if (message == null) return;

            if (message.startsWith("ping:")) {
                sendText("pong:" + message.substring(5));
                return;
            }

            // Key state: comma-separated integers; empty string = all keys released.
            // IMPORTANT: always call updatePressedKeys even for empty message so keys
            // are cleared when the player releases all keys.
            Set<Integer> keys = new HashSet<>();
            for (String part : message.split(",")) {
                String p = part.trim();
                if (!p.isEmpty()) {
                    try { keys.add(Integer.parseInt(p)); }
                    catch (NumberFormatException ignored) {}
                }
            }

            DoomSession session = resolveSession();
            if (session != null) {
                session.updatePressedKeys(keys);
            }
        }

        @OnWebSocketClose
        public void onClose(int statusCode, String reason) {
            closed = true;
            // Clear all keys on disconnect to prevent sticky keys
            DoomSession session = resolveSession();
            if (session != null) {
                session.updatePressedKeys(new HashSet<>());
            }
            if (pushThread != null) pushThread.interrupt();
            log.debug("WS close: session={} status={}", sessionId, statusCode);
        }

        @OnWebSocketError
        public void onError(Throwable cause) {
            closed = true;
            if (pushThread != null) pushThread.interrupt();
            log.debug("WS error: session={} cause={}", sessionId, cause.getMessage());
        }

        // ── Event push loop ───────────────────────────────────────────────────

        private void pushLoop() {
            MusicStateDTO lastSentMusic = null;

            while (!closed && !Thread.currentThread().isInterrupted()) {
                try {
                    DoomSession session = resolveSession();
                    if (session == null || !session.isRunning()) {
                        Thread.sleep(100);
                        continue;
                    }

                    // Sound events
                    SoundEventDTO[] events = session.pollSoundEvents();
                    if (events.length > 0) {
                        sendText("sound:" + buildSoundEventsJson(events));
                    }

                    // Music state
                    MusicStateDTO music = session.peekMusicState();
                    if (music != null && music != lastSentMusic) {
                        lastSentMusic = music;
                        sendText("music:{\"playing\":" + music.playing
                            + ",\"changed\":true,\"looping\":" + music.looping
                            + ",\"volume\":" + music.volume + "}");
                    }

                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    if (!closed) log.debug("WS push error: {}", e.getMessage());
                }
            }
        }

        // ── Helpers ───────────────────────────────────────────────────────────

        private DoomSession resolveSession() {
            SessionManager sm = GatewayHook.getSessionManager();
            if (sm == null || sessionId == null) return null;

            if (matchId != null) {
                MatchSession match = sm.getMatch(matchId);
                if (match == null) return null;
                return match.getSlotSession(sessionId);
            }

            return sm.getSession(sessionId);
        }

        private void sendText(String msg) {
            Session s = wsSession;
            if (s != null && s.isOpen() && !closed) {
                try {
                    s.sendText(msg, Callback.NOOP);
                } catch (Exception e) {
                    log.debug("WS send failed: {}", e.getMessage());
                }
            }
        }

        private static String buildSoundEventsJson(SoundEventDTO[] events) {
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

        private static String escapeJson(String s) {
            if (s == null) return "";
            return s.replace("\\", "\\\\").replace("\"", "\\\"");
        }
    }
}
