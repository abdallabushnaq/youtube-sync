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

import com.google.api.services.youtube.YouTube;
import de.bushnaq.abdalla.youtube.dto.OldVersionStrategy;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Real end-to-end dry-run integration test.
 *
 * <h2>Purpose</h2>
 * <p>Exercises the full stack — OAuth authentication, YouTube API calls, plan building and
 * plan display — against a live Google account without mutating anything.
 *
 * <h2>How to run</h2>
 * <pre>
 * # Default — uses the video/ folder in the project root:
 * mvn test -Dtest=SyncServiceIntegrationTest
 *
 * # Override — point at any other folder:
 * mvn test -Dtest=SyncServiceIntegrationTest -Dyoutube.sync.folder="E:\Videos\kassandra"
 * </pre>
 * <p>The folder must contain {@code client_secret.json} and at least one video file with a
 * matching sidecar {@code .json}.  OAuth tokens are cached in {@code <folder>/oauth-tokens/}
 * after the first browser-based consent.
 *
 * <h2>CI behaviour</h2>
 * <p>When {@code youtube.sync.folder} is not set the test is silently skipped via
 * {@link Assumptions#assumeTrue}, so it never blocks the regular build.
 */
@Slf4j
@Tag("IntegrationTest")
class SyncServiceIntegrationTest {

    /**
     * Runs a full dry-run sync against the real YouTube API.
     *
     * <p>Resolves the video folder in this order:
     * <ol>
     *   <li>System property {@code youtube.sync.folder} (explicit override)</li>
     *   <li>{@code video/} in the project root (default — always present in this repo)</li>
     * </ol>
     * Skipped automatically if neither path points to an existing directory.
     *
     * @throws Exception if authentication or API communication fails unexpectedly
     */
    @Test
    void realDryRun() throws Exception {
        // Resolve the video folder: explicit system property, or fall back to video/ in the
        // project root (which is the working directory when running via Maven or IntelliJ).
        String folderProp = System.getProperty("youtube.sync.folder");
        Path   folder     = (folderProp != null) ? Path.of(folderProp) : Path.of("video");

        Assumptions.assumeTrue(Files.isDirectory(folder),
                "Video folder not found: '" + folder.toAbsolutePath()
                        + "' — create video/ in the project root or pass -Dyoutube.sync.folder=<path>");

        log.info("=== Real dry-run integration test ===");
        log.info("Folder: {}", folder.toAbsolutePath());

        YouTubeClientFactory factory = new YouTubeClientFactory();
        YouTube              youtube = factory.build(folder);

        YouTubeGateway gateway      = new YouTubeGatewayImpl(youtube);
        QuotaTracker   quotaTracker = new QuotaTracker(10_000);

        // dryRun = true: plan + display only, zero mutations
        SyncService syncService = new SyncService(gateway, OldVersionStrategy.KEEP, quotaTracker, true);
        syncService.sync(folder);

        log.info("=== Real dry-run completed successfully ===");
    }
}
