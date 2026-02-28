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

/**
 * Serves a minimal HTML page with JavaScript for capturing keyboard input.
 *
 * This page is loaded in a Perspective Inline Frame component overlaying the
 * DOOM game image. JavaScript captures all keyboard events with preventDefault(),
 * maintains the set of currently pressed keys client-side, and POSTs the state
 * to the gateway's /data/doom/input endpoint on every change.
 *
 * This approach bypasses Perspective's event system entirely, eliminating
 * race conditions and lost key-up events that caused "stuck keys."
 */
public class KeyboardCapturePage {

    public static String getHTML() {
        return "<!DOCTYPE html>\n"
            + "<html><head><style>\n"
            + "* { margin:0; padding:0; }\n"
            + "body { width:100%; height:100vh; overflow:hidden; background:transparent; }\n"
            + "#capture {\n"
            + "  width:100%; height:100vh; outline:none;\n"
            + "  display:flex; align-items:center; justify-content:center;\n"
            + "  font-family:monospace; font-size:14px; color:rgba(0,0,0,0.3);\n"
            + "  cursor:default; user-select:none;\n"
            + "}\n"
            + "#capture:focus { color:transparent; }\n"
            + "</style></head><body>\n"
            + "<div id='capture' tabindex='0'>Click to play</div>\n"
            + "<script>\n"
            + "var el = document.getElementById('capture');\n"
            + "var pressed = {};\n"
            + "var sendTimer = null;\n"
            + "\n"
            + "function sendState() {\n"
            + "  var keys = Object.keys(pressed).filter(function(k) { return pressed[k]; });\n"
            + "  var body = keys.join(',');\n"
            + "  fetch('/system/doom/input', {\n"
            + "    method: 'POST',\n"
            + "    headers: {'Content-Type': 'text/plain'},\n"
            + "    body: body\n"
            + "  }).catch(function() {});\n"
            + "}\n"
            + "\n"
            + "function schedSend() {\n"
            + "  if (!sendTimer) {\n"
            + "    sendTimer = setTimeout(function() { sendTimer = null; sendState(); }, 8);\n"
            + "  }\n"
            + "}\n"
            + "\n"
            + "el.addEventListener('keydown', function(e) {\n"
            + "  e.preventDefault();\n"
            + "  e.stopPropagation();\n"
            + "  if (!pressed[e.keyCode]) {\n"
            + "    pressed[e.keyCode] = true;\n"
            + "    schedSend();\n"
            + "  }\n"
            + "});\n"
            + "\n"
            + "el.addEventListener('keyup', function(e) {\n"
            + "  e.preventDefault();\n"
            + "  e.stopPropagation();\n"
            + "  if (pressed[e.keyCode]) {\n"
            + "    delete pressed[e.keyCode];\n"
            + "    schedSend();\n"
            + "  }\n"
            + "});\n"
            + "\n"
            + "el.addEventListener('blur', function() {\n"
            + "  pressed = {};\n"
            + "  sendState();\n"
            + "});\n"
            + "\n"
            + "el.focus();\n"
            + "</script>\n"
            + "</body></html>";
    }
}
