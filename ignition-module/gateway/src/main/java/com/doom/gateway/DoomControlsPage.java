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

import com.doom.common.ControlsLegend;

/**
 * GET /system/doom/controls: the controls reference for new players.
 * Content comes from {@link ControlsLegend}; no session or password needed.
 */
final class DoomControlsPage {

    private DoomControlsPage() {}

    static String getHTML() {
        StringBuilder sections = new StringBuilder();
        for (ControlsLegend.Section sec : ControlsLegend.sections()) {
            sections.append("<section class='sec'>\n<h2>").append(esc(sec.title)).append("</h2>\n");
            if (sec.note != null) {
                sections.append("<p class='note'>").append(esc(sec.note)).append("</p>\n");
            }
            sections.append("<table>\n");
            for (ControlsLegend.Row row : sec.rows) {
                sections.append("<tr><td class='keys'>").append(keyCaps(row.keys))
                        .append("</td><td>").append(esc(row.action)).append("</td></tr>\n");
            }
            sections.append("</table>\n</section>\n");
        }

        return "<!DOCTYPE html>\n"
            + "<html lang='en'><head>\n"
            + "<meta charset='UTF-8'>\n"
            + "<meta name='viewport' content='width=device-width, initial-scale=1'>\n"
            + "<title>DOOM on Ignition - Controls</title>\n"
            + "<style>\n"
            + "* { margin:0; padding:0; box-sizing:border-box; }\n"
            + "body { background:#0d0d0d; color:#c8c8c8; font-family:'Courier New', monospace; padding:24px; line-height:1.45; }\n"
            + ".wrap { max-width:1040px; margin:0 auto; }\n"
            + "header { display:flex; align-items:baseline; gap:18px; flex-wrap:wrap; border-bottom:2px solid #ff4444; padding-bottom:12px; margin-bottom:18px; }\n"
            + "h1 { color:#ff4444; font-size:26px; letter-spacing:6px; text-transform:uppercase; }\n"
            + "header a { color:#e8a33d; text-decoration:none; font-size:13px; letter-spacing:1px; margin-left:auto; }\n"
            + "header a:hover { text-decoration:underline; }\n"
            + ".intro { color:#b4b4b4; font-size:13px; margin-bottom:22px; max-width:80ch; }\n"
            + ".grid { display:grid; grid-template-columns:repeat(auto-fill, minmax(320px, 1fr)); gap:16px; }\n"
            + ".sec { background:#111; border:1px solid #2a2a2a; padding:16px 18px; }\n"
            + "h2 { color:#ff4444; font-size:13px; letter-spacing:3px; text-transform:uppercase; padding-bottom:8px; margin-bottom:10px; border-bottom:1px solid #2a2a2a; }\n"
            + ".note { color:#9a9a9a; font-size:12px; margin:-2px 0 10px 0; }\n"
            + "table { width:100%; border-collapse:collapse; }\n"
            + "td { padding:5px 0; font-size:13px; vertical-align:top; border-bottom:1px solid #1c1c1c; }\n"
            + "tr:last-child td { border-bottom:none; }\n"
            + "td.keys { width:44%; padding-right:12px; color:#eee; }\n"
            + "kbd { display:inline-block; background:#1d1d1d; border:1px solid #444; border-bottom-width:2px; padding:0 6px; font:12px 'Courier New', monospace; color:#eee; margin:1px 0; }\n"
            + ".tips { margin-top:16px; background:#1a0a00; border:1px solid #663300; color:#e0b080; padding:12px 18px; font-size:13px; }\n"
            + ".tips h2 { color:#ff8844; border-bottom-color:#663300; }\n"
            + ".tips li { margin:4px 0 4px 18px; }\n"
            + "</style>\n"
            + "</head><body><div class='wrap'>\n"
            + "<header><h1>Controls</h1>"
            + "<a href='/system/doom/'>&larr; Back to DOOM on Ignition</a></header>\n"
            + "<p class='intro'>Keyboard and mouse controls for single-player and deathmatch. "
            + "Mouselook, autoaim, the crosshair, mouse sensitivity and invert Y are set on the "
            + "start page before you launch a game.</p>\n"
            + "<div class='grid'>\n" + sections + "</div>\n"
            + "<div class='tips'><h2>Tips</h2><ul>\n"
            + "<li>Click inside the game first: keys only reach DOOM while the game view has focus "
            + "(red outline).</li>\n"
            + "<li>Most browsers close the tab on <kbd>Ctrl</kbd>+<kbd>W</kbd> and won't let a page "
            + "stop it, so firing with <kbd>Ctrl</kbd> while moving with <kbd>W</kbd> can close the game. "
            + "Fire with the mouse, or move with the arrow keys.</li>\n"
            + "<li>Browsers may also keep <kbd>F5</kbd>, <kbd>F11</kbd> and <kbd>F12</kbd> for themselves.</li>\n"
            + "</ul></div>\n"
            + "</div></body></html>\n";
    }

    /** Wraps each key name in &lt;kbd&gt;, keeping separators like " / " and " + " as text. */
    private static String keyCaps(String keys) {
        if (isMouseText(keys)) {
            return esc(keys);
        }
        StringBuilder out = new StringBuilder();
        // Tokens split on separators; separators are kept as plain text.
        String[] parts = keys.split("((?<= / )|(?= / )|(?<= \\+ )|(?= \\+ )|(?<=, )|(?=, ))");
        for (String p : parts) {
            if (p.equals(" / ") || p.equals(" + ") || p.equals(", ")) {
                out.append(esc(p));
            } else if (p.contains("(")) {
                // e.g. "Shift (hold)" -> key + plain qualifier
                int i = p.indexOf('(');
                String key = p.substring(0, i).trim();
                if (!key.isEmpty()) out.append("<kbd>").append(esc(key)).append("</kbd> ");
                out.append("<span style='color:#9a9a9a'>").append(esc(p.substring(i))).append("</span>");
            } else {
                out.append("<kbd>").append(esc(p)).append("</kbd>");
            }
        }
        return out.toString();
    }

    /** Mouse rows describe actions in words rather than naming keys. */
    private static boolean isMouseText(String s) {
        return s.startsWith("Move ") || s.contains("button") || s.startsWith("Wheel");
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
