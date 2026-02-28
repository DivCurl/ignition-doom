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

package doom;

import com.doom.common.TikcmdBus;
import n.DoomSystemNetworking;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Per-engine P2P tikcmd-sync net driver for DOOM on Ignition Perspective.
 *
 * Each player gets their OWN independent DOOM engine (DoomMain instance), and
 * this driver synchronizes them via a shared TikcmdBus: each engine publishes
 * its consoleplayer's tikcmds and reads remote players' tikcmds from the bus
 * each game tic, implementing DOOM's original lockstep P2P network design.
 *
 * Lives in the {@code doom} package to access package-private fields of DoomMain:
 * {@code nettics[]}, {@code nodeingame[]}, {@code maketic}, {@code nodeforplayer[]}.
 *
 * Phase 6.3: P2P tikcmd-sync multiplayer.
 */
public class P2PNetDriver<T, V> implements DoomSystemNetworking, NetConsts {

    private static final Logger log = LoggerFactory.getLogger("DOOM.P2PNet");

    private final DoomMain<T, V> dm;
    private final int numPlayers;
    private final int playerSlot;
    private final TikcmdBus bus;

    /**
     * Tracks the last absolute tic number published to the bus.
     * Guards against duplicate publishes when TrySendPackets calls HSendPacket
     * for multiple remote nodes (N > 2 player games).
     */
    private int lastPublishedTic = -1;

    public P2PNetDriver(DoomMain<T, V> dm, int numPlayers, int playerSlot, TikcmdBus bus) {
        this.dm         = dm;
        this.numPlayers = numPlayers;
        this.playerSlot = playerSlot;
        this.bus        = bus;
    }

    /**
     * Arms all multiplayer game state after Engine.initializeWithArgs() returns.
     * Must be called BEFORE dm.setupLoop() starts the game loop.
     * Sets netgame=true, consoleplayer=playerSlot, configures playeringame[]/nodeingame[].
     */
    public void setupForGame() {
        dm.netgame    = true;
        dm.deathmatch = true;

        dm.doomcom.numplayers = (short) numPlayers;
        dm.doomcom.numnodes   = (short) numPlayers;
        dm.doomcom.deathmatch = 1;
        dm.consoleplayer  = playerSlot;
        dm.displayplayer  = playerSlot;

        for (int i = 0; i < numPlayers; i++) {
            dm.playeringame[i] = true;
            dm.nodeingame[i]   = true;
            dm.nettics[i]      = 0;
            dm.nodeforplayer[i]= i;
        }
        for (int i = numPlayers; i < data.Limits.MAXNETNODES; i++) {
            dm.nodeingame[i] = false;
        }
        log.info("P2PNetDriver.setupForGame(): slot={} of {} players, netgame=true, deathmatch=1",
                playerSlot, numPlayers);
    }

    // ── DoomSystemNetworking ─────────────────────────────────────────────────

    /**
     * InitNetwork is called during Engine.initializeWithArgs() → CheckNetGame().
     * We do a minimal single-player-style init (netgame=false) to avoid
     * ArbitrateNetStart(). The real multiplayer state is set later via setupForGame().
     */
    @Override
    public void InitNetwork() {
        doomcom_t dc = new doomcom_t();
        dc.id            = DOOMCOM_ID;
        dc.ticdup        = 1;
        dc.numplayers    = (short) numPlayers;
        dc.numnodes      = (short) numPlayers;
        dc.consoleplayer = (short) playerSlot;
        dc.deathmatch    = 0;
        // netgame stays false here — CheckNetGame() skips ArbitrateNetStart()
        dm.gameNetworking.setDoomCom(dc);
    }

    /**
     * Called by DoomMain.HSendPacket(node, flags) for non-consoleplayer nodes (CMD_SEND),
     * and by HGetPacket() when polling for incoming data (CMD_GET).
     *
     * CMD_SEND: DoomMain is "sending" our consoleplayer's tikcmds to remote nodes.
     *   We publish all newly-built localcmds to the TikcmdBus so remote engines
     *   can read them. A lastPublishedTic guard prevents duplicate publishes in
     *   N > 2 player games (HSendPacket fires once per remote node).
     *
     * CMD_GET: Read tikcmds for remote nodes from the TikcmdBus and inject them
     *   directly into dm.netcmds[node][tic % BACKUPTICS], advancing dm.nettics[node].
     *   Set remotenode=-1 to bypass GetPackets' netbuffer processing (we've already
     *   injected the data directly).
     */
    @Override
    public void NetCmd() {
        short cmd = dm.doomcom.command;

        if (cmd == CMD_SEND) {
            // Publish all localcmds from (lastPublishedTic+1) to (maketic-1) to the bus.
            // localcmds[tic % BACKUPTICS] contains the consoleplayer's built tikcmds.
            for (int t = lastPublishedTic + 1; t < dm.maketic; t++) {
                // pack() returns the internal buffer; bus.publish() copies it immediately.
                bus.publish(playerSlot, t, dm.localcmds[t % data.Defines.BACKUPTICS].pack());
            }
            if (dm.maketic - 1 > lastPublishedTic) {
                lastPublishedTic = dm.maketic - 1;
            }

        } else if (cmd == CMD_GET) {
            // Read and inject tikcmds for all remote nodes that are behind.
            // bus.get() blocks (with lock.wait) until the remote engine publishes,
            // then returns immediately — efficient lockstep synchronization.
            for (int n = 0; n < numPlayers; n++) {
                if (n == playerSlot || !dm.nodeingame[n]) continue;
                int tic = dm.nettics[n];
                try {
                    byte[] bytes = bus.get(n, tic, 1); // 1 ms: spin-friendly, wakes on notifyAll
                    if (bytes != null) {
                        dm.netcmds[n][tic % data.Defines.BACKUPTICS].unpack(bytes);
                        dm.nettics[n]++;
                    }
                    // else: null → remote engine hasn't published yet; TryRunTics will retry
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            // Set remotenode=-1 so HGetPacket() returns false, stopping GetPackets' loop.
            // All injection was done directly above, bypassing the netbuffer path.
            dm.doomcom.remotenode = -1;
        }
    }
}
