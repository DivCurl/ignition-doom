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

import g.Signals.ScanCode;
import doom.event_t;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Maps JavaScript keyCode values to Mocha DOOM ScanCode enums.
 *
 * JavaScript keyCode values are identical to Java AWT VK_ constants
 * for letters (65-90), digits (48-57), arrows (37-40), space (32),
 * escape (27), enter (13), shift (16), ctrl (17), alt (18), and F-keys (112-123).
 */
public class KeyMapping {

    private static final Map<Integer, ScanCode> KEY_MAP = new HashMap<>();

    static {
        // Build mapping from JS keyCode → ScanCode
        // ScanCode enum stores the AWT VK_ value, which matches JS keyCode
        for (ScanCode sc : ScanCode.values()) {
            if (sc == ScanCode.SC_NULL) continue;
            // ScanCode stores virtualKey as char 'c' (lowercase of VK_)
            // but we need the actual VK_ value. The 'c' field is lowercase char.
            // We need to map by the original VK_ constant.
        }

        // Explicit mappings for all essential DOOM keys
        // Letters (JS keyCode = ASCII uppercase = AWT VK_ value)
        // WASD mapped to arrow scan codes so both WASD and arrows control movement.
        // DoomEngineWrapper remaps key_up/down/left/right to SC_UP/DOWN/LEFT/RIGHT.
        KEY_MAP.put(87, ScanCode.SC_UP);    // W → forward (same as Up arrow)
        KEY_MAP.put(83, ScanCode.SC_DOWN);  // S → backward (same as Down arrow)
        KEY_MAP.put(65, ScanCode.SC_A);     // A → strafe left
        KEY_MAP.put(68, ScanCode.SC_D);     // D → strafe right

        // Other letters
        KEY_MAP.put(66, ScanCode.SC_B);
        KEY_MAP.put(67, ScanCode.SC_C);
        KEY_MAP.put(69, ScanCode.SC_E);
        KEY_MAP.put(70, ScanCode.SC_F);
        KEY_MAP.put(71, ScanCode.SC_G);
        KEY_MAP.put(72, ScanCode.SC_H);
        KEY_MAP.put(73, ScanCode.SC_I);
        KEY_MAP.put(74, ScanCode.SC_J);
        KEY_MAP.put(75, ScanCode.SC_K);
        KEY_MAP.put(76, ScanCode.SC_L);
        KEY_MAP.put(77, ScanCode.SC_M);
        KEY_MAP.put(78, ScanCode.SC_N);
        KEY_MAP.put(79, ScanCode.SC_O);
        KEY_MAP.put(80, ScanCode.SC_P);
        KEY_MAP.put(81, ScanCode.SC_Q);
        KEY_MAP.put(82, ScanCode.SC_R);
        KEY_MAP.put(84, ScanCode.SC_T);
        KEY_MAP.put(85, ScanCode.SC_U);
        KEY_MAP.put(86, ScanCode.SC_V);
        KEY_MAP.put(88, ScanCode.SC_X);
        KEY_MAP.put(89, ScanCode.SC_Y);
        KEY_MAP.put(90, ScanCode.SC_Z);

        // Digits
        KEY_MAP.put(48, ScanCode.SC_0);
        KEY_MAP.put(49, ScanCode.SC_1);     // Weapon 1 (fist/chainsaw)
        KEY_MAP.put(50, ScanCode.SC_2);     // Weapon 2 (pistol)
        KEY_MAP.put(51, ScanCode.SC_3);     // Weapon 3 (shotgun)
        KEY_MAP.put(52, ScanCode.SC_4);     // Weapon 4 (chaingun)
        KEY_MAP.put(53, ScanCode.SC_5);     // Weapon 5 (rocket launcher)
        KEY_MAP.put(54, ScanCode.SC_6);
        KEY_MAP.put(55, ScanCode.SC_7);
        KEY_MAP.put(56, ScanCode.SC_8);
        KEY_MAP.put(57, ScanCode.SC_9);

        // Arrow keys
        KEY_MAP.put(37, ScanCode.SC_LEFT);  // Turn left
        KEY_MAP.put(38, ScanCode.SC_UP);    // Forward (alt)
        KEY_MAP.put(39, ScanCode.SC_RIGHT); // Turn right
        KEY_MAP.put(40, ScanCode.SC_DOWN);  // Backward (alt)

        // Special keys
        KEY_MAP.put(32, ScanCode.SC_SPACE);     // Use/Open doors
        KEY_MAP.put(17, ScanCode.SC_LCTRL);     // Fire weapon
        KEY_MAP.put(16, ScanCode.SC_LSHIFT);    // Run
        KEY_MAP.put(18, ScanCode.SC_LALT);      // Strafe modifier
        KEY_MAP.put(27, ScanCode.SC_ESCAPE);    // Menu
        KEY_MAP.put(13, ScanCode.SC_ENTER);     // Confirm
        KEY_MAP.put(9, ScanCode.SC_TAB);        // Automap
        KEY_MAP.put(8, ScanCode.SC_BACKSPACE);  // Back

        // F-keys
        KEY_MAP.put(112, ScanCode.SC_F1);   // Help
        KEY_MAP.put(113, ScanCode.SC_F2);   // Save
        KEY_MAP.put(114, ScanCode.SC_F3);   // Load
        KEY_MAP.put(115, ScanCode.SC_F4);   // Sound volume
        KEY_MAP.put(116, ScanCode.SC_F5);   // Detail
        KEY_MAP.put(117, ScanCode.SC_F6);   // Quicksave
        KEY_MAP.put(118, ScanCode.SC_F7);   // End game
        KEY_MAP.put(119, ScanCode.SC_F8);   // Messages
        KEY_MAP.put(120, ScanCode.SC_F9);   // Quickload
        KEY_MAP.put(121, ScanCode.SC_F10);  // Quit
        KEY_MAP.put(122, ScanCode.SC_F11);  // Gamma
        KEY_MAP.put(123, ScanCode.SC_F12);
    }

    /**
     * Gets all mapped JavaScript keyCodes for polling.
     */
    public static Set<Integer> getAllKeyCodes() {
        return KEY_MAP.keySet();
    }

    /**
     * Gets the ScanCode for a JavaScript keyCode.
     *
     * @param jsKeyCode The JavaScript keyCode value
     * @return The corresponding ScanCode, or null if unmapped
     */
    public static ScanCode getScanCode(int jsKeyCode) {
        return KEY_MAP.get(jsKeyCode);
    }

    /**
     * Gets the pre-created DOOM key-down event for a JavaScript keyCode.
     *
     * @param jsKeyCode The JavaScript keyCode value
     * @return The event_t for key down, or null if unmapped
     */
    public static event_t getKeyDownEvent(int jsKeyCode) {
        ScanCode sc = KEY_MAP.get(jsKeyCode);
        return sc != null ? sc.doomEventDown : null;
    }

    /**
     * Gets the pre-created DOOM key-up event for a JavaScript keyCode.
     *
     * @param jsKeyCode The JavaScript keyCode value
     * @return The event_t for key up, or null if unmapped
     */
    public static event_t getKeyUpEvent(int jsKeyCode) {
        ScanCode sc = KEY_MAP.get(jsKeyCode);
        return sc != null ? sc.doomEventUp : null;
    }
}
