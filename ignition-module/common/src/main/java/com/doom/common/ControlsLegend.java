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

package com.doom.common;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The controls shown on the Controls page ({@code /system/doom/controls}).
 *
 * <p>Kept in common.jar so the gateway can render it and the engine module can
 * test it: every browser key code listed here must be in SessionRunner's key
 * map, so the page can't promise a key the game ignores.
 */
public final class ControlsLegend {

    private ControlsLegend() {}

    /** One line of the legend. */
    public static final class Row {
        public final String keys;
        public final String action;
        /** Browser keyCodes this row relies on (empty for mouse or page-handled input). */
        public final int[] jsKeyCodes;

        Row(String keys, String action, int... jsKeyCodes) {
            this.keys = keys;
            this.action = action;
            this.jsKeyCodes = jsKeyCodes;
        }
    }

    /** A titled group of rows, with an optional note shown under the title. */
    public static final class Section {
        public final String title;
        public final String note;
        public final List<Row> rows;

        Section(String title, String note, Row... rows) {
            this.title = title;
            this.note = note;
            this.rows = Collections.unmodifiableList(Arrays.asList(rows));
        }
    }

    private static final List<Section> SECTIONS = Collections.unmodifiableList(Arrays.asList(
        new Section("Movement", null,
            new Row("W / \u2191", "Move forward", 87, 38),
            new Row("S / \u2193", "Move backward", 83, 40),
            new Row("A / D", "Strafe left / right", 65, 68),
            new Row("\u2190 / \u2192", "Turn left / right", 37, 39),
            new Row("Shift (hold)", "Run", 16),
            new Row("Alt (hold) + \u2190 / \u2192", "Strafe with the turn keys", 18)),

        new Section("Mouse",
            "Click the game view to capture the mouse. Esc releases it.",
            new Row("Move left / right", "Turn"),
            new Row("Move up / down", "Look up / down (Mouselook on). With Mouselook off: move forward / back"),
            new Row("Left button", "Fire"),
            new Row("Right button", "Use: open doors, flip switches"),
            new Row("Wheel down / up", "Next / previous weapon")),

        new Section("Weapons and actions", null,
            new Row("Ctrl", "Fire", 17),
            new Row("Space", "Use: open doors, flip switches", 32),
            new Row("1", "Fist / chainsaw", 49),
            new Row("2", "Pistol", 50),
            new Row("3", "Shotgun / super shotgun (DOOM II)", 51),
            new Row("4", "Chaingun", 52),
            new Row("5", "Rocket launcher", 53),
            new Row("6", "Plasma rifle", 54),
            new Row("7", "BFG 9000", 55)),

        new Section("Looking with the keyboard", null,
            new Row("Page Up / Page Down", "Look up / down", 33, 34),
            new Row("End", "Centre the view", 35)),

        new Section("Map and screen", null,
            new Row("Tab", "Automap on / off", 9),
            new Row("- / =", "Automap: zoom out / in. In the game: shrink / grow the view", 189, 187),
            new Row("F / G (automap)", "Follow player / grid on or off", 70, 71),
            new Row("M / C (automap)", "Add a mark / clear marks", 77, 67),
            new Row("F11", "Brightness (gamma)", 122)),

        new Section("Menus and game", null,
            new Row("Esc", "Menu (if the mouse is captured, the first press releases it)", 27),
            new Row("\u2191 / \u2193, Enter, Backspace", "Move, select and go back in menus", 38, 40, 13, 8),
            new Row("Y / N", "Answer yes / no prompts", 89, 78),
            new Row("F1", "Help", 112),
            new Row("F2 / F3", "Save / load game", 113, 114),
            new Row("F4", "Sound volume", 115),
            new Row("F6 / F9", "Quicksave / quickload", 117, 120),
            new Row("F7", "End game", 118),
            new Row("F8", "Messages on / off", 119),
            new Row("Pause", "Pause", 19),
            new Row("T", "Chat (deathmatch)", 84),
            new Row("~", "Console: warp, god, noclip, give all, help (single-player)"))
    ));

    public static List<Section> sections() {
        return SECTIONS;
    }
}
