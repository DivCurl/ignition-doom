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
 * Data Transfer Object for a DOOM sound effect event.
 *
 * Crosses the ClassLoader boundary between headless-renderer (child CL)
 * and gateway (parent CL). All fields are primitive or String — bootstrap
 * types visible identically from both ClassLoaders.
 */
public class SoundEventDTO {

    public final String sfxName;
    public final int    volume;
    public final int    separation;
    public final int    pitch;

    public SoundEventDTO(String sfxName, int volume, int separation, int pitch) {
        this.sfxName    = sfxName;
        this.volume     = volume;
        this.separation = separation;
        this.pitch      = pitch;
    }
}
