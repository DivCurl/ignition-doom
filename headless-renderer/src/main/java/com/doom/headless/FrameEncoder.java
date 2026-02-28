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

package com.doom.headless;

import com.doom.common.FrameData;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

/**
 * Encodes Doom framebuffer data to various output formats.
 *
 * This class handles the conversion pipeline:
 * 1. Indexed pixels (byte[]) + Palette (byte[]) → RGB pixels
 * 2. RGB pixels → BufferedImage
 * 3. BufferedImage → JPEG/PNG bytes
 * 4. JPEG bytes → Base64 string (data URI)
 *
 * The encoder is designed for performance with reusable buffers and
 * configurable quality settings.
 *
 * @author Phase 1 - Ignition Doom Project
 */
public class FrameEncoder {

    /** JPEG quality (0.0 - 1.0, higher = better quality but larger file) */
    private float jpegQuality = 0.70f;

    /** Reusable BufferedImage to reduce GC pressure */
    private BufferedImage rgbImage;

    /** Image width (set on first encode) */
    private int width = -1;

    /** Image height (set on first encode) */
    private int height = -1;

    /** Cached JPEG writer — initialized once, reused every frame to avoid per-frame service lookup. */
    private ImageWriter jpegWriter;

    /** Cached write params — tied to jpegWriter. */
    private ImageWriteParam writeParam;

    /** Reusable output buffer — reset each frame to avoid repeated allocation. */
    private final ByteArrayOutputStream baos = new ByteArrayOutputStream(64 * 1024);

    /**
     * Creates a frame encoder with default JPEG quality (70%).
     */
    public FrameEncoder() {
        // Default quality is fine for most use cases
    }

    /**
     * Creates a frame encoder with custom JPEG quality.
     *
     * @param jpegQuality Quality from 0.0 (worst) to 1.0 (best)
     */
    public FrameEncoder(float jpegQuality) {
        setJpegQuality(jpegQuality);
    }

    /**
     * Sets JPEG encoding quality.
     *
     * @param quality Quality from 0.0 (worst) to 1.0 (best)
     */
    public void setJpegQuality(float quality) {
        if (quality < 0.0f || quality > 1.0f) {
            throw new IllegalArgumentException("JPEG quality must be between 0.0 and 1.0");
        }
        this.jpegQuality = quality;
        this.jpegWriter  = null;  // force re-init with new quality on next encode
        this.writeParam  = null;
    }

    /**
     * Gets current JPEG quality setting.
     */
    public float getJpegQuality() {
        return jpegQuality;
    }

    /**
     * Converts indexed framebuffer to RGB BufferedImage.
     *
     * This applies the palette to convert 8-bit indexed pixels to 24-bit RGB.
     * The BufferedImage is reused across calls to reduce GC pressure.
     *
     * @param frameData Frame data with indexed pixels and palette
     * @return BufferedImage in RGB format
     */
    public BufferedImage toBufferedImage(FrameData frameData) {
        // Initialize or recreate image if dimensions changed
        if (rgbImage == null ||
            width != frameData.width ||
            height != frameData.height) {

            width = frameData.width;
            height = frameData.height;
            rgbImage = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
        }

        // Get direct access to image pixel data
        byte[] rgbPixels = ((DataBufferByte) rgbImage.getRaster().getDataBuffer()).getData();

        // Apply palette: convert indexed to RGB
        byte[] framebuffer = frameData.framebuffer;
        byte[] palette = frameData.palette;

        for (int i = 0; i < framebuffer.length; i++) {
            int paletteIndex = framebuffer[i] & 0xFF;  // Unsigned byte
            int rgbOffset = i * 3;
            int paletteOffset = paletteIndex * 3;

            // BGR order for TYPE_3BYTE_BGR
            rgbPixels[rgbOffset + 0] = palette[paletteOffset + 2];  // B
            rgbPixels[rgbOffset + 1] = palette[paletteOffset + 1];  // G
            rgbPixels[rgbOffset + 2] = palette[paletteOffset + 0];  // R
        }

        return rgbImage;
    }

