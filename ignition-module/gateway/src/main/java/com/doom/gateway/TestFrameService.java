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

package com.doom.gateway;

import com.doom.common.FrameData;
import com.doom.headless.FrameEncoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Test frame service that generates animated test frames.
 *
 * This generates simple animated patterns to validate the Perspective
 * integration before we wire up the real DOOM engine.
 *
 * The frames animate at ~15 FPS and show a moving pattern to prove
 * the pipeline is working end-to-end.
 */
public class TestFrameService {

    private static final Logger logger = LoggerFactory.getLogger("DOOM.TestFrameService");

    private static final int WIDTH = 320;
    private static final int HEIGHT = 200;
    private static final int TARGET_FPS = 15;

    private final FrameEncoder encoder;
    private final AtomicReference<String> currentFrameDataURI;
    private final AtomicInteger frameCounter;
    private ScheduledExecutorService executor;
    private volatile boolean running;

    public TestFrameService() {
        this.encoder = new FrameEncoder(0.70f);  // 70% JPEG quality
        this.currentFrameDataURI = new AtomicReference<>("");
        this.frameCounter = new AtomicInteger(0);
    }

    /**
     * Starts the test frame generator.
     */
    public void start() {
        if (running) {
            logger.warn("TestFrameService already running");
            return;
        }

        running = true;
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "DOOM-TestFrameGenerator");
            t.setDaemon(true);
            return t;
        });

        // Generate frames at target FPS
        long periodMs = 1000 / TARGET_FPS;
        executor.scheduleAtFixedRate(this::generateFrame, 0, periodMs, TimeUnit.MILLISECONDS);

        logger.info("TestFrameService started ({}fps, {}x{})", TARGET_FPS, WIDTH, HEIGHT);
    }

    /**
     * Stops the test frame generator.
     */
    public void stop() {
        if (!running) {
            return;
        }

        running = false;
        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        logger.info("TestFrameService stopped");
    }

    /**
     * Generates a new test frame with animated pattern.
     */
    private void generateFrame() {
        try {
            int frame = frameCounter.getAndIncrement();

            // Create indexed framebuffer with animated pattern
            byte[] framebuffer = new byte[WIDTH * HEIGHT];

            // Animated diagonal stripes that move
            int offset = frame % 32;
            for (int y = 0; y < HEIGHT; y++) {
                for (int x = 0; x < WIDTH; x++) {
                    int index = y * WIDTH + x;
                    // Create moving diagonal pattern
                    int value = ((x + y + offset) / 8) % 16;
                    framebuffer[index] = (byte) value;
                }
            }

            // Create a colorful palette (not just grayscale)
            byte[] palette = new byte[768];
            for (int i = 0; i < 256; i++) {
                // Create a simple color gradient
                palette[i * 3 + 0] = (byte) ((i * 3) % 256);      // R
                palette[i * 3 + 1] = (byte) ((i * 5) % 256);      // G
                palette[i * 3 + 2] = (byte) ((i * 7) % 256);      // B
            }

            // Create frame data and encode
            FrameData frameData = new FrameData(framebuffer, palette, WIDTH, HEIGHT);
            String dataURI = encoder.toDataURI(frameData);

            // Update current frame
            currentFrameDataURI.set(dataURI);

            // Also store in system property for easy script access
            // This is a simple workaround for classloader issues in Phase 1
            System.setProperty("doom.currentFrame", dataURI);
            System.setProperty("doom.frameNumber", String.valueOf(frame));

            // Log every 5 seconds
            if (frame % (TARGET_FPS * 5) == 0) {
                logger.debug("Generated frame #{}, size: {} bytes",
                    frame, dataURI.length());
            }

        } catch (Exception e) {
            logger.error("Error generating test frame", e);
        }
    }

    /**
     * Gets the current frame as a data URI for Perspective Image component.
     *
     * @return Data URI string (e.g., "data:image/jpeg;base64,...")
     */
    public String getCurrentFrame() {
        return currentFrameDataURI.get();
    }

    /**
     * Gets the current frame number.
     */
    public int getFrameNumber() {
        return frameCounter.get();
    }

    /**
     * Gets the target FPS.
     */
    public int getTargetFPS() {
        return TARGET_FPS;
    }

    /**
     * Checks if the service is running.
     */
    public boolean isRunning() {
        return running;
    }
}
