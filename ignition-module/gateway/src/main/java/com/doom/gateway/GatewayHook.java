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

import com.inductiveautomation.ignition.common.licensing.LicenseState;
import com.inductiveautomation.ignition.gateway.model.AbstractGatewayModuleHook;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;

/**
 * Ignition gateway hook for the DOOM module.
 *
 * A few things that need to stay true here or startup will crash:
 *   - startup() only stores the JAR URL and registers the servlet. No engine code.
 *   - Engine init happens lazily in SessionRunner when a browser hits /play.
 *   - No engine type imports in this file (mochadoom/doom/g/v/s/w/i packages).
 *   - Exceptions in startup() are caught so the module stays up even if SM fails.
 */
public class GatewayHook extends AbstractGatewayModuleHook {

    private static final Logger logger = LoggerFactory.getLogger("DOOM.GatewayHook");

    private static final String MODULE_VERSION = "0.9.8";

    private GatewayContext gatewayContext;
    private static volatile SessionManager sessionManager;  // static for servlet access

    public GatewayHook() {
        super();
    }

    @Override
    public void setup(GatewayContext context) {
        this.gatewayContext = context;
    }

    @Override
    public void startup(LicenseState activationState) {
        logger.info("── Perspective DOOM | Mike Dillmann (DivCurl) | github.com/DivCurl/ignition-doom ──");
        logger.info("DOOM Perspective Module — startup BEGIN");

        // Initialize SessionManager — does NOT start any engine, just stores the JAR URL
        try {
            URL rendererJar = findHeadlessRendererJar();
            logger.info("DOOM: headless-renderer.jar resolved: {}", rendererJar);
            sessionManager = new SessionManager(rendererJar);
            logger.info("DOOM: SessionManager ready");
        } catch (Exception e) {
            // Log but DO NOT rethrow — module loads even if SessionManager fails.
            // Sessions will be unavailable but the gateway stays up.
            // Check /system/doom/health for the error.
            logger.error("DOOM: SessionManager init failed — sessions unavailable. Error: {}", e.getMessage(), e);
        }

        // Register servlets
        try {
            gatewayContext.getWebResourceManager().addServlet("doom", DoomInputServlet.class);
            logger.info("DOOM: Servlet registered at /system/doom/*");
        } catch (Exception e) {
            logger.error("DOOM: Failed to register doom servlet", e);
        }

        try {
            gatewayContext.getWebResourceManager().addServlet("doom-ws", DoomWebSocketServlet.class);
            logger.info("DOOM: WebSocket servlet registered at /system/doom-ws/*");
        } catch (Exception e) {
            logger.error("DOOM: Failed to register WebSocket servlet", e);
        }

        logger.info("DOOM Perspective Module — startup COMPLETE");
    }

    @Override
    public void shutdown() {
        logger.info("DOOM Perspective Module — shutdown BEGIN");

        if (sessionManager != null) {
            try {
                sessionManager.shutdown();
            } catch (Exception e) {
                logger.warn("DOOM: Error shutting down SessionManager", e);
            }
            sessionManager = null;
        }

        try {
            gatewayContext.getWebResourceManager().removeServlet("doom");
        } catch (Exception e) {
            logger.warn("DOOM: Error removing doom servlet", e);
        }

        try {
            gatewayContext.getWebResourceManager().removeServlet("doom-ws");
        } catch (Exception e) {
            logger.warn("DOOM: Error removing WebSocket servlet", e);
        }

        logger.info("DOOM Perspective Module — shutdown COMPLETE");
    }

    @Override
    public boolean isFreeModule() {
        return true;
    }

    /**
     * Returns the SessionManager. Static accessor for servlets.
     * May return null if startup failed or module is not yet started.
     */
    public static SessionManager getSessionManager() {
        return sessionManager;
    }

    // ── Private: JAR discovery ────────────────────────────────────────────────

    /**
     * Locates headless-renderer.jar using three fallback strategies.
     *
     * Strategy 1: Code-source of gateway.jar → sibling headless-renderer.jar
     *   Works in Ignition's jar-cache where all module JARs live side-by-side.
     *
     * Strategy 2: Known Ignition jar-cache path
     *   Hardcoded fallback for the standard container path.
     *
     * Strategy 3: Scan parent URLClassLoader URLs
     *   Works if Ignition uses a URLClassLoader for the module (8.x sometimes does).
     *
     * Throws RuntimeException if all three strategies fail.
     */
    private URL findHeadlessRendererJar() throws Exception {
        // Strategy 1: sibling of gateway.jar in the jar-cache
        try {
            URL gatewayJarUrl = GatewayHook.class.getProtectionDomain()
                .getCodeSource().getLocation();
            logger.debug("DOOM: gateway.jar location: {}", gatewayJarUrl);

            File jarDir = new File(gatewayJarUrl.toURI()).getParentFile();
            logger.debug("DOOM: searching for headless-renderer.jar in: {}", jarDir);

            File candidate = new File(jarDir, "headless-renderer.jar");
            if (candidate.exists()) {
                logger.info("DOOM: [Strategy 1] found headless-renderer.jar at: {}", candidate.getAbsolutePath());
                return candidate.toURI().toURL();
            }
            logger.debug("DOOM: [Strategy 1] not found at {}", candidate.getAbsolutePath());
        } catch (Exception e) {
            logger.warn("DOOM: [Strategy 1] failed: {}", e.getMessage());
        }

        // Strategy 2: hardcoded Ignition jar-cache path
        String cachePath = "/usr/local/bin/ignition/data/jar-cache/com.doom.perspective/";
        File cacheJar = new File(cachePath, "headless-renderer.jar");
        logger.debug("DOOM: [Strategy 2] trying: {}", cacheJar.getAbsolutePath());
        if (cacheJar.exists()) {
            logger.info("DOOM: [Strategy 2] found headless-renderer.jar at: {}", cacheJar.getAbsolutePath());
            return cacheJar.toURI().toURL();
        }
        logger.debug("DOOM: [Strategy 2] not found at {}", cacheJar.getAbsolutePath());

        // Strategy 3: scan ClassLoader URLs
        ClassLoader cl = GatewayHook.class.getClassLoader();
        logger.debug("DOOM: [Strategy 3] scanning ClassLoader: {}", cl.getClass().getName());
        if (cl instanceof URLClassLoader) {
            for (URL url : ((URLClassLoader) cl).getURLs()) {
                logger.debug("DOOM: CL URL: {}", url);
                if (url.getPath().contains("headless-renderer")) {
                    logger.debug("DOOM: [Strategy 3] found via URLClassLoader scan: {}", url);
                    return url;
                }
            }
        } else {
            logger.warn("DOOM: [Strategy 3] ClassLoader is not URLClassLoader ({}), cannot scan URLs",
                cl.getClass().getName());
        }

        throw new RuntimeException(
            "Cannot locate headless-renderer.jar. Tried: sibling of gateway.jar, "
            + cachePath + ", URLClassLoader URL scan. Check module installation.");
    }
}
