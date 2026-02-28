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

package v.renderers;

import java.awt.Image;
import com.doom.common.FrameCallback;
import com.doom.common.FrameData;

/**
 * Headless renderer for server-side Doom rendering without AWT display.
 *
 * This renderer extends Mocha Doom's SoftwareIndexedVideoRenderer but intercepts
 * the frame output before it's converted to an AWT Image. Instead of displaying
 * frames in a window, it invokes a callback with the raw framebuffer data.
 *
 * IMPORTANT: This class must be in the v.renderers package because
 * SoftwareIndexedVideoRenderer is package-private (not public).
 *
 * The rendering pipeline remains unchanged - BSP traversal, wall/plane/sprite
 * rendering all work identically to normal Mocha Doom. Only the final output
 * stage is modified.
 *
 * Usage:
 * <pre>
 * HeadlessIndexedRenderer renderer = new HeadlessIndexedRenderer(
 *     rendererFactory,
 *     frameData -> {
 *         // Encode to JPEG, Base64, send to Perspective, etc.
 *     }
 * );
 * </pre>
 *
 * @author Phase 1 - Ignition Doom Project
 */
public class HeadlessIndexedRenderer extends SoftwareIndexedVideoRenderer {

    /** Callback invoked when a new frame is rendered */
    private FrameCallback frameCallback;

    /** Flag to track if callback is set */
    private boolean callbackEnabled = false;

    /**
     * Creates a headless renderer with no callback (useful for testing).
     *
     * @param rf Renderer factory with WAD loader
     */
    public HeadlessIndexedRenderer(RendererFactory.WithWadLoader<byte[], byte[]> rf) {
        super(rf);
        this.currentscreen = null;  // Never create AWT Image
    }

    /**
     * Creates a headless renderer with frame callback.
     *
     * @param rf Renderer factory with WAD loader
     * @param frameCallback Callback to receive rendered frames
     */
    public HeadlessIndexedRenderer(
            RendererFactory.WithWadLoader<byte[], byte[]> rf,
            FrameCallback frameCallback) {
        super(rf);
        this.frameCallback = frameCallback;
        this.callbackEnabled = (frameCallback != null);
        this.currentscreen = null;  // Never create AWT Image
    }

    /**
     * Sets or updates the frame callback.
     *
     * @param callback The callback to receive frames (null to disable)
     */
    public void setFrameCallback(FrameCallback callback) {
        this.frameCallback = callback;
        this.callbackEnabled = (callback != null);
    }

    /**
     * Intercepts frame output and triggers callback instead of creating AWT Image.
     *
     * This overrides the base implementation which creates a BufferedImage.
     * Instead, we extract the raw framebuffer byte array and current palette,
     * then invoke the callback.
     *
     * @return null (no AWT Image is created in headless mode)
     */
    @Override
    public Image getScreenImage() {
        if (callbackEnabled) {
            try {
                // Get raw framebuffer from foreground screen
                byte[] framebuffer = screens.get(DoomScreen.FG);

                // Get current palette (affected by gamma and palette index)
                // The palette field is inherited from SoftwareGraphicsSystem
                byte[] currentPalette = this.palette;

                // Create frame data snapshot
                FrameData frameData = new FrameData(
                    framebuffer,
                    currentPalette,
                    width,
                    height
                );

                // Invoke callback
                frameCallback.onFrameRendered(frameData);

            } catch (Exception e) {
                // Log error but don't crash the game
                System.err.println("Error in frame callback: " + e.getMessage());
                e.printStackTrace();
            }
        }

        // Return null instead of BufferedImage
        // This prevents AWT from trying to display anything
        return null;
    }

    /**
     * Override forcePalette to prevent BufferedImage creation.
     *
     * The base SoftwareIndexedVideoRenderer creates a BufferedImage here.
     * In headless mode, we skip this entirely.
     */
    @Override
    public void forcePalette() {
        // Do nothing - we don't create Images in headless mode
        // The palette field is still updated by the parent class
    }

    /**
     * Gets the current framebuffer directly (for testing/debugging).
     *
     * @return Raw indexed framebuffer byte array
     */
    public byte[] getFramebuffer() {
        return screens.get(DoomScreen.FG);
    }

    /**
     * Gets the current palette data directly (for testing/debugging).
     *
     * @return RGB palette byte array (768 bytes)
     */
    public byte[] getPaletteData() {
        return this.palette;
    }

    // Note: getScreenWidth() and getScreenHeight() are inherited from parent
    // (they are final methods in SoftwareGraphicsSystem)
}
