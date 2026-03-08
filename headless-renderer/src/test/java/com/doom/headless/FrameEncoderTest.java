package com.doom.headless;

import com.doom.common.FrameData;
import java.awt.image.BufferedImage;
import java.io.IOException;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Unit tests for FrameEncoder.
 *
 * These tests verify the frame encoding pipeline works correctly
 * without requiring a full Mocha Doom initialization.
 *
 * NOTE: HeadlessIndexedRenderer has been moved to v.renderers package
 * to access package-private Mocha Doom classes.
 */
public class FrameEncoderTest {

    /**
     * Creates a test framebuffer with a simple pattern.
     * Generates a 320x200 checkerboard pattern in indexed color.
     */
    private FrameData createTestFrame() {
        int width = 320;
        int height = 200;

        // Create indexed framebuffer
        byte[] framebuffer = new byte[width * height];

        // Simple checkerboard pattern
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int index = y * width + x;
                // Alternate between palette indices
                framebuffer[index] = (byte) (((x / 8) + (y / 8)) % 16);
            }
        }

        // Create a simple test palette (grayscale ramp)
        byte[] palette = new byte[768];  // 256 colors × 3 bytes
        for (int i = 0; i < 256; i++) {
            byte gray = (byte) i;
            palette[i * 3 + 0] = gray;  // R
            palette[i * 3 + 1] = gray;  // G
            palette[i * 3 + 2] = gray;  // B
        }

        return new FrameData(framebuffer, palette, width, height);
    }

    @Test
    public void testFrameDataCreation() {
        FrameData frame = createTestFrame();
        assertNotNull(frame);
        assertEquals(320, frame.width);
        assertEquals(200, frame.height);
        assertEquals(320 * 200, frame.framebuffer.length);
        assertEquals(768, frame.palette.length);
    }

    @Test
    public void testRGBConversion() {
        FrameData frame = createTestFrame();
        byte[] rgb = frame.toRGB();

        assertNotNull(rgb);
        assertEquals(320 * 200 * 3, rgb.length);

        // Verify first pixel (should be palette index 0 → gray 0)
        assertEquals(0, rgb[0]);  // R
        assertEquals(0, rgb[1]);  // G
        assertEquals(0, rgb[2]);  // B
    }

    @Test
    public void testBufferedImageConversion() throws IOException {
        FrameEncoder encoder = new FrameEncoder();
        FrameData frame = createTestFrame();

        BufferedImage image = encoder.toBufferedImage(frame);

        assertNotNull(image);
        assertEquals(320, image.getWidth());
        assertEquals(200, image.getHeight());
        assertEquals(BufferedImage.TYPE_3BYTE_BGR, image.getType());
    }

    @Test
    public void testJPEGEncoding() throws IOException {
        FrameEncoder encoder = new FrameEncoder(0.80f);
        FrameData frame = createTestFrame();

        byte[] jpegBytes = encoder.toJPEG(frame);

        assertNotNull(jpegBytes);
        assertTrue("JPEG should not be empty", jpegBytes.length > 0);
        assertTrue("JPEG should be reasonable size", jpegBytes.length < 100000);

        // Verify JPEG header (FF D8 FF)
        assertEquals((byte) 0xFF, jpegBytes[0]);
        assertEquals((byte) 0xD8, jpegBytes[1]);
        assertEquals((byte) 0xFF, jpegBytes[2]);

        System.out.println("JPEG size: " + jpegBytes.length + " bytes");
    }

    @Test
    public void testPNGEncoding() throws IOException {
        FrameEncoder encoder = new FrameEncoder();
        FrameData frame = createTestFrame();

        byte[] pngBytes = encoder.toPNG(frame);

        assertNotNull(pngBytes);
        assertTrue("PNG should not be empty", pngBytes.length > 0);

        // Verify PNG header
        assertEquals((byte) 0x89, pngBytes[0]);
        assertEquals('P', pngBytes[1]);
        assertEquals('N', pngBytes[2]);
        assertEquals('G', pngBytes[3]);

        System.out.println("PNG size: " + pngBytes.length + " bytes");
    }

    @Test
    public void testDataURIGeneration() throws IOException {
        FrameEncoder encoder = new FrameEncoder();
        FrameData frame = createTestFrame();

        String dataURI = encoder.toDataURI(frame);

        assertNotNull(dataURI);
        assertTrue("Data URI should start with correct prefix",
                   dataURI.startsWith("data:image/jpeg;base64,"));
        assertTrue("Data URI should have base64 content",
                   dataURI.length() > 100);

        System.out.println("Data URI length: " + dataURI.length() + " characters");
    }

    @Test
    public void testQualitySettings() throws IOException {
        FrameData frame = createTestFrame();

        FrameEncoder lowQuality = new FrameEncoder(0.3f);
        FrameEncoder highQuality = new FrameEncoder(0.95f);

        byte[] lowJPEG = lowQuality.toJPEG(frame);
        byte[] highJPEG = highQuality.toJPEG(frame);

        assertTrue("Higher quality should produce larger file",
                   highJPEG.length > lowJPEG.length);

        System.out.println("Low quality (30%): " + lowJPEG.length + " bytes");
        System.out.println("High quality (95%): " + highJPEG.length + " bytes");
    }

    @Test
    public void testEncodingPerformance() {
        FrameEncoder encoder = new FrameEncoder(0.70f);
        FrameData frame = createTestFrame();

        long encodeTime = encoder.measureEncodingTime(frame);

        assertTrue("Encoding should succeed", encodeTime > 0);
        assertTrue("Encoding should be fast (< 500ms)", encodeTime < 500);

        System.out.println("Encoding time: " + encodeTime + " ms");
    }

}
