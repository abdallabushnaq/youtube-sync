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
package de.bushnaq.abdalla.youtube.ui;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal REST endpoint that shuts down the JVM when the browser tab is closed.
 *
 * <p>The endpoint is called from a {@code beforeunload} JavaScript handler installed by
 * {@link MainView}.  A short delay before {@link System#exit(int)} allows the HTTP
 * response to be flushed back to the browser before the server disappears.
 *
 * <p>Using GET (rather than POST) avoids CSRF token requirements.  The endpoint is
 * intentionally unauthenticated — this application runs locally on the developer's
 * workstation only.
 */
@RestController
@Slf4j
public class ShutdownController {

    /**
     * Triggers a clean JVM shutdown.
     *
     * <p>The actual {@link System#exit(int)} call is performed on a virtual thread
     * 300 ms after this method returns, giving the servlet container time to flush
     * the HTTP response.
     */
    @GetMapping("/api/shutdown")
    public void shutdown() {
        log.info("Browser tab closed — scheduling JVM shutdown.");
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            log.info("Exiting.");
            System.exit(0);
        });
    }
}

