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

import java.util.HashSet;
import java.util.Set;

/**
 * Parses browser input messages. The same text format is used over the
 * WebSocket and in the HTTP fallback POST body (one message per line):
 *
 * <pre>
 *   87,65            pressed key codes (empty = no keys)
 *   m:dx,dy,b,w      mouse: movement since the last message (dy positive = up,
 *                    DOOM's convention), button bits (1 left, 2 right, 4 middle),
 *                    wheel weapon steps (positive = next weapon)
 *   opt:xhair=1      crosshair on (0 = off)
 * </pre>
 *
 * Mouse and option lines never change the pressed-key state.
 */
final class InputMessages {

    /** Receives parsed input. */
    interface Target {
        void keys(Set<Integer> pressed);
        void mouse(int dx, int dy, int buttons, int wheel);
        void crosshair(boolean on);
    }

    /** Limit on one message's movement, so a malformed client can't spin the view wildly. */
    private static final int MAX_MOUSE_DELTA = 4000;
    private static final int MAX_WHEEL_STEPS = 10;

    private InputMessages() {}

    /** Dispatches every line of {@code body}. */
    static void dispatchAll(String body, Target target) {
        if (body == null) return;
        if (body.indexOf('\n') < 0) {
            dispatch(body, target);
            return;
        }
        for (String line : body.split("\n")) {
            dispatch(line, target);
        }
    }

    /** Dispatches one message. */
    static void dispatch(String message, Target target) {
        if (message == null) return;
        String m = message.trim();
        if (m.startsWith("m:")) {
            String[] p = m.substring(2).split(",");
            if (p.length < 3) return;
            try {
                int dx = clamp(Integer.parseInt(p[0].trim()), MAX_MOUSE_DELTA);
                int dy = clamp(Integer.parseInt(p[1].trim()), MAX_MOUSE_DELTA);
                int buttons = Integer.parseInt(p[2].trim()) & 7;
                int wheel = p.length > 3 ? clamp(Integer.parseInt(p[3].trim()), MAX_WHEEL_STEPS) : 0;
                target.mouse(dx, dy, buttons, wheel);
            } catch (NumberFormatException ignored) {
                // malformed: ignore
            }
            return;
        }
        if (m.startsWith("opt:")) {
            String kv = m.substring(4);
            if (kv.startsWith("xhair=")) {
                target.crosshair("1".equals(kv.substring(6).trim()));
            }
            return;
        }
        if (m.indexOf(':') >= 0) {
            return; // unknown message type (e.g. a newer client): never treat it as keys
        }
        Set<Integer> keys = new HashSet<>();
        for (String part : m.split(",")) {
            String s = part.trim();
            if (!s.isEmpty()) {
                try { keys.add(Integer.parseInt(s)); }
                catch (NumberFormatException ignored) {}
            }
        }
        target.keys(keys);
    }

    private static int clamp(int v, int max) {
        return Math.max(-max, Math.min(max, v));
    }

    /** Target for a single-player session. */
    static Target forSession(DoomSession session) {
        return new Target() {
            @Override public void keys(Set<Integer> pressed) { session.updatePressedKeys(pressed); }
            @Override public void mouse(int dx, int dy, int b, int w) { session.addMouseInput(dx, dy, b, w); }
            @Override public void crosshair(boolean on) { session.setCrosshair(on); }
        };
    }

    /** Target for one player in a match (input goes to that player's engine). */
    static Target forMatchPlayer(MatchSession match, String browserSessionId) {
        return new Target() {
            @Override public void keys(Set<Integer> pressed) { match.updatePressedKeys(browserSessionId, pressed); }
            @Override public void mouse(int dx, int dy, int b, int w) { match.addMouseInput(browserSessionId, dx, dy, b, w); }
            @Override public void crosshair(boolean on) { match.setCrosshair(browserSessionId, on); }
        };
    }
}
