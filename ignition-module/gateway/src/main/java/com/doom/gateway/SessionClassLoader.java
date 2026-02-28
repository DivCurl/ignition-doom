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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URL;
import java.net.URLClassLoader;

/**
 * Child-first URLClassLoader for DOOM session isolation.
 *
 * Each DOOM browser session gets its own instance of this ClassLoader, whose sole
 * URL is headless-renderer.jar. Because the engine packages are loaded child-first,
 * each session gets its own copy of mochadoom.Engine (with its own static `instance`
 * field), enabling multiple concurrent sessions without singleton collisions.
 *
 * Delegation rules
 * ────────────────
 * Parent-first (standard delegation — types shared across the CL boundary):
 *   com.doom.common.*    — ISessionRunner, FrameCallback, FrameData, DTOs
 *   java.*, javax.*, jakarta.*, sun.*
 *   org.slf4j.*
 *   com.inductiveautomation.*, com.doom.gateway.*, com.doom.perspective.*
 *
 * Child-first (load from headless-renderer.jar before delegating to parent):
 *   mochadoom.*, doom.*, s.*, v.*, w.*, p.*, r.*, i.*, m.*, g.*, n.*
 *   hu.*, am.*, f.*, data.*, rr.*, defines.*, automap.*, utils.*
 *   demo.*, savegame.*
 *   com.doom.headless.*  — SessionRunner, FrameEncoder, DoomSoundExtractor, etc.
 *
 * Why child-first works
 * ─────────────────────
 * The parent CL (Ignition module CL) never loads engine classes directly — gateway.jar
 * has ZERO imports of engine types (verified by removing headless-renderer from compile
 * scope in gateway/pom.xml). So each child CL loads its own fresh engine classes from
 * its private headless-renderer.jar URL. Engine.instance is null for each new child CL.
 *
 * Why com.doom.common.* is parent-first
 * ──────────────────────────────────────
 * ISessionRunner is defined in common.jar on the parent CL. The gateway casts the
 * reflected SessionRunner instance to ISessionRunner. This cast succeeds only if both
 * sides see the SAME ISessionRunner Class object (loaded by the same ClassLoader).
 * By delegating com.doom.common.* to the parent, the child CL reuses the parent's
 * ISessionRunner class → the cast succeeds.
 *
 * Phase 5.2: Session isolation via ClassLoader-per-session.
 */
public class SessionClassLoader extends URLClassLoader {

    private static final Logger logger = LoggerFactory.getLogger("DOOM.ClassLoader");

    public SessionClassLoader(URL[] urls, ClassLoader parent) {
        super(urls, parent);
        logger.debug("SessionClassLoader created with {} URL(s), parent={}",
            urls.length, parent.getClass().getName());
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (mustDelegateToParent(name)) {
            return super.loadClass(name, resolve);
        }

        // Child-first: try our own JAR before delegating up
        synchronized (getClassLoadingLock(name)) {
            // Check if already loaded by this CL
            Class<?> already = findLoadedClass(name);
            if (already != null) return already;

            try {
                Class<?> c = findClass(name);
                if (resolve) resolveClass(c);
                return c;
            } catch (ClassNotFoundException e) {
                // Not in our JAR — fall back to parent (e.g., JDK classes, SLF4J)
                return super.loadClass(name, resolve);
            }
        }
    }

    /**
     * Returns true if the named class must always be loaded by the PARENT ClassLoader.
     * These packages contain types that must be shared across the CL boundary:
     * - com.doom.common.*: ISessionRunner, FrameCallback, FrameData, DTOs
     * - Platform and framework classes
     */
    private boolean mustDelegateToParent(String name) {
        // Shared types — MUST come from parent so CL boundary cast succeeds
        if (name.startsWith("com.doom.common."))           return true;

        // Java platform — always parent
        if (name.startsWith("java."))                      return true;
        if (name.startsWith("javax."))                     return true;
        if (name.startsWith("jakarta."))                   return true;
        if (name.startsWith("sun."))                       return true;
        if (name.startsWith("jdk."))                       return true;

        // Logging — must be shared so log routing works
        if (name.startsWith("org.slf4j."))                 return true;
        if (name.startsWith("ch.qos.logback."))            return true;

        // Ignition SDK classes
        if (name.startsWith("com.inductiveautomation."))   return true;

        // Gateway module classes (not in headless-renderer.jar anyway)
        if (name.startsWith("com.doom.gateway."))          return true;
        if (name.startsWith("com.doom.perspective."))      return true;

        // Everything else (engine packages) → child-first
        return false;
    }
}
