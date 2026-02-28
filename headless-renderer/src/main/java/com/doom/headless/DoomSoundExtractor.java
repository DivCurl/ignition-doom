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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import w.IWadLoader;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Extracts DOOM sound lumps from WAD and converts them to WAV format.
 *
 * DOOM sounds use the DMX sound format:
 * - 16-byte header (format marker, sample rate, sample count)
 * - 8-bit unsigned PCM data (0-255 range)
 *
 * This class converts DMX to standard WAV format for browser playback.
 *
 * Phase 4.2: Sound Data Extraction
 */
public class DoomSoundExtractor {

    private static final Logger logger = LoggerFactory.getLogger("DOOM.SoundExtractor");

    private final IWadLoader wadLoader;

    /**
     * DMX sound format header structure (16 bytes total)
     */
    private static class DMXHeader {
        int formatMarker;    // Should be 3 for valid DMX
        int sampleRate;      // Samples per second (typically 11025 or 22050)
        int numSamples;      // Number of PCM samples
        int padding;         // Unused

        static DMXHeader parse(byte[] data) {
            DMXHeader header = new DMXHeader();

            // DMX format: little-endian 16-bit values
            header.formatMarker = (data[0] & 0xFF) | ((data[1] & 0xFF) << 8);
            header.sampleRate = (data[2] & 0xFF) | ((data[3] & 0xFF) << 8);
            header.numSamples = (data[4] & 0xFF) | ((data[5] & 0xFF) << 8) |
                               ((data[6] & 0xFF) << 16) | ((data[7] & 0xFF) << 24);

            return header;
        }

        boolean isValid() {
            return formatMarker == 3 && sampleRate > 0 && numSamples > 0;
        }
    }

    public DoomSoundExtractor(IWadLoader wadLoader) {
        this.wadLoader = wadLoader;
    }

    /**
     * Discovers all sound lumps in the WAD by scanning for lumps starting with "DS".
     * This allows dynamic sound loading that works with any WAD file.
     *
     * @return Array of sound names (without "DS" prefix, lowercase)
     */
    public String[] discoverAllSounds() {
        java.util.List<String> soundNames = new java.util.ArrayList<>();

        try {
            int numLumps = wadLoader.NumLumps();
            logger.debug("DoomSoundExtractor: scanning {} lumps for sounds", numLumps);

            for (int i = 0; i < numLumps; i++) {
                try {
                    String lumpName = wadLoader.GetNameForNum(i);

                    // DOOM sound lumps start with "DS" (e.g., DSPISTOL, DSSHOTGN)
                    if (lumpName != null && lumpName.toUpperCase().startsWith("DS") && lumpName.length() > 2) {
                        // Remove "DS" prefix and convert to lowercase
                        String soundName = lumpName.substring(2).toLowerCase();
                        soundNames.add(soundName);
                    }
                } catch (Exception e) {
                    // Skip lumps that can't be read
                    continue;
                }
            }

            logger.debug("DoomSoundExtractor: discovered {} sound lumps in WAD", soundNames.size());

        } catch (Exception e) {
            logger.warn("DoomSoundExtractor: error discovering sounds", e);
        }

        return soundNames.toArray(new String[0]);
    }

    /**
     * Checks if a lump exists in the WAD without triggering exceptions.
     *
     * @param lumpName Name of the lump to check
     * @return true if the lump exists, false otherwise
     */
    public boolean lumpExists(String lumpName) {
        try {
            int lumpNum = wadLoader.GetNumForName(lumpName);
            return lumpNum >= 0;
        } catch (Exception e) {
            // Lump not found - this is normal for shareware WAD
            return false;
        }
    }

    /**
     * Extracts a sound lump and converts it to WAV format.
     *
     * @param lumpName Name of the sound lump (e.g., "DSPISTOL")
     * @return WAV file as byte array, or null if not found
     */
    public byte[] extractSoundAsWAV(String lumpName) {
        // Pre-check if lump exists to avoid exception printing
        if (!lumpExists(lumpName)) {
            return null;
        }

        try {
            // Get lump index - we know it exists now
            int lumpNum = wadLoader.GetNumForName(lumpName);
            if (lumpNum < 0) {
                return null;
            }

            // Read raw lump data
            byte[] lumpData = wadLoader.ReadLump(lumpNum);
            if (lumpData == null || lumpData.length < 16) {
                logger.warn("DoomSoundExtractor: invalid lump data for: {}", lumpName);
                return null;
            }

            // Parse DMX header
            DMXHeader header = DMXHeader.parse(lumpData);
            if (!header.isValid()) {
                logger.warn("DoomSoundExtractor: invalid DMX header for: {}", lumpName);
                return null;
            }

            // Extract PCM data (skip 16-byte header, then read numSamples, skip 16-byte padding at end)
            int pcmStart = 16;
            int pcmLength = Math.min(header.numSamples, lumpData.length - pcmStart - 16);

            if (pcmLength <= 0) {
                logger.warn("DoomSoundExtractor: no PCM data for: {}", lumpName);
                return null;
            }

            // Copy PCM data directly (both DMX and 8-bit WAV use unsigned 8-bit PCM)
            // No conversion needed - 8-bit WAV uses unsigned samples (0-255) just like DMX
            byte[] pcmData = new byte[pcmLength];
            System.arraycopy(lumpData, pcmStart, pcmData, 0, pcmLength);

            // Create WAV file
            return createWAV(pcmData, header.sampleRate);

        } catch (Exception e) {
            // Sound not found - this is normal for shareware WAD (DOOM1.WAD)
            // which doesn't include all sounds from the full game
            return null;
        }
    }

