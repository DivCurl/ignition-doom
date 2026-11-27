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

    /**
     * Serialized tikcmd size — matches ticcmd_t.P2P_TICCMDLEN: the vanilla 8 bytes
     * plus lookfly (1) and the mouselook pitch change lookdelta (2).
     */
    public static final int TIKCMD_BYTES = 11;

    // ring buffer depth — 256 gives ~7s of headroom at 35 Hz (BACKUPTICS = 12)
    private static final int RING_SIZE = 256;

    private final int numPlayers;

    // ring[slot][tic % RING_SIZE] = serialized tikcmd bytes
    private final byte[][][] ring;

    // storedTic[slot][idx] = absolute tic stored at ring[slot][idx]. -1 = no data yet.
    private final int[][] storedTic;

    // disconnected[slot] = true once that player has left the match
    private final boolean[] disconnected;

    // lastPublished[slot] = highest tic the slot has published (-1 = none yet)
    private final int[] lastPublished;

    // leftAfterTic[slot] = the slot's last real tic when it left; later tics read as PLAYER_LEFT
    private final int[] leftAfterTic;

    /**
     * Returned by {@link #get} (compare by identity) for every tic after a player's
     * last one once they have left. Each remaining engine sees it at the same tic, so
     * all of them drop the player at the same point and stay in lockstep.
     */
    public static final byte[] PLAYER_LEFT = new byte[0];

    private volatile boolean closed = false;
    private final Object lock = new Object();

    public TikcmdBus(int numPlayers) {
        this.numPlayers    = numPlayers;
        this.ring          = new byte[numPlayers][RING_SIZE][TIKCMD_BYTES];
        this.storedTic     = new int[numPlayers][RING_SIZE];
        this.disconnected  = new boolean[numPlayers];
        this.lastPublished = new int[numPlayers];
        this.leftAfterTic  = new int[numPlayers];
        Arrays.fill(lastPublished, -1);
        Arrays.fill(leftAfterTic, -1);
        for (int s = 0; s < numPlayers; s++) {
            Arrays.fill(storedTic[s], -1);   // -1 = no data yet
        }
    }

    /** Publishes slot's tikcmd for the given tic. Called by P2PNetDriver on the game thread. */
    public void publish(int slot, int tic, byte[] bytes) {
        if (closed) return;
        int idx = tic & (RING_SIZE - 1);
        synchronized (lock) {
            if (disconnected[slot]) return;   // left the match: later input is ignored
            System.arraycopy(bytes, 0, ring[slot][idx], 0, TIKCMD_BYTES);
            storedTic[slot][idx] = tic;     // mark this exact tic as available
            if (tic > lastPublished[slot]) lastPublished[slot] = tic;
            lock.notifyAll();
        }
    }

    /**
     * Blocking read — waits until the tikcmd for (slot, tic) arrives.
     * Returns {@link #PLAYER_LEFT} for tics after a departed player's last one, and
     * null on timeout or if the bus was closed (never blank commands: those would
     * fail DOOM's consistency check).
     */
    public byte[] get(int slot, int tic, long timeoutMs) throws InterruptedException {
        int idx = tic & (RING_SIZE - 1);
        long deadline = System.currentTimeMillis() + timeoutMs;
        synchronized (lock) {
            while (storedTic[slot][idx] != tic && !closed && !hasLeftBefore(slot, tic)) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    return null;
                }
                // Overflow guard: if the ring slot holds a tic far ahead of what we're waiting
                // for, the buffer has wrapped and our tic was overwritten. Return null so the
                // caller treats this the same as a timeout (→ playerDisconnected).
                if (storedTic[slot][idx] != -1 && storedTic[slot][idx] > tic + (RING_SIZE >>> 1)) {
                    return null;
                }
                lock.wait(remaining);
            }
            if (closed) return null;
            if (hasLeftBefore(slot, tic)) return PLAYER_LEFT;
            byte[] result = new byte[TIKCMD_BYTES];
            System.arraycopy(ring[slot][idx], 0, result, 0, TIKCMD_BYTES);
            return result;
        }
    }

    /** True if the slot left the match and {@code tic} comes after its last real one. Holds {@code lock}. */
    private boolean hasLeftBefore(int slot, int tic) {
        return disconnected[slot] && tic > leftAfterTic[slot];
    }

    /**
     * Marks the slot as having left. Its tics up to the last one it published are still
     * delivered (other engines may not have read them yet); every later tic reads as
     * {@link #PLAYER_LEFT}. Further publishes from the slot are ignored. Idempotent.
     */
    public void playerDisconnected(int slot) {
        synchronized (lock) {
            if (disconnected[slot]) return;
            disconnected[slot] = true;
            leftAfterTic[slot] = lastPublished[slot];
            lock.notifyAll();
        }
    }

    /** True once the slot has left the match. */
    public boolean isDisconnected(int slot) {
        synchronized (lock) {
            return disconnected[slot];
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
