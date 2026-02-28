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

package s;

import data.sfxinfo_t;
import doom.DoomMain;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import static data.sounds.S_sfx;

/**
 * Headless sound driver for Ignition DOOM.
 *
 * Instead of playing sounds via Java Sound API, this driver captures
 * sound events and makes them available for transport to the browser
 * where they will be played via Web Audio API.
 *
 * Phase 4.1: Sound Event Capture
 */
public class HeadlessSoundDriver implements ISoundDriver {

    private final DoomMain<?, ?> doomMain;
    private final int numChannels;
    private final AtomicInteger nextHandle = new AtomicInteger(0);

    /** Queue of sound events to be sent to browser */
    private final ConcurrentLinkedQueue<SoundEvent> soundEvents = new ConcurrentLinkedQueue<>();

    /** Active sound handles (for tracking what's playing) */
    private final boolean[] activeSounds = new boolean[MAXHANDLES];

    /** Sound event data structure */
    public static class SoundEvent {
        public final int handle;
        public final int sfxId;
        public final String sfxName;
        public final int volume;      // 0-127
        public final int separation;  // 0-255 (128=center, <128=left, >128=right)
        public final int pitch;       // 128=normal
        public final long timestamp;
        /** DOOM fixed-point world X of the sound origin; 0 if not positional */
        public final int originX;
        /** DOOM fixed-point world Y of the sound origin; 0 if not positional */
        public final int originY;
        /** true = positional (origin ≠ listener); false = player's own sound (NORM_SEP) */
        public final boolean isPositional;

        public SoundEvent(int handle, int sfxId, String sfxName, int volume, int separation, int pitch,
                          int originX, int originY, boolean isPositional) {
            this.handle = handle;
            this.sfxId = sfxId;
            this.sfxName = sfxName;
            this.volume = volume;
            this.separation = separation;
            this.pitch = pitch;
            this.originX = originX;
            this.originY = originY;
            this.isPositional = isPositional;
            this.timestamp = System.currentTimeMillis();
        }

        @Override
        public String toString() {
            return String.format("SoundEvent[%d: %s vol=%d sep=%d pitch=%d pos=%b ox=%d oy=%d]",
                handle, sfxName, volume, separation, pitch, isPositional, originX, originY);
        }
    }

    // ── Pending origin (set by AbstractDoomAudio immediately before StartSound) ──
    // Both calls happen on the game thread, so volatile is sufficient.
    private volatile int     pendingOriginX       = 0;
    private volatile int     pendingOriginY       = 0;
    private volatile boolean pendingIsPositional  = false;

    /**
     * Called by AbstractDoomAudio.StartSoundAtVolume() immediately before ISND.StartSound()
     * to record the sound-origin coordinates.  These are captured into the SoundEvent so that
     * SessionRunner can recalculate volume/separation for each player slot independently.
     */
    public void setPendingOrigin(int x, int y, boolean positional) {
        this.pendingOriginX      = x;
        this.pendingOriginY      = y;
        this.pendingIsPositional = positional;
    }

    public HeadlessSoundDriver(DoomMain<?, ?> doomMain, int numChannels) {
        this.doomMain = doomMain;
        this.numChannels = numChannels;
    }

    @Override
    public boolean InitSound() {
        System.out.println("HeadlessSoundDriver: Initialized (event capture mode)");
        return true;
    }

    @Override
    public void UpdateSound() {
        // No-op for headless mode - we don't mix audio samples
    }

    @Override
    public void SubmitSound() {
        // No-op for headless mode - browser handles playback
    }

    @Override
    public void ShutdownSound() {
        soundEvents.clear();
        System.out.println("HeadlessSoundDriver: Shutdown");
    }

    @Override
    public void SetChannels(int numChannels) {
        // No-op - browser manages channels
    }

    @Override
    public int GetSfxLumpNum(sfxinfo_t sfxinfo) {
        // Sound lumps in WAD are prefixed with "DS" (e.g., "DSPISTOL")
        String lumpName = String.format("ds%s", sfxinfo.name).toUpperCase();

        if ("DSNONE".equals(lumpName)) {
            return -1;
        }

        try {
            return doomMain.wadLoader.GetNumForName(lumpName);
        } catch (Exception e) {
            System.err.println("HeadlessSoundDriver: Sound lump not found: " + lumpName);
            return -1;
        }
    }

    @Override
    public int StartSound(int id, int vol, int sep, int pitch, int priority) {
        // Validate sound ID
        if (id < 0 || id >= NUMSFX) {
            return IDLE_HANDLE;
        }

        // Get sound info from static S_sfx array
        sfxinfo_t sfxinfo = S_sfx[id];
        if (sfxinfo == null) {
            return IDLE_HANDLE;
        }

        // Allocate handle
        int handle = nextHandle.getAndIncrement() % MAXHANDLES;
        activeSounds[handle] = true;

        // Capture pending origin (set by AbstractDoomAudio immediately before this call)
        int ox  = pendingOriginX;
        int oy  = pendingOriginY;
        boolean pos = pendingIsPositional;
        // Reset for next sound
        pendingOriginX      = 0;
        pendingOriginY      = 0;
        pendingIsPositional = false;

        // Create and queue sound event (origin included for per-player positional recalc)
        SoundEvent event = new SoundEvent(handle, id, sfxinfo.name, vol, sep, pitch, ox, oy, pos);
        soundEvents.offer(event);

        // Debug logging (can be disabled later)
        if (soundEvents.size() % 10 == 0) {
            System.out.println("HeadlessSoundDriver: " + soundEvents.size() + " events queued");
        }

        return handle;
    }

    @Override
    public void StopSound(int handle) {
        if (handle >= 0 && handle < MAXHANDLES) {
            activeSounds[handle] = false;
            // Note: We don't queue stop events for now - sounds will play to completion
            // This matches DOOM's original behavior for most sounds
        }
    }

    @Override
    public boolean SoundIsPlaying(int handle) {
        if (handle < 0 || handle >= MAXHANDLES) {
            return false;
        }
        return activeSounds[handle];
    }

    @Override
    public void UpdateSoundParams(int handle, int vol, int sep, int pitch) {
        // For headless mode, we don't support real-time parameter updates
        // Sounds play with their initial parameters
        // This is acceptable for most DOOM sounds which are short
    }

    /**
     * Gets and clears pending sound events.
     * Called by DoomGameService to retrieve events for transport to browser.
     *
     * @return Array of sound events (may be empty, never null)
     */
    public SoundEvent[] pollSoundEvents() {
        if (soundEvents.isEmpty()) {
            return new SoundEvent[0];
        }

        // Drain queue into array
        SoundEvent[] events = soundEvents.toArray(new SoundEvent[0]);
        soundEvents.clear();
        return events;
    }

    /**
     * Checks if this driver has pending sound events.
     */
    public boolean hasPendingEvents() {
        return !soundEvents.isEmpty();
    }
}