    /**
     * Encodes frame data to JPEG bytes.
     *
     * @param frameData Frame data to encode
     * @return JPEG-encoded image bytes
     * @throws IOException if encoding fails
     */
    public byte[] toJPEG(FrameData frameData) throws IOException {
        BufferedImage image = toBufferedImage(frameData);
        return toJPEG(image);
    }

    /**
     * Encodes BufferedImage to JPEG bytes with quality control.
     *
     * @param image Image to encode
     * @return JPEG-encoded bytes
     * @throws IOException if encoding fails
     */
    public byte[] toJPEG(BufferedImage image) throws IOException {
        // Lazy-init: create the writer once and reuse it every frame.
        if (jpegWriter == null) {
            jpegWriter = ImageIO.getImageWritersByFormatName("jpeg").next();
            writeParam = jpegWriter.getDefaultWriteParam();
            writeParam.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            writeParam.setCompressionQuality(jpegQuality);
        }

        baos.reset();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(baos)) {
            jpegWriter.setOutput(ios);
            jpegWriter.write(null, new javax.imageio.IIOImage(image, null, null), writeParam);
        }

        return baos.toByteArray();
    }

    /**
     * Encodes frame data to PNG bytes (lossless).
     *
     * PNG is slower and larger than JPEG but lossless. Good for menus,
     * intermission screens, or debugging.
     *
     * @param frameData Frame data to encode
     * @return PNG-encoded image bytes
     * @throws IOException if encoding fails
     */
    public byte[] toPNG(FrameData frameData) throws IOException {
        BufferedImage image = toBufferedImage(frameData);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, "png", baos);
        return baos.toByteArray();
    }

    /**
     * Encodes frame data to Base64 data URI for Perspective Image component.
     *
     * Returns a complete data URI string that can be directly assigned to
     * an Image component's source property.
     *
     * Format: "data:image/jpeg;base64,/9j/4AAQSkZJRgABAQAA..."
     *
     * @param frameData Frame data to encode
     * @return Base64-encoded data URI string
     * @throws IOException if encoding fails
     */
    public String toDataURI(FrameData frameData) throws IOException {
        byte[] jpegBytes = toJPEG(frameData);
        String base64 = Base64.getEncoder().encodeToString(jpegBytes);
        return "data:image/jpeg;base64," + base64;
    }

    /**
     * Encodes frame data to PNG data URI.
     *
     * @param frameData Frame data to encode
     * @return Base64-encoded PNG data URI string
     * @throws IOException if encoding fails
     */
    public String toPNGDataURI(FrameData frameData) throws IOException {
        byte[] pngBytes = toPNG(frameData);
        String base64 = Base64.getEncoder().encodeToString(pngBytes);
        return "data:image/png;base64," + base64;
    }

    /**
     * Estimates the size of encoded JPEG for given frame dimensions.
     *
     * This is a rough estimate based on typical compression ratios.
     * Actual size varies by frame content complexity.
     *
     * @param width Frame width
     * @param height Frame height
     * @param quality JPEG quality (0.0-1.0)
     * @return Estimated JPEG size in bytes
     */
    public static int estimateJPEGSize(int width, int height, float quality) {
        int pixels = width * height;
        // Rough estimate: 0.5-2 bits per pixel depending on quality
        double bitsPerPixel = 0.5 + (quality * 1.5);
        return (int) (pixels * bitsPerPixel / 8);
    }

    /**
     * Performance test helper: measures encoding time.
     *
     * @param frameData Frame data to encode
     * @return Encoding time in milliseconds
     */
    public long measureEncodingTime(FrameData frameData) {
        long start = System.nanoTime();
        try {
            toDataURI(frameData);
        } catch (IOException e) {
            return -1;
        }
        long end = System.nanoTime();
        return (end - start) / 1_000_000;  // Convert to milliseconds
    }
}
