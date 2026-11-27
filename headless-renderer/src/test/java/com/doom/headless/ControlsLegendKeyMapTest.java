package com.doom.headless;

import com.doom.common.ControlsLegend;
import com.doom.common.GameplayOptions;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The Controls page must not list a key the game ignores: every browser key code
 * in ControlsLegend has to be in SessionRunner's key map.
 */
public class ControlsLegendKeyMapTest {

    @Test
    public void everyLegendKeyIsMapped() {
        StringBuilder missing = new StringBuilder();
        for (ControlsLegend.Section sec : ControlsLegend.sections()) {
            for (ControlsLegend.Row row : sec.rows) {
                for (int code : row.jsKeyCodes) {
                    if (!SessionRunner.keyMap().containsKey(code)) {
                        missing.append("\n  ").append(sec.title).append(" / ").append(row.keys)
                               .append(": keyCode ").append(code);
                    }
                }
            }
        }
        if (missing.length() > 0) {
            fail("Controls legend lists keys the engine key map doesn't handle:" + missing);
        }
    }

    @Test
    public void gameplayOptionsDefaultsAndParsing() {
        assertTrue(GameplayOptions.DEFAULTS.isMouselook());
        assertTrue(GameplayOptions.DEFAULTS.isAutoaim());

        Map<String, String> q = new HashMap<>();
        assertEquals(GameplayOptions.DEFAULTS.toQuery(), GameplayOptions.fromParams(q::get).toQuery());

        q.put("mlook", "0");
        q.put("autoaim", "off");
        GameplayOptions o = GameplayOptions.fromParams(q::get);
        assertFalse(o.isMouselook());
        assertFalse(o.isAutoaim());

        // Unrecognised values keep the default.
        q.put("mlook", "banana");
        assertTrue(GameplayOptions.fromParams(q::get).isMouselook());

        // toQuery() round-trips.
        GameplayOptions mixed = new GameplayOptions(true, false);
        Map<String, String> parsed = new HashMap<>();
        for (String kv : mixed.toQuery().substring(1).split("&")) {
            String[] p = kv.split("=");
            parsed.put(p[0], p[1]);
        }
        GameplayOptions back = GameplayOptions.fromParams(parsed::get);
        assertTrue(back.isMouselook());
        assertFalse(back.isAutoaim());
    }
}
