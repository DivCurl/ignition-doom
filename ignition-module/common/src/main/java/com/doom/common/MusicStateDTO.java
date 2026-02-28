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

/**
 * Data Transfer Object for the current DOOM music playback state.
 *
 * Crosses the ClassLoader boundary between headless-renderer (child CL)
 * and gateway (parent CL). Set by SessionRunner when a track changes;
 * read by DoomSession to serve the MIDI endpoint.
 *
 * Uses drain-and-clear semantics: SessionRunner.pollMusicState() does
 * an AtomicReference.getAndSet(null), so each state snapshot is consumed
 * exactly once.
 */
public class MusicStateDTO {

    /**
     * Standard MIDI format bytes (converted from MUS in SessionRunner).
     * Null if conversion failed or no track is registered.
     * Pre-converted so the gateway never needs to reference engine classes (s.MusReader).
     */
    public final byte[]  midiData;
    public final boolean playing;
    public final boolean looping;
    public final int     volume;

    public MusicStateDTO(byte[] midiData, boolean playing, boolean looping, int volume) {
        this.midiData = midiData;
        this.playing  = playing;
        this.looping  = looping;
        this.volume   = volume;
    }
}
