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

import java.util.function.Function;

/**
 * Per-game options chosen on the landing page and fixed for the life of a
 * session or match.
 *
 * <p>Both options change the game simulation (where shots go), so in a
 * deathmatch the host's choice is a rule for every player, like ZDoom's
 * "allow freelook" / "allow autoaim" server settings. Purely visual or
 * per-player preferences (crosshair, invert Y, sensitivity) are not here.
 *
 * <p>Plain Java on purpose: lives in common.jar (parent class loader) so the
 * gateway and the engine side can both use it.
 */
public final class GameplayOptions {

    /** Query parameter names. */
    public static final String PARAM_MOUSELOOK = "mlook";
    public static final String PARAM_AUTOAIM   = "autoaim";

    /** Defaults: GZDoom-style mouselook on, vanilla autoaim on. */
    public static final GameplayOptions DEFAULTS = new GameplayOptions(true, true);

    private final boolean mouselook;
    private final boolean autoaim;

    public GameplayOptions(boolean mouselook, boolean autoaim) {
        this.mouselook = mouselook;
        this.autoaim   = autoaim;
    }

    /** Vertical mouse movement controls the view pitch (freelook). */
    public boolean isMouselook() { return mouselook; }

    /** Vanilla vertical autoaim for player attacks. */
    public boolean isAutoaim() { return autoaim; }

    /**
     * Reads options from query parameters; a missing or unrecognised value
     * keeps the default. Accepts 1/0, true/false, on/off.
     */
    public static GameplayOptions fromParams(Function<String, String> param) {
        return new GameplayOptions(
            parseFlag(param.apply(PARAM_MOUSELOOK), DEFAULTS.mouselook),
            parseFlag(param.apply(PARAM_AUTOAIM),   DEFAULTS.autoaim));
    }

    /** Query-string fragment, starting with '&', that round-trips through {@link #fromParams}. */
    public String toQuery() {
        return "&" + PARAM_MOUSELOOK + "=" + (mouselook ? 1 : 0)
             + "&" + PARAM_AUTOAIM   + "=" + (autoaim   ? 1 : 0);
    }

    static boolean parseFlag(String v, boolean dflt) {
        if (v == null) return dflt;
        switch (v.trim().toLowerCase()) {
            case "1": case "true": case "on": case "yes":  return true;
            case "0": case "false": case "off": case "no": return false;
            default: return dflt;
        }
    }

    @Override
    public String toString() {
        return "mouselook=" + (mouselook ? "on" : "off") + ", autoaim=" + (autoaim ? "on" : "off");
    }
}
