package com.doom.headless;

import com.doom.common.GameplayOptions;
import com.doom.common.ISessionRunner;
import com.doom.common.TikcmdBus;
import org.junit.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * Integration test: runs real engines with the shareware DOOM1.WAD from the repo.
 *
 * <p>Not part of the normal build (the name ends in IT, which Surefire skips by
 * default). Run it after building the module:
 * <pre>
 *   cd headless-renderer
 *   mvn test -Dtest=MouselookEngineIT
 * </pre>
 * Takes about a minute. Each engine gets its own child-first class loader, as
 * SessionClassLoader does in the gateway, so engine statics never collide.
 */
public class MouselookEngineIT {

    private static final File WAD = new File("../assets/iwads/DOOM1.WAD");

    // ── Engine plumbing ───────────────────────────────────────────────────────

    /** Child-first loader over the engine classes; shared types come from the parent. */
    static final class EngineClassLoader extends URLClassLoader {
        EngineClassLoader(URL[] urls, ClassLoader parent) { super(urls, parent); }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.startsWith("com.doom.common.") || name.startsWith("java.") || name.startsWith("javax.")
                    || name.startsWith("sun.") || name.startsWith("jdk.") || name.startsWith("org.slf4j.")
                    || name.startsWith("org.junit.")) {
                return super.loadClass(name, resolve);
            }
            synchronized (getClassLoadingLock(name)) {
                Class<?> c = findLoadedClass(name);
                if (c != null) return c;
                try {
                    c = findClass(name);
                    if (resolve) resolveClass(c);
                    return c;
                } catch (ClassNotFoundException e) {
                    return super.loadClass(name, resolve);
                }
            }
        }
    }

    private static ISessionRunner newRunner() throws Exception {
        URL engineClasses = SessionRunner.class.getProtectionDomain().getCodeSource().getLocation();
        ClassLoader cl = new EngineClassLoader(new URL[]{ engineClasses }, MouselookEngineIT.class.getClassLoader());
        return (ISessionRunner) cl.loadClass("com.doom.headless.SessionRunner").getDeclaredConstructor().newInstance();
    }

    private static String newConfigDir(String prefix) throws Exception {
        return Files.createTempDirectory(prefix).toString() + File.separator;
    }

    private static String wadPath() throws Exception {
        assumeTrue("DOOM1.WAD not found at " + WAD.getAbsolutePath(), WAD.isFile());
        return WAD.getCanonicalPath();   // native separators: the engine rejects C:/ paths on Windows
    }

    private static Object get(Object o, String name) throws Exception {
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(o);
            } catch (NoSuchFieldException ignored) {
                // try the superclass
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static Object player(ISessionRunner r, int i) throws Exception {
        return ((Object[]) get(get(r, "doomMain"), "players"))[i];
    }

    private static int lookdir(ISessionRunner r, int i) throws Exception {
        return (Integer) get(player(r, i), "lookdir");
    }

    private static int gametic(ISessionRunner r) throws Exception {
        return (Integer) get(get(r, "doomMain"), "gametic");
    }

    /** Waits until the engine has run {@code n} more game tics (35 per second). */
    private static void waitTics(ISessionRunner r, int n) throws Exception {
        int target = gametic(r) + n;
        long deadline = System.currentTimeMillis() + 20_000;
        while (gametic(r) < target && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
    }

    // ── Single player ─────────────────────────────────────────────────────────

    @Test
    public void singlePlayerMouselookWheelAndFire() throws Exception {
        String wad = wadPath();
        ISessionRunner r = newRunner();
        r.setGameplayOptions(new GameplayOptions(true, true));
        r.start(wad, newConfigDir("doom-it-sp"), 640, 400, 1, 1, 2);
        try {
            waitTics(r, 70);
            assertEquals("view starts centred", 0, lookdir(r, 0));

            // 400 px of upward movement = 400 / MLOOKUNIT(8) = 50 look units, however it is split up.
            for (int i = 0; i < 20; i++) {
                r.addMouseInput(0, 20, 0, 0);
                Thread.sleep(15);
            }
            waitTics(r, 10);
            assertEquals("mouse up 400px", 50, lookdir(r, 0));

            r.addMouseInput(0, 4000, 0, 0);
            waitTics(r, 10);
            assertEquals("clamped at LOOKDIRMAX", 90, lookdir(r, 0));

            r.addMouseInput(0, -4000, 0, 0);
            waitTics(r, 5);
            r.addMouseInput(0, -4000, 0, 0);
            waitTics(r, 10);
            assertEquals("clamped at -LOOKDIRMIN", -110, lookdir(r, 0));

            // End key (Heretic TOCENTER) recentres over a few tics.
            r.updatePressedKeys(Collections.singleton(35));
            waitTics(r, 25);
            r.updatePressedKeys(Collections.emptySet());
            waitTics(r, 5);
            assertEquals("End centres the view", 0, lookdir(r, 0));

            // Horizontal movement turns: 1000 px * 8 angle units, shifted into the 32-bit angle.
            long a0 = (Long) get(get(player(r, 0), "mo"), "angle");
            r.addMouseInput(1000, 0, 0, 0);
            waitTics(r, 5);
            long a1 = (Long) get(get(player(r, 0), "mo"), "angle");
            assertNotEquals("mouse right turns", a0, a1);

            // Wheel: next weapon, then back.
            r.sendCheatKeys("idkfa");
            waitTics(r, 10);
            String w0 = String.valueOf(get(player(r, 0), "readyweapon"));
            r.addMouseInput(0, 0, 0, 1);
            waitTics(r, 60);
            String w1 = String.valueOf(get(player(r, 0), "readyweapon"));
            r.addMouseInput(0, 0, 0, -1);
            waitTics(r, 60);
            String w2 = String.valueOf(get(player(r, 0), "readyweapon"));
            assertNotEquals("wheel down selects the next weapon", w0, w1);
            assertEquals("wheel up goes back", w0, w2);

            // Left button fires.
            int before = ((int[]) get(player(r, 0), "ammo"))[0];
            r.addMouseInput(0, 0, 1, 0);
            waitTics(r, 40);
            r.addMouseInput(0, 0, 0, 0);
            waitTics(r, 5);
            int after = ((int[]) get(player(r, 0), "ammo"))[0];
            assertTrue("left button fires (" + before + " -> " + after + ")", after < before);

            assertTrue("engine still running", r.isRunning());
        } finally {
            r.stop();
        }
    }

    // ── Deathmatch: look data must reach the other engine ─────────────────────

    @Test
    public void deathmatchEnginesStayInSyncWithDirectAim() throws Exception {
        String wad = wadPath();
        TikcmdBus bus = new TikcmdBus(2);
        ISessionRunner[] rs = { newRunner(), newRunner() };
        Exception[] errors = new Exception[2];
        Thread[] starters = new Thread[2];
        for (int s = 0; s < 2; s++) {
            final int slot = s;
            rs[s].setGameplayOptions(new GameplayOptions(true, false));   // autoaim off: shots follow lookdir
            starters[s] = new Thread(() -> {
                try {
                    rs[slot].startP2P(wad, newConfigDir("doom-it-p2p" + slot), 640, 400, 1, 1, 2, 2, bus, slot);
                } catch (Exception e) {
                    errors[slot] = e;
                }
            });
            starters[s].start();
        }
        for (Thread t : starters) t.join();
        for (Exception e : errors) if (e != null) throw e;

        try {
            waitTics(rs[0], 70);

            rs[0].addMouseInput(0, 480, 0, 0);    // player 0 looks up 60
            rs[1].addMouseInput(0, -320, 0, 0);   // player 1 looks down 40
            waitTics(rs[0], 20);
            waitTics(rs[1], 20);
            for (int e = 0; e < 2; e++) {
                assertEquals("engine " + e + ": player 0 lookdir", 60, lookdir(rs[e], 0));
                assertEquals("engine " + e + ": player 1 lookdir", -40, lookdir(rs[e], 1));
            }

            // Both turn and fire while changing pitch, then compare world state.
            rs[0].addMouseInput(300, 0, 1, 0);
            rs[1].addMouseInput(-300, 0, 1, 0);
            waitTics(rs[0], 70);
            rs[0].addMouseInput(0, -800, 0, 0);
            rs[1].addMouseInput(0, 800, 0, 0);
            waitTics(rs[0], 35);
            rs[0].addMouseInput(0, 0, 0, 0);
            rs[1].addMouseInput(0, 0, 0, 0);
            waitTics(rs[0], 35);
            waitTics(rs[1], 35);

            // No mouse movement may be lost while engines wait on each other: many small
            // moves (with a movement key held, as in real play) must add up to the exact
            // turn, 8 angle units per pixel (<< 16 into the 32-bit angle).
            for (int s = 0; s < 2; s++) {
                ISessionRunner r = rs[s];
                waitTics(r, 10);
                long before = (Long) get(get(player(r, s), "mo"), "angle");
                // Player 1 holds A (strafe left), player 2 holds W: both seen in real play.
                r.updatePressedKeys(Collections.singleton(s == 0 ? 65 : 87));
                int moves = 120, px = 7;
                for (int i = 0; i < moves; i++) {
                    r.addMouseInput(px, 0, 0, 0);
                    Thread.sleep(4);
                }
                r.updatePressedKeys(Collections.emptySet());
                waitTics(rs[0], 20);
                waitTics(rs[1], 20);
                long after = (Long) get(get(player(r, s), "mo"), "angle");
                long turned = (before - after) & 0xFFFFFFFFL;
                long expected = ((long) moves * px * 8L << 16) & 0xFFFFFFFFL;
                assertEquals("slot " + s + ": every pixel of mouse movement turns the player", expected, turned);
            }

            for (int p = 0; p < 2; p++) {
                Object a = player(rs[0], p), b = player(rs[1], p);
                assertEquals("player " + p + " x", get(get(a, "mo"), "x"), get(get(b, "mo"), "x"));
                assertEquals("player " + p + " y", get(get(a, "mo"), "y"), get(get(b, "mo"), "y"));
                assertEquals("player " + p + " health", ((int[]) get(a, "health"))[0], ((int[]) get(b, "health"))[0]);
                assertEquals("player " + p + " lookdir", lookdir(rs[0], p), lookdir(rs[1], p));
                assertEquals("player " + p + " ammo", ((int[]) get(a, "ammo"))[0], ((int[]) get(b, "ammo"))[0]);
            }
            assertTrue("no consistency failure", rs[0].isRunning() && rs[1].isRunning());
        } finally {
            rs[0].stop();
            rs[1].stop();
        }
    }
}
