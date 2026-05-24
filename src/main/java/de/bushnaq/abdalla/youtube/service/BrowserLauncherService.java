/*
 * Copyright (C) 2025-2026 Abdalla Bushnaq
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.bushnaq.abdalla.youtube.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Opens the application URL in a browser once Spring Boot has finished starting.
 *
 * <p>Vaadin's built-in {@code vaadin.launch-browser} mechanism delegates to
 * {@code java.awt.Desktop.browse()}, which is blocked in a headless JVM (Spring Boot sets
 * {@code java.awt.headless=true} by default).  This component works around that by
 * launching the browser via {@link ProcessBuilder}.
 *
 * <p>On Windows it tries, in order:
 * <ol>
 *   <li>Google Chrome with {@code --app} flag — strips browser toolbar; the window looks
 *       and feels like a native desktop application.</li>
 *   <li>Microsoft Edge with {@code --app} flag — same experience as Chrome.</li>
 *   <li>{@code cmd /c start &lt;url&gt;} — opens the OS default browser without any
 *       special flags; no size control.</li>
 * </ol>
 *
 * <p>On non-Windows systems (Linux, macOS) the service falls back to
 * {@code xdg-open} / {@code open} respectively, without app-mode flags.
 */
@Component
@Slf4j
public class BrowserLauncherService {

    /** Width of the browser app window in pixels. */
    private static final int WINDOW_WIDTH  = 1000;
    /** Height of the browser app window in pixels. */
    private static final int WINDOW_HEIGHT = 760;

    /** The HTTP port on which the embedded Tomcat is listening. */
    @Value("${server.port:8080}")
    private int port;

    /**
     * Fires once Spring Boot is fully started.  Opens the application URL in the best
     * available browser.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        String url = "http://localhost:" + port;
        log.info("Opening browser at {}", url);
        String os = System.getProperty("os.name", "").toLowerCase();
        try {
            if (os.contains("win")) {
                launchWindows(url);
            } else if (os.contains("mac")) {
                new ProcessBuilder("open", url).start();
            } else {
                new ProcessBuilder("xdg-open", url).start();
            }
        } catch (Exception ex) {
            log.warn("Could not open browser automatically: {}", ex.getMessage());
        }
    }

    /**
     * Attempts to open a browser on Windows, preferring an app-mode Chromium window.
     *
     * @param url the URL to open
     * @throws Exception if all launch attempts throw
     */
    private void launchWindows(String url) throws Exception {
        String windowSize = "--window-size=" + WINDOW_WIDTH + "," + WINDOW_HEIGHT;

        // 1 — Chrome app window
        if (tryLaunch("chrome", "--app=" + url, windowSize)) {
            return;
        }
        // 2 — Edge app window
        if (tryLaunch("msedge", "--app=" + url, windowSize)) {
            return;
        }
        // 3 — Shell open (default browser, no app-mode)
        log.info("Neither Chrome nor Edge found; falling back to default browser.");
        new ProcessBuilder("cmd", "/c", "start", url).start();
    }

    /**
     * Tries to start a browser process with the given executable and arguments.
     * Returns {@code true} if the process started without error, {@code false} if the
     * executable was not found.
     *
     * @param executable the browser executable name (looked up on {@code PATH})
     * @param args       additional command-line arguments forwarded to the browser
     * @return {@code true} if the process started successfully
     */
    private boolean tryLaunch(String executable, String... args) {
        try {
            String[] command = new String[1 + args.length];
            command[0] = executable;
            System.arraycopy(args, 0, command, 1, args.length);
            new ProcessBuilder(command).start();
            log.debug("Launched browser via '{}'", executable);
            return true;
        } catch (Exception ex) {
            log.debug("'{}' not available: {}", executable, ex.getMessage());
            return false;
        }
    }
}



