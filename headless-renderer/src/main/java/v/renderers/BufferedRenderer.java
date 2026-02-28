/**
 * Copyright (C) 2017 Good Sign
 * Modified 2026 by Mike Dillmann (DivCurl) for Ignition Perspective DOOM.
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
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

 package v.renderers;

import com.doom.common.FrameCallback;
import com.doom.common.FrameData;
import java.awt.Image;
import java.awt.Point;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;

public class BufferedRenderer extends SoftwareIndexedVideoRenderer {

    /**
     * Headless mode support for Ignition Gateway integration.
     * When set, frame rendering will invoke this callback instead of creating AWT images.
     */
    private static final ThreadLocal<FrameCallback> headlessCallback = new ThreadLocal<>();

    /**
     * Sets the headless frame callback for the current thread.
     * When non-null, the renderer will invoke this callback on each frame
     * instead of creating BufferedImages for display.
     *
     * @param callback The callback to receive frame data, or null to disable headless mode
     */
    public static void setHeadlessCallback(FrameCallback callback) {
        headlessCallback.set(callback);
    }

    /**
     * Checks if headless mode is active for the current thread.
     */
    public static boolean isHeadlessMode() {
        return headlessCallback.get() != null;
    }
    private final WritableRaster[] rasters = new WritableRaster[SCREENS_COUNT];

    /**
     * This actually creates a raster with a fixed underlying array, but NOT the images themselves. So it's possible to
     * have "imageless" rasters (unless you specifically request to make them visible, of course).
     */
    BufferedRenderer(RendererFactory.WithWadLoader<byte[], byte[]> rf) {
        super(rf);
        for (DoomScreen s: DoomScreen.values()) {
            final int index = s.ordinal();
            // Only create non-visible data, pegged to the raster. Create visible images only on-demand.
            final DataBufferByte db = (DataBufferByte) newBuffer(s);
            // should be fully compatible with IndexColorModels from SoftwareIndexedVideoRenderer
            rasters[index] = Raster.createInterleavedRaster(db, width, height, width, 1, new int[]{0}, new Point(0, 0));
        }
        // Thou shalt not best nullt!!! Sets currentscreen
        forcePalette();
    }

    /**
     * Clear the screenbuffer so when the whole screen will be recreated palettes will too
     * These screens represent a complete range of palettes for a specific gamma and specific screen
     */
    @Override
    public final void forcePalette() {
        this.currentscreen = new BufferedImage(cmaps[usegamma][usepalette], rasters[DoomScreen.FG.ordinal()], true, null);
    }

    /**
     * Override getScreenImage to support headless mode.
     * In headless mode, invokes the frame callback instead of returning a BufferedImage.
     */
    @Override
    public Image getScreenImage() {
        FrameCallback callback = headlessCallback.get();
        if (callback != null) {
            try {
                byte[] framebuffer = screens.get(DoomScreen.FG);
                byte[] currentPalette = this.palette;

                FrameData frameData = new FrameData(
                    framebuffer,
                    currentPalette,
                    width,
                    height
                );

                callback.onFrameRendered(frameData);

            } catch (Exception e) {
                System.err.println("DOOM: Error in headless frame callback: " + e.getMessage());
            }

            // Return null in headless mode (no AWT display)
            return null;
        }

        // Normal mode: return the BufferedImage
        return super.getScreenImage();
    }
}

//$Log: BufferedRenderer.java,v $
//Revision 1.18  2012/09/24 17:16:23  velktron
//Massive merge between HiColor and HEAD. There's no difference from now on, and development continues on HEAD.
//
//Revision 1.17.2.3  2012/09/24 16:56:06  velktron
//New hierarchy, less code repetition.
//
