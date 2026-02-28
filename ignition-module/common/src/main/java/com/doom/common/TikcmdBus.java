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

/**
 * Ring buffer for tikcmd exchange between N independent engine threads.
 *
 * Each engine calls publish(mySlot, tic, bytes) each game tic, then blocks on
 * get(otherSlot, tic) for each peer. TryRunTics() advances once all slots have
 * published — same P2P lockstep design DOOM was built for.
 *
 * All methods synchronized. Only java.* types — safe across the CL boundary
 * since common.jar lives in the parent CL.
 *
 * storedTic[][] tracks the absolute tic at each ring slot so get() doesn't
 * hand back stale data after the ring wraps at 256 tics (~7s at 35 Hz).
 */
public class TikcmdBus {

    /** Serialized tikcmd size — matches ticcmd_t.TICCMDLEN */
    public static final int TIKCMD_BYTES = 8;

    // ring buffer depth — 256 gives ~7s of headroom at 35 Hz (BACKUPTICS = 12)
    private static final int RING_SIZE = 256;

    private final int numPlayers;

    // ring[slot][tic % RING_SIZE] = serialized tikcmd bytes
    private final byte[][][] ring;

    // storedTic[slot][idx] = absolute tic stored at ring[slot][idx]. -1 = no data yet.
    private final int[][] storedTic;

    // disconnected[slot] = true when that player's session ended
    private final boolean[] disconnected;

    private volatile boolean closed = false;
    private final Object lock = new Object();

    public TikcmdBus(int numPlayers) {
        this.numPlayers   = numPlayers;
        this.ring         = new byte[numPlayers][RING_SIZE][TIKCMD_BYTES];
        this.storedTic    = new int[numPlayers][RING_SIZE];
        this.disconnected = new boolean[numPlayers];
        for (int s = 0; s < numPlayers; s++) {
            Arrays.fill(storedTic[s], -1);   // -1 = no data yet
        }
    }

    /** Publishes slot's tikcmd for the given tic. Called by P2PNetDriver on the game thread. */
    public void publish(int slot, int tic, byte[] bytes) {
        if (closed) return;
        int idx = tic & (RING_SIZE - 1);
        synchronized (lock) {
            System.arraycopy(bytes, 0, ring[slot][idx], 0, TIKCMD_BYTES);
            storedTic[slot][idx] = tic;     // mark this exact tic as available
            lock.notifyAll();
        }
    }

    /**
     * Blocking read — waits until the tikcmd for (slot, tic) arrives.
     * Returns null on timeout or if the bus was closed.
     */
    public byte[] get(int slot, int tic, long timeoutMs) throws InterruptedException {
        // disconnected players auto-yield empty tikcmds so other engines don't freeze
        if (disconnected[slot]) {
            return new byte[TIKCMD_BYTES];
        }
        int idx = tic & (RING_SIZE - 1);
        long deadline = System.currentTimeMillis() + timeoutMs;
        synchronized (lock) {
            while (storedTic[slot][idx] != tic && !closed && !disconnected[slot]) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    return null;
                }
                lock.wait(remaining);
            }
            if (closed || disconnected[slot]) return new byte[TIKCMD_BYTES];
            byte[] result = new byte[TIKCMD_BYTES];
            System.arraycopy(ring[slot][idx], 0, result, 0, TIKCMD_BYTES);
            return result;
        }
    }

    /** Marks slot as disconnected — future get() calls return empty tikcmds so other engines keep running. */
    public void playerDisconnected(int slot) {
        synchronized (lock) {
            disconnected[slot] = true;
            lock.notifyAll();
        }
    }

    /** Closes the bus and wakes all waiting threads. Call on match end. */
    public void close() {
        synchronized (lock) {
            closed = true;
            lock.notifyAll();
        }
    }

    public boolean isClosed() {
        return closed;
    }

    public int getNumPlayers() {
        return numPlayers;
    }
}
