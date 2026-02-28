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
 * Callback interface for receiving rendered Doom frames.
 *
 * Lives in common.jar (parent ClassLoader) so that both the gateway module
 * and headless-renderer (child ClassLoader) share the same Class object.
 * This is required for the cross-ClassLoader cast to succeed in Phase 5.2.
 */
@FunctionalInterface
public interface FrameCallback {

    /**
     * Called when a new frame has been rendered.
     *
     * @param frameData The rendered frame data (framebuffer, palette, dimensions)
     */
    void onFrameRendered(FrameData frameData);
}
