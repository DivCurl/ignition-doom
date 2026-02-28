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

/**
 * Headless music driver for browser-side OPL2 playback.
 *
 * Implements the IMusic interface by capturing MUS lump data and playback
 * state instead of producing local audio. The gateway servlet polls this
 * driver and serves the raw MUS bytes + state to the browser, where a
 * nuked-opl3 emulator performs the actual FM synthesis.
 *
 * Phase 4.3 (restart): Clean implementation — no MIDI conversion.
 * cf. i_oplmusic.c in Chocolate Doom for the OPL register mapping reference.
 */
public class HeadlessMusicDriver implements IMusic {

    // Current track state
    private volatile byte[] currentMusData = null;
    private volatile boolean playing = false;
    private volatile boolean paused  = false;
    private volatile boolean looping = false;
    private volatile int     volume  = 127;

    // Flag set whenever a new track is registered; cleared by the servlet after fetching
    private volatile boolean changed = false;

    // ── IMusic implementation ────────────────────────────────────────────────

    @Override
    public void InitMusic() {
        System.out.println("HeadlessMusicDriver: initialized (nuked-opl3 browser path)");
    }

    @Override
    public void ShutdownMusic() {
        currentMusData = null;
        playing = false;
        paused  = false;
        changed = false;
        System.out.println("HeadlessMusicDriver: shutdown");
    }

    @Override
    public void SetMusicVolume(int vol) {
        this.volume = Math.max(0, Math.min(127, vol));
    }

    /**
     * Registers a MUS lump for playback.
     * Stores the raw MUS bytes so the servlet can serve them to the browser.
     *
     * @param data Raw MUS bytes from the WAD lump
     * @return handle (always 1; not used)
     */
    @Override
    public int RegisterSong(byte[] data) {
        if (data == null || data.length < 8) {
            System.err.println("HeadlessMusicDriver: RegisterSong received invalid data");
            return 0;
        }
        currentMusData = data.clone();
        changed = true;
        System.out.println("HeadlessMusicDriver: RegisterSong — " + data.length + " bytes");
        return 1;
    }

    @Override
    public void PlaySong(int handle, boolean loop) {
        this.looping = loop;
        this.playing = true;
        this.paused  = false;
        System.out.println("HeadlessMusicDriver: PlaySong (looping=" + loop + ")");
    }

    @Override
    public void StopSong(int handle) {
        this.playing = false;
        this.paused  = false;
        System.out.println("HeadlessMusicDriver: StopSong");
    }

    @Override
    public void PauseSong(int handle) {
        this.paused = true;
    }

    @Override
    public void ResumeSong(int handle) {
        this.paused = false;
    }

    @Override
    public void UnRegisterSong(int handle) {
        // Keep currentMusData — the browser may still be playing it.
        // It will be replaced by the next RegisterSong call.
    }

    // ── Polling API for DoomGameService / DoomInputServlet ──────────────────

    /** Raw MUS bytes of the currently registered track, or null if none. */
    public byte[] getCurrentMusData() {
        return currentMusData;
    }

    /** True if music is currently playing and not paused. */
    public boolean isPlaying() {
        return playing && !paused;
    }

    /** True if the current track should loop. */
    public boolean isLooping() {
        return looping;
    }

    /** Current volume (0–127). */
    public int getVolume() {
        return volume;
    }

    /**
     * Returns true if a new track has been registered since the last call to clearChanged().
     * Used by DoomGameService to detect track changes.
     */
    public boolean hasChanged() {
        return changed;
    }

    /** Clears the changed flag after the servlet has fetched the new MUS data. */
    public void clearChanged() {
        changed = false;
    }
}