    /**
     * Creates a WAV file from 8-bit unsigned PCM data, upsampled to 16-bit / 44100 Hz.
     *
     * DOOM's DMX audio is 8-bit unsigned PCM at 11025 Hz. Serving it raw to the browser
     * causes Web Audio API to perform its own low-quality resampling (11025 → 44100/48000 Hz),
     * producing aliasing, muffled transients, and harmonic distortion.
     *
     * Since 44100 / 11025 = 4 exactly, we upsample 4× using linear interpolation and
     * convert to signed 16-bit PCM. This gives the browser standard CD-quality format
     * it handles perfectly with no further resampling required.
     *
     * @param pcmData Unsigned 8-bit PCM samples (raw from DMX lump)
     * @param sourceSampleRate Source sample rate in Hz (typically 11025)
     * @return WAV file as byte array (16-bit signed PCM at 44100 Hz)
     */
    private byte[] createWAV(byte[] pcmData, int sourceSampleRate) throws IOException {
        // Upsample to 44100 Hz. 44100 / 11025 = 4 (exact integer ratio).
        // If source rate doesn't divide evenly, round to nearest integer factor.
        final int TARGET_SAMPLE_RATE = 44100;
        int upsampleFactor = Math.max(1, TARGET_SAMPLE_RATE / sourceSampleRate);

        // Convert unsigned 8-bit → signed 16-bit with linear interpolation between samples.
        // Each source sample produces upsampleFactor output samples.
        int upsampledSamples = pcmData.length * upsampleFactor;
        byte[] pcm16 = new byte[upsampledSamples * 2]; // 2 bytes per 16-bit sample
        int outIdx = 0;

        for (int i = 0; i < pcmData.length; i++) {
            // Unsigned 8-bit (0-255) → signed int (-128 to 127)
            int s0 = (pcmData[i] & 0xFF) - 128;
            int s1 = (i + 1 < pcmData.length) ? ((pcmData[i + 1] & 0xFF) - 128) : s0;

            for (int j = 0; j < upsampleFactor; j++) {
                // Linear interpolation between adjacent source samples
                int lerped = s0 + (s1 - s0) * j / upsampleFactor;
                // Scale to full 16-bit range: [-128,127] * 256 → [-32768, 32512]
                short sample16 = (short)(lerped * 256);
                // Write little-endian
                pcm16[outIdx++] = (byte)(sample16 & 0xFF);
                pcm16[outIdx++] = (byte)((sample16 >> 8) & 0xFF);
            }
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int dataLength = pcm16.length;
        int fileSize = 36 + dataLength;

        // RIFF header
        out.write("RIFF".getBytes());
        writeInt32LE(out, fileSize);
        out.write("WAVE".getBytes());

        // fmt chunk — 16-bit mono PCM at 44100 Hz
        out.write("fmt ".getBytes());
        writeInt32LE(out, 16);                      // fmt chunk size
        writeInt16LE(out, 1);                       // PCM format
        writeInt16LE(out, 1);                       // mono
        writeInt32LE(out, TARGET_SAMPLE_RATE);      // 44100 Hz
        writeInt32LE(out, TARGET_SAMPLE_RATE * 2);  // byte rate (44100 * 1 ch * 2 bytes)
        writeInt16LE(out, 2);                       // block align (1 ch * 2 bytes)
        writeInt16LE(out, 16);                      // bits per sample

        // data chunk
        out.write("data".getBytes());
        writeInt32LE(out, dataLength);
        out.write(pcm16);

        return out.toByteArray();
    }

    /**
     * Writes a 32-bit little-endian integer.
     */
    private void writeInt32LE(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
        out.write((value >> 16) & 0xFF);
        out.write((value >> 24) & 0xFF);
    }

    /**
     * Writes a 16-bit little-endian integer.
     */
    private void writeInt16LE(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
    }

    /**
     * Pre-loads and caches all DOOM sound effects.
     * Returns a map of sound names to WAV data.
     *
     * @param soundNames Array of sound names to load (e.g., ["pistol", "shotgn", ...])
     * @return Map of soundName -> WAV bytes
     */
    public java.util.Map<String, byte[]> loadAllSounds(String[] soundNames) {
        java.util.Map<String, byte[]> soundCache = new java.util.HashMap<>();

        logger.debug("DoomSoundExtractor: loading {} sounds", soundNames.length);

        for (int i = 0; i < soundNames.length; i++) {
            String soundName = soundNames[i];
            String lumpName = "DS" + soundName.toUpperCase();

            try {
                byte[] wavData = extractSoundAsWAV(lumpName);
                if (wavData != null) {
                    soundCache.put(soundName.toLowerCase(), wavData);
                }
            } catch (Exception e) {
                logger.warn("DoomSoundExtractor: error processing {}", lumpName, e);
            } catch (Throwable t) {
                logger.warn("DoomSoundExtractor: fatal error processing {}", lumpName, t);
                throw t;
            }
        }

        logger.debug("DoomSoundExtractor: cached {} of {} sounds", soundCache.size(), soundNames.length);
        return soundCache;
    }
}
