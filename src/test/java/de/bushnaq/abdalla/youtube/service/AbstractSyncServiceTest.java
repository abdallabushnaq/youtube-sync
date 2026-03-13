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

import com.google.api.services.youtube.model.PlaylistItem;
import de.bushnaq.abdalla.youtube.dto.OldVersionStrategy;
import de.bushnaq.abdalla.youtube.dto.SyncAction;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Abstract base for all {@code SyncService*Test} classes.
 *
 * <p>Provides a fresh {@link StubYouTubeGateway} and a temporary directory for each test,
 * plus shared fixture helpers and factory methods so subclasses stay focused on behaviour.
 */
@Slf4j
abstract class AbstractSyncServiceTest {

    /** Temporary directory provided fresh for each test by JUnit 5. */
    @TempDir
    Path tempDir;

    /** Fresh stub reset before each test. */
    StubYouTubeGateway stub;

    /**
     * Resets the stub before each test.
     * The {@code tempDir} is already freshly created by JUnit 5 at this point.
     */
    @BeforeEach
    void setUp() {
        stub = new StubYouTubeGateway();
    }

    // =========================================================================
    // Service factories
    // =========================================================================

    /**
     * Creates a {@link SyncService} with {@link OldVersionStrategy#KEEP} and a 10 000-unit
     * quota budget.
     *
     * @param gateway the gateway stub to use
     * @param dryRun  {@code true} for dry-run mode
     * @return a configured {@link SyncService}
     */
    SyncService newService(StubYouTubeGateway gateway, boolean dryRun) {
        return new SyncService(gateway, OldVersionStrategy.KEEP, new QuotaTracker(10_000), dryRun);
    }

    /**
     * Creates a {@link SyncService} with the given {@link OldVersionStrategy} and a 10 000-unit
     * quota budget.
     *
     * @param gateway  the gateway stub to use
     * @param strategy the old-version handling strategy
     * @param dryRun   {@code true} for dry-run mode
     * @return a configured {@link SyncService}
     */
    SyncService newService(StubYouTubeGateway gateway, OldVersionStrategy strategy,
                           boolean dryRun) {
        return new SyncService(gateway, strategy, new QuotaTracker(10_000), dryRun);
    }

    // =========================================================================
    // Fixture helpers
    // =========================================================================

    /**
     * Creates a 5 MB dummy {@code .mp4} file and a matching sidecar JSON in {@code folder}.
     * The filename is {@code <stem>-<version>.mp4}.
     *
     * @param folder     the target directory
     * @param stem       dash-separated title stem, e.g. {@code "My-Product-Intro"}
     * @param version    version number appended to the stem
     * @param playlistId YouTube playlist ID written into the sidecar
     * @throws Exception on I/O failure
     */
    static void addVideo(Path folder, String stem, int version,
                         String playlistId) throws Exception {
        String base = stem + "-" + version;
        Files.write(folder.resolve(base + ".mp4"), new byte[5 * 1024 * 1024]);
        String sidecar = """
                {
                  "description": "Test description for %s.",
                  "tags": ["test"],
                  "categoryId": "28",
                  "privacyStatus": "private",
                  "playlistId": "%s"
                }
                """.formatted(base, playlistId);
        Files.writeString(folder.resolve(base + ".json"), sidecar);
    }

    /**
     * Creates a 5 MB dummy {@code .mp4} file in {@code folder} with no sidecar JSON.
     *
     * @param folder  the target directory
     * @param stem    dash-separated title stem
     * @param version version number appended to the stem
     * @throws Exception on I/O failure
     */
    static void addVideoNoSidecar(Path folder, String stem, int version) throws Exception {
        String base = stem + "-" + version;
        Files.write(folder.resolve(base + ".mp4"), new byte[5 * 1024 * 1024]);
    }

    /**
     * Creates a video file of the given size and a matching sidecar JSON in {@code folder}.
     *
     * @param folder     the target directory
     * @param stem       dash-separated title stem
     * @param version    version number appended to the stem
     * @param playlistId YouTube playlist ID written into the sidecar
     * @param sizeBytes  exact file size in bytes
     * @throws Exception on I/O failure
     */
    static void addLargeVideo(Path folder, String stem, int version,
                               String playlistId, int sizeBytes) throws Exception {
        String base = stem + "-" + version;
        Files.write(folder.resolve(base + ".mp4"), new byte[sizeBytes]);
        String sidecar = """
                {
                  "description": "Large test video %s.",
                  "tags": ["test"],
                  "categoryId": "28",
                  "privacyStatus": "private",
                  "playlistId": "%s"
                }
                """.formatted(base, playlistId);
        Files.writeString(folder.resolve(base + ".json"), sidecar);
    }

    /**
     * Creates a 1-byte dummy {@code .mp4} file whose name has no version suffix.
     * This file will always produce a {@link SyncAction.Kind#ERROR} action.
     *
     * @param folder the target directory
     * @param name   filename stem (no version, no extension)
     * @throws Exception on I/O failure
     */
    static void addInvalidVideo(Path folder, String name) throws Exception {
        Files.write(folder.resolve(name + ".mp4"), new byte[]{0x00});
    }

    // =========================================================================
    // Assertion helpers
    // =========================================================================

    /**
     * Finds the {@link SyncAction} for the given filename, failing if absent.
     *
     * @param plan     the list of planned actions
     * @param filename the exact filename to search for
     * @return the matching action
     * @throws AssertionError if no action is found for the filename
     */
    static SyncAction findAction(List<SyncAction> plan, String filename) {
        return plan.stream()
                .filter(a -> a.filename().equals(filename))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No action found for: " + filename));
    }

    /**
     * Delegates to {@link StubYouTubeGateway#makePlaylistItem(String, String)}.
     *
     * @param videoId the video ID to embed in the resource
     * @param title   the snippet title
     * @return a fully populated {@link PlaylistItem}
     */
    static PlaylistItem makePlaylistItem(String videoId, String title) {
        return StubYouTubeGateway.makePlaylistItem(videoId, title);
    }
}

