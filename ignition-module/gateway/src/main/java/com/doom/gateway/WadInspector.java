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

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteOrder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Reads the lump directory of a WAD file to detect the required base IWAD and the first map.
 *
 * Only the 12-byte header and 16-byte-per-entry lump directory are read; no lump data is parsed.
 * PK3/PK7 files are not inspected (WAD-only).
 *
 * IWAD detection heuristics:
 *   MAPxx lumps  → DOOM2  (default; indistinguishable from PLUTONIA/TNT without MAPINFO parsing)
 *   ExMy  lumps  → DOOM   (Ultimate DOOM; could also be DOOM1 shareware for E1 only)
 */
public class WadInspector {

    public static final class WadInfo {
        /** First map lump name found, e.g. "MAP01" or "E1M1". Null if none found. */
        public final String firstMap;
        /** Suggested IWAD stem, e.g. "DOOM2" or "DOOM". Null if undetermined. */
        public final String iwad;

        WadInfo(String firstMap, String iwad) {
            this.firstMap = firstMap;
            this.iwad     = iwad;
        }
    }

    /**
     * Inspects a WAD file and returns the detected IWAD stem and first map.
     * Returns a WadInfo with null fields if the file is unreadable, not a WAD, or has no maps.
     */
    public static WadInfo inspect(File wadFile) {
        if (wadFile == null || !wadFile.isFile()) return empty();
        String lower = wadFile.getName().toLowerCase();
        if (!lower.endsWith(".wad"))                  return empty(); // PK3/PK7 not yet supported

        try (RandomAccessFile raf = new RandomAccessFile(wadFile, "r")) {
            // Header: 4-byte magic, 4-byte numlumps (LE), 4-byte diroffset (LE)
            byte[] header = new byte[12];
            if (raf.read(header) != 12) return empty();

            String magic = new String(header, 0, 4, StandardCharsets.US_ASCII);
            if (!magic.equals("PWAD") && !magic.equals("IWAD")) return empty();

            ByteBuffer bb = ByteBuffer.wrap(header, 4, 8).order(ByteOrder.LITTLE_ENDIAN);
            int numLumps  = bb.getInt();
            int dirOffset = bb.getInt();

            if (numLumps <= 0 || numLumps > 65536 || dirOffset < 12) return empty();

            raf.seek(dirOffset);

            String firstMap    = null;
            String detectedIwad = null;

            byte[] entry = new byte[16];
            for (int i = 0; i < numLumps; i++) {
                if (raf.read(entry) != 16) break;

                // Lump name: bytes 8-15, ASCII, null-padded
                int nameLen = 0;
                for (int j = 8; j < 16; j++) {
                    if (entry[j] == 0) break;
                    nameLen++;
                }
                String name = new String(entry, 8, nameLen, StandardCharsets.US_ASCII).toUpperCase();

                if (isMap2Name(name)) {         // MAPxx
                    if (firstMap == null) firstMap = name;
                    detectedIwad = "DOOM2";
                    break; // DOOM2 format confirmed — first map found, no need to continue
                } else if (isMap1Name(name)) {  // ExMy
                    if (firstMap == null) firstMap = name;
                    if (detectedIwad == null) detectedIwad = "DOOM";
                    // Keep scanning — confirm it's really episode-based
                }
            }

            return new WadInfo(firstMap, detectedIwad);

        } catch (IOException e) {
            return empty();
        }
    }

    // MAP01 … MAP32 (and beyond, for custom WADs)
    private static boolean isMap2Name(String name) {
        if (name.length() < 4 || !name.startsWith("MAP")) return false;
        for (int i = 3; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return name.length() > 3;
    }

    // E1M1 … E4M9 (and E5+ for mods/Sigil)
    private static boolean isMap1Name(String name) {
        return name.length() == 4
            && name.charAt(0) == 'E'
            && Character.isDigit(name.charAt(1))
            && name.charAt(2) == 'M'
            && Character.isDigit(name.charAt(3));
    }

    private static WadInfo empty() {
        return new WadInfo(null, null);
    }
}
