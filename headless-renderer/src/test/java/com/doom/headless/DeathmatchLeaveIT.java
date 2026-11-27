package com.doom.headless;

import com.doom.common.GameplayOptions;
import com.doom.common.ISessionRunner;
import com.doom.common.TikcmdBus;
import org.junit.Test;

import static com.doom.headless.MouselookEngineIT.gametic;
import static com.doom.headless.MouselookEngineIT.get;
import static com.doom.headless.MouselookEngineIT.newConfigDir;
import static com.doom.headless.MouselookEngineIT.newRunner;
import static com.doom.headless.MouselookEngineIT.player;
import static com.doom.headless.MouselookEngineIT.waitTics;
import static com.doom.headless.MouselookEngineIT.wadPath;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Integration test: a player leaving a deathmatch must not freeze the others.
 *
 * <p>Run like {@link MouselookEngineIT}:
 * <pre>
 *   cd headless-renderer
 *   mvn test -Dtest=DeathmatchLeaveIT
 * </pre>
 */
public class DeathmatchLeaveIT {

    private static ISessionRunner[] startMatch(int players, TikcmdBus bus) throws Exception {
        String wad = wadPath();
        ISessionRunner[] rs = new ISessionRunner[players];
        Exception[] errors = new Exception[players];
        Thread[] starters = new Thread[players];
        for (int s = 0; s < players; s++) {
            final int slot = s;
            rs[s] = newRunner();
            rs[s].setGameplayOptions(GameplayOptions.DEFAULTS);
            starters[s] = new Thread(() -> {
                try {
                    rs[slot].startP2P(wad, newConfigDir("doom-leave" + slot), 640, 400, 1, 1, 2, players, bus, slot);
                } catch (Exception e) {
                    errors[slot] = e;
                }
            });
            starters[s].start();
        }
        for (Thread t : starters) t.join();
        for (Exception e : errors) if (e != null) throw e;
        return rs;
    }

    private static boolean inGame(ISessionRunner r, int p) throws Exception {
        return ((boolean[]) get(get(r, "doomMain"), "playeringame"))[p];
    }

    /** The newest line on the HUD message display (the HUD takes player.message within a tic). */
    private static String hudMessage(ISessionRunner r) throws Exception {
        Object stext = get(get(get(r, "doomMain"), "headsUp"), "w_message");
        Object[] lines = (Object[]) get(stext, "lines");
        Object line = lines[(Integer) get(stext, "currline")];
        return new String((char[]) get(line, "text"), 0, (Integer) get(line, "len"));
    }

    /** Stops a player's engine and reports the departure, as MatchSession does. */
    private static void leave(ISessionRunner[] rs, TikcmdBus bus, int slot) {
        rs[slot].stop();
        bus.playerDisconnected(slot);
    }

    @Test
    public void threePlayers_oneLeaves_othersContinueInSync() throws Exception {
        TikcmdBus bus = new TikcmdBus(3);
        ISessionRunner[] rs = startMatch(3, bus);
        try {
            waitTics(rs[0], 70);
            rs[0].addMouseInput(400, 0, 1, 0);   // keep everyone busy: turn and fire
            rs[1].addMouseInput(-400, 0, 1, 0);
            rs[2].addMouseInput(0, 200, 1, 0);
            waitTics(rs[0], 35);

            leave(rs, bus, 2);

            // The others must keep running: 3 s of game time within the wait budget.
            int t0 = gametic(rs[0]), t1 = gametic(rs[1]);
            waitTics(rs[0], 105);
            waitTics(rs[1], 105);
            assertTrue("player 1's engine keeps running", gametic(rs[0]) >= t0 + 105);
            assertTrue("player 2's engine keeps running", gametic(rs[1]) >= t1 + 105);

            for (int e = 0; e < 2; e++) {
                assertFalse("engine " + e + ": player 3 is out of the game", inGame(rs[e], 2));
                assertTrue("engine " + e + ": players 1 and 2 still in", inGame(rs[e], 0) && inGame(rs[e], 1));
            }

            // Still in lockstep afterwards.
            rs[0].addMouseInput(0, 0, 0, 0);
            rs[1].addMouseInput(0, 0, 0, 0);
            waitTics(rs[0], 35);
            waitTics(rs[1], 35);
            for (int p = 0; p < 2; p++) {
                Object a = player(rs[0], p), b = player(rs[1], p);
                assertEquals("player " + (p + 1) + " x", get(get(a, "mo"), "x"), get(get(b, "mo"), "x"));
                assertEquals("player " + (p + 1) + " y", get(get(a, "mo"), "y"), get(get(b, "mo"), "y"));
                assertEquals("player " + (p + 1) + " health",
                    ((int[]) get(a, "health"))[0], ((int[]) get(b, "health"))[0]);
            }
            assertTrue("no consistency failure", rs[0].isRunning() && rs[1].isRunning());
        } finally {
            for (ISessionRunner r : rs) r.stop();
        }
    }

    @Test
    public void twoPlayers_guestLeaves_hostPlaysOnAndIsTold() throws Exception {
        TikcmdBus bus = new TikcmdBus(2);
        ISessionRunner[] rs = startMatch(2, bus);
        try {
            waitTics(rs[0], 70);
            leave(rs, bus, 1);

            int t0 = gametic(rs[0]);
            waitTics(rs[0], 70);
            assertTrue("host keeps running alone", gametic(rs[0]) >= t0 + 70);
            assertFalse("guest is out of the game", inGame(rs[0], 1));
            assertTrue("host is running", rs[0].isRunning());
        } finally {
            for (ISessionRunner r : rs) r.stop();
        }
    }

    @Test
    public void hostLeaves_guestPlaysOnAndSeesMessage() throws Exception {
        TikcmdBus bus = new TikcmdBus(2);
        ISessionRunner[] rs = startMatch(2, bus);
        try {
            waitTics(rs[1], 70);
            leave(rs, bus, 0);
            // HUD messages last ~4 s; check soon after the leave is processed.
            waitTics(rs[1], 10);
            assertEquals("PLAYER 1 LEFT THE GAME", hudMessage(rs[1]).toUpperCase());
            int t1 = gametic(rs[1]);
            waitTics(rs[1], 70);
            assertTrue("guest keeps running alone", gametic(rs[1]) >= t1 + 70);
            assertFalse("host is out of the game", inGame(rs[1], 0));
        } finally {
            for (ISessionRunner r : rs) r.stop();
        }
    }
}
