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
 * Data Transfer Object containing a rendered Doom frame.
 *
 * Lives in common.jar (parent ClassLoader) so that both the gateway module
 * and headless-renderer (child ClassLoader) share the same Class object.
 *
 * The framebuffer contains palette-indexed pixels (0-255), where each byte
 * is an index into the palette array. The palette contains RGB triplets.
 */
public class FrameData {

    /** Raw framebuffer: palette indices (0-255) */
    public final byte[] framebuffer;

    /** RGB palette: 256 colors × 3 bytes (R, G, B) = 768 bytes */
    public final byte[] palette;

    /** Frame width in pixels */
    public final int width;

    /** Frame height in pixels */
    public final int height;

    /**
     * Creates a new FrameData snapshot.
     *
     * @param framebuffer Indexed pixel data (width * height bytes)
     * @param palette RGB palette data (768 bytes)
     * @param width Frame width
     * @param height Frame height
     */
    public FrameData(byte[] framebuffer, byte[] palette, int width, int height) {
        this.framebuffer = framebuffer;
        this.palette = palette;
        this.width = width;
        this.height = height;
    }

    /**
     * Converts the indexed framebuffer to RGB format.
     *
     * @return RGB byte array (width * height * 3 bytes)
     */
    public byte[] toRGB() {
        byte[] rgb = new byte[width * height * 3];

        for (int i = 0; i < framebuffer.length; i++) {
            int paletteIndex = framebuffer[i] & 0xFF;  // Unsigned byte
            int rgbOffset = i * 3;
            int paletteOffset = paletteIndex * 3;

            rgb[rgbOffset + 0] = palette[paletteOffset + 0];  // R
            rgb[rgbOffset + 1] = palette[paletteOffset + 1];  // G
            rgb[rgbOffset + 2] = palette[paletteOffset + 2];  // B
        }

        return rgb;
    }

    /**
     * Returns the size of the framebuffer in bytes.
     */
    public int getFramebufferSize() {
        return width * height;
    }

    @Override
    public String toString() {
        return String.format("FrameData[%dx%d, %d pixels]", width, height, getFramebufferSize());
    }
}
