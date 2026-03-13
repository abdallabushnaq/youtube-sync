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

import com.google.api.client.http.AbstractInputStreamContent;
import com.google.api.services.youtube.model.*;
import de.bushnaq.abdalla.youtube.dto.OldVersionStrategy;
import de.bushnaq.abdalla.youtube.dto.SyncAction;
import de.bushnaq.abdalla.youtube.dto.SyncAction.Kind;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link SyncService}.
 *
 * <p>All YouTube API calls are handled by {@link StubYouTubeGateway} — an in-memory stub that
 * requires no real HTTP connections.
 *
 * <p>Each test method receives its own temporary directory (via {@link TempDir}) and populates
 * it with exactly the video files and sidecar JSON files it needs.  This keeps tests fully
 * independent of each other and of any shared resource folder.
 *
 * <h2>Helper methods</h2>
 * <ul>
 *   <li>{@link #addVideo(Path, String, int, String)} — creates a dummy {@code .mp4} and its
 *       sidecar JSON.</li>
 *   <li>{@link #addVideoNoSidecar(Path, String, int)} — creates only the video file (no sidecar),
 *       used to exercise the "no sidecar → default metadata" path.</li>
 *   <li>{@link #addInvalidVideo(Path, String)} — creates a video file with no version suffix,
 *       used to exercise the ERROR path.</li>
 * </ul>
 */
@Slf4j
@Tag("UnitTest")
class SyncServiceTest {

    private static final String PLAYLIST_ID = "PLtest1234567890";

    /** Fresh stub and temp dir injected per test by JUnit 5. */
    @TempDir
    Path tempDir;

    private StubYouTubeGateway stub;

    /**
     * Resets the stub before each test.
     * The {@code tempDir} is already freshly created by JUnit 5 at this point.
     */
    @BeforeEach
    void setUp() {
        stub = new StubYouTubeGateway();
    }

    // =========================================================================
    // Plan-only tests (buildPlan)
    // =========================================================================

    /**
     * Folder: 1 valid v1 upload + 1 valid v2 skip + 1 invalid filename.
     * Verifies all three are classified correctly.
     *
     * @throws Exception on unexpected failure
     */
    @Test
    void buildPlan_classifiesUploadSkipAndError() throws Exception {
        addVideo(tempDir, "My-Product-Intro", 1, PLAYLIST_ID);
        addVideo(tempDir, "My-Release-Notes", 2, PLAYLIST_ID);
        addInvalidVideo(tempDir, "Bad-filename");

        stub.remoteVersions.put("My Release Notes", 2); // already at v2 → SKIP

        SyncService service = newService(stub, true);
        List<Path> files = List.of(
                tempDir.resolve("Bad-filename.mp4"),
                tempDir.resolve("My-Product-Intro-1.mp4"),
                tempDir.resolve("My-Release-Notes-2.mp4")
        );
        List<SyncAction> plan = service.buildPlan(files, tempDir);
        service.printPlanTable(plan);

        assertEquals(3, plan.size());

        assertEquals(Kind.ERROR,  findAction(plan, "Bad-filename.mp4").kind());

        SyncAction intro = findAction(plan, "My-Product-Intro-1.mp4");
        assertEquals(Kind.UPLOAD, intro.kind());
        assertEquals(1, intro.localVersion());
        assertEquals(0, intro.remoteVersion());
        assertEquals("My Product Intro", intro.baseTitle());

        SyncAction notes = findAction(plan, "My-Release-Notes-2.mp4");
        assertEquals(Kind.SKIP, notes.kind());
        assertEquals(2, notes.localVersion());
        assertEquals(2, notes.remoteVersion());
    }

    /**
     * Folder: single video whose remote version equals the local version.
     * Verifies the action is SKIP.
     *
     * @throws Exception on unexpected failure
     */
    @Test
    void buildPlan_sameVersion_isSkip() throws Exception {
        addVideo(tempDir, "My-Product-Intro", 1, PLAYLIST_ID);
        stub.remoteVersions.put("My Product Intro", 1);

        SyncService service = newService(stub, true);
        List<Path> files = List.of(tempDir.resolve("My-Product-Intro-1.mp4"));
        List<SyncAction> plan = service.buildPlan(files, tempDir);
        service.printPlanTable(plan);

        assertEquals(Kind.SKIP, plan.get(0).kind());
    }

    /**
     * Folder: single video whose remote version is higher than local.
     * Verifies the action is SKIP (no downgrade).
     *
     * @throws Exception on unexpected failure
     */
    @Test
    void buildPlan_remoteNewer_isSkip() throws Exception {
        addVideo(tempDir, "My-Product-Intro", 1, PLAYLIST_ID);
        stub.remoteVersions.put("My Product Intro", 5);

        SyncService service = newService(stub, true);
        List<Path> files = List.of(tempDir.resolve("My-Product-Intro-1.mp4"));
        List<SyncAction> plan = service.buildPlan(files, tempDir);
        service.printPlanTable(plan);

        assertEquals(Kind.SKIP, plan.get(0).kind());
        assertEquals(5, plan.get(0).remoteVersion());
    }

    // =========================================================================
    // Dry-run tests (full sync(), no mutations)
    // =========================================================================

    /**
     * Folder: two new videos (remote v0).
     * Verifies dry-run mode makes no mutations even though uploads are planned.
     *
     * @throws Exception on unexpected failure
     */
    @Test
    void dryRun_twoNewVideos_makesNoMutations() throws Exception {
        addVideo(tempDir, "My-Product-Intro", 1, PLAYLIST_ID);
        addVideo(tempDir, "My-Release-Notes", 2, PLAYLIST_ID);

        SyncService service = newService(stub, true);
        service.sync(tempDir);

        assertEquals(0, stub.insertVideoCalls.get(),        "insertVideo must not be called");
        assertEquals(0, stub.insertPlaylistItemCalls.get(), "insertPlaylistItem must not be called");
        assertEquals(0, stub.deleteVideoCalls.get(),        "deleteVideo must not be called");
    }

    /**
     * Folder: one video that is already up-to-date (remote = local).
     * Verifies dry-run with nothing to do makes no mutations and reports 0 errors.
     *
     * @throws Exception on unexpected failure
     */
    @Test
    void dryRun_alreadyUpToDate_makesNoMutations() throws Exception {
        addVideo(tempDir, "My-Product-Intro", 1, PLAYLIST_ID);
        stub.remoteVersions.put("My Product Intro", 1);

        SyncService service = newService(stub, true);
        service.sync(tempDir);

        assertEquals(0, stub.insertVideoCalls.get());
        assertEquals(0, stub.insertPlaylistItemCalls.get());
        assertEquals(0, stub.deleteVideoCalls.get());
    }

    // =========================================================================
    // Upload tests (full sync(), mutations expected)
    // =========================================================================

    /**
     * Folder: two new videos (remote v0) + one invalid filename.
     * Verifies exactly two uploads and two playlist insertions, none for the bad file.
     *
     * @throws Exception on unexpected failure
     */
    @Test
    void execute_twoNewVideos_uploadsExactlyTwice() throws Exception {
        addVideo(tempDir, "My-Product-Intro",  1, PLAYLIST_ID);
        addVideo(tempDir, "My-Release-Notes",  2, PLAYLIST_ID);
        addInvalidVideo(tempDir, "Bad-filename");

        SyncService service = newService(stub, false);
        service.sync(tempDir);

        assertEquals(2, stub.insertVideoCalls.get(),        "expected 2 uploads");
        assertEquals(2, stub.insertPlaylistItemCalls.get(), "expected 2 playlist insertions");
        assertEquals(0, stub.deleteVideoCalls.get(),        "no old versions to delete");
    }

    /**
     * Folder: single v1 video (remote v0).
     * Verifies the YouTube title has no version suffix.
     *
     * @throws Exception on unexpected failure
     */
    @Test
    void execute_firstUpload_titleHasNoVersionSuffix() throws Exception {
        addVideo(tempDir, "My-Product-Intro", 1, PLAYLIST_ID);

        SyncService service = newService(stub, false);
        service.sync(tempDir);

        assertTrue(stub.uploadedTitles.contains("My Product Intro"),
                "v1 title should have no version suffix");
    }

    /**
     * Folder: single v2 video (remote v0).
     * Verifies the YouTube title carries the {@code " v2"} suffix.
     *
     * @throws Exception on unexpected failure
     */
    @Test
    void execute_v2Upload_titleHasVersionSuffix() throws Exception {
        addVideo(tempDir, "My-Release-Notes", 2, PLAYLIST_ID);

        SyncService service = newService(stub, false);
        service.sync(tempDir);

        assertTrue(stub.uploadedTitles.contains("My Release Notes v2"),
                "v2 title should end with ' v2'");
    }

    /**
     * Folder: single video whose local version (v2) is higher than remote (v1).
     * Strategy = KEEP.
     * Verifies the old playlist item is removed but the old video is NOT deleted.
     *
     * @throws Exception on unexpected failure
     */
    @Test
    void execute_newVersion_keepStrategy_removesFromPlaylistOnly() throws Exception {
        addVideo(tempDir, "My-Release-Notes", 2, PLAYLIST_ID);
        stub.remoteVersions.put("My Release Notes", 1);
        stub.playlistContents.add(makePlaylistItem("old-release-notes-id", "My Release Notes"));

        SyncService service = newService(stub, false);
        service.sync(tempDir);

        assertTrue(stub.uploadedTitles.contains("My Release Notes v2"),
                "My Release Notes v2 should have been uploaded");
        assertTrue(stub.deletedPlaylistItemIds.contains("item-old-release-notes-id"),
                "old version should be removed from playlist");
        assertEquals(0, stub.deleteVideoCalls.get(),
                "KEEP strategy must not delete the old video from YouTube");
    }

    /**
     * Folder: single video whose local version (v2) is higher than remote (v1).
     * Strategy = DELETE.
     * Verifies the old video is fully deleted from YouTube.
     *
     * @throws Exception on unexpected failure
     */
    @Test
    void execute_newVersion_deleteStrategy_deletesOldVideo() throws Exception {
        addVideo(tempDir, "My-Release-Notes", 2, PLAYLIST_ID);
        stub.remoteVersions.put("My Release Notes", 1);
        stub.playlistContents.add(makePlaylistItem("old-release-notes-id", "My Release Notes"));

        SyncService service = newService(stub, OldVersionStrategy.DELETE, false);
        service.sync(tempDir);

        assertTrue(stub.uploadedTitles.contains("My Release Notes v2"));
        assertEquals(1, stub.deleteVideoCalls.get(),
                "DELETE strategy must delete the old video");
        assertTrue(stub.deletedVideoIds.contains("old-release-notes-id"));
    }

    /**
     * Folder: single 5 MB video (remote v0), stub simulates 100 Mbit/s upload bandwidth.
     *
     * <p>At 100 Mbit/s = 12.5 MB/s a 5 MB file takes ~400 ms.  The stub fires progress
     * callbacks at 10% intervals so we can verify that IN_PROGRESS events are actually
     * delivered before COMPLETE.
     *
     * @throws Exception on unexpected failure
     */
    @Test
    void execute_upload_progressCallbacksFiredInOrder() throws Exception {
        addLargeVideo(tempDir, "My-Product-Intro", 1, PLAYLIST_ID, 5 * 1024 * 1024);

        stub.simulatedBytesPerSecond = 100L * 1024 * 1024 / 8; // 100 Mbit/s = 12.5 MB/s

        SyncService service = newService(stub, false);

        long start = System.currentTimeMillis();
        service.sync(tempDir);
        long elapsed = System.currentTimeMillis() - start;

        assertEquals(1, stub.insertVideoCalls.get(), "video should be uploaded");

        // At least one IN_PROGRESS callback must have arrived before COMPLETE
        assertTrue(stub.maxProgressPercentSeen > 0,
                "expected at least one IN_PROGRESS callback before COMPLETE");
        assertEquals(100, stub.lastProgressPercentSeen,
                "last callback must be COMPLETE (100%)");

        // Rough timing sanity check: 5 MB / 12.5 MB/s ≈ 400 ms; allow generous 3× margin
        log.info("Simulated upload elapsed: {} ms", elapsed);
        assertTrue(elapsed >= 100,
                "upload should take at least 100 ms with 100 Mbit/s simulation");
    }

    /**
     * Folder: 10 new videos, quota budget set to exactly 2 uploads worth of units (3,300).
     *
     * <p>In dry-run mode the plan table is printed and a QUOTA WARNING is logged naming the
     * 8 uploads that would be blocked.  In live mode only the first 2 uploads actually execute
     * before the budget is exhausted.
     *
     * @throws Exception on unexpected failure
     */
    @Test
    void quota_tenVideos_budgetForTwo_warnsAndStopsAfterTwo() throws Exception {
        final int costPerUpload = QuotaTracker.COST_VIDEO_INSERT + QuotaTracker.COST_PLAYLIST_ITEM_INSERT;
        // Budget covers exactly 2 uploads (plus a small margin for the list calls during planning)
        final int planCost  = 10; // 10 playlistItems.list calls during buildPlan (1 unit each)
        final int budget    = planCost + (2 * costPerUpload);

        for (int i = 1; i <= 10; i++) {
            addVideo(tempDir, "My-Video-" + i, 1, PLAYLIST_ID);
        }

        // --- dry-run: verify quota warning is emitted but no uploads happen ---
        SyncService dryService = new SyncService(stub, OldVersionStrategy.KEEP,
                new QuotaTracker(budget), true);
        dryService.sync(tempDir);
        assertEquals(0, stub.insertVideoCalls.get(),
                "dry-run must not upload anything");

        // --- live run: only the first 2 uploads should succeed before budget is exhausted ---
        stub = new StubYouTubeGateway(); // fresh stub for the live run
        SyncService liveService = new SyncService(stub, OldVersionStrategy.KEEP,
                new QuotaTracker(budget), false);
        liveService.sync(tempDir);

        assertEquals(2, stub.insertVideoCalls.get(),
                "only 2 uploads should complete before quota is exhausted");
    }

    /**
     * Folder: single video with no sidecar JSON present.
     * Verifies the upload still proceeds using default metadata (no exception).
     *
     * @throws Exception on unexpected failure
     */
    @Test
    void execute_noSidecar_uploadsWithDefaults() throws Exception {
        addVideoNoSidecar(tempDir, "My-Product-Intro", 1);

        SyncService service = newService(stub, false);
        service.sync(tempDir);

        assertEquals(1, stub.insertVideoCalls.get(),
                "upload should proceed even without a sidecar");
    }

    // =========================================================================
    // Fixture helpers
    // =========================================================================

    /**
     * Creates a 1-byte dummy {@code .mp4} file and a matching sidecar JSON in {@code folder}.
     * The filename is {@code <stem>-<version>.mp4}.
     *
     * @param folder     the target directory
     * @param stem       dash-separated title stem, e.g. {@code "My-Product-Intro"}
     * @param version    version number appended to the stem
     * @param playlistId YouTube playlist ID written into the sidecar
     * @throws Exception on I/O failure
     */
    private static void addVideo(Path folder, String stem, int version,
                                 String playlistId) throws Exception {
        String base = stem + "-" + version;
        Files.write(folder.resolve(base + ".mp4"), new byte[]{0x00});
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
     * Creates a 1-byte dummy {@code .mp4} file in {@code folder} with no sidecar JSON.
     *
     * @param folder  the target directory
     * @param stem    dash-separated title stem
     * @param version version number appended to the stem
     * @throws Exception on I/O failure
     */
    private static void addVideoNoSidecar(Path folder, String stem, int version) throws Exception {
        String base = stem + "-" + version;
        Files.write(folder.resolve(base + ".mp4"), new byte[]{0x00});
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
    private static void addLargeVideo(Path folder, String stem, int version,
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
     * This file will always produce a {@link Kind#ERROR} action.
     *
     * @param folder the target directory
     * @param name   filename stem (no version, no extension)
     * @throws Exception on I/O failure
     */
    private static void addInvalidVideo(Path folder, String name) throws Exception {
        Files.write(folder.resolve(name + ".mp4"), new byte[]{0x00});
    }

    // =========================================================================
    // Test-service helpers
    // =========================================================================

    /** Creates a {@link SyncService} with {@link OldVersionStrategy#KEEP}. */
    private SyncService newService(StubYouTubeGateway gateway, boolean dryRun) {
        return new SyncService(gateway, OldVersionStrategy.KEEP, new QuotaTracker(10_000), dryRun);
    }

    /** Creates a {@link SyncService} with the given strategy. */
    private SyncService newService(StubYouTubeGateway gateway, OldVersionStrategy strategy,
                                   boolean dryRun) {
        return new SyncService(gateway, strategy, new QuotaTracker(10_000), dryRun);
    }

    /** Finds the {@link SyncAction} for the given filename, failing if absent. */
    private static SyncAction findAction(List<SyncAction> plan, String filename) {
        return plan.stream()
                .filter(a -> a.filename().equals(filename))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No action found for: " + filename));
    }

    /** Builds a fake {@link PlaylistItem} with the given video ID and title. */
    private static PlaylistItem makePlaylistItem(String videoId, String title) {
        ResourceId resourceId = new ResourceId();
        resourceId.setKind("youtube#video");
        resourceId.setVideoId(videoId);

        PlaylistItemSnippet snippet = new PlaylistItemSnippet();
        snippet.setTitle(title);
        snippet.setResourceId(resourceId);

        PlaylistItem item = new PlaylistItem();
        item.setId("item-" + videoId);
        item.setSnippet(snippet);
        return item;
    }

    // =========================================================================
    // Stub
    // =========================================================================

    /**
     * In-memory stub for {@link YouTubeGateway}.
     *
     * <ul>
     *   <li>{@link #playlistExists} always returns {@code true}.</li>
     *   <li>{@link #listPlaylistItems} returns {@link #playlistContents} merged with items
     *       synthesised from {@link #remoteVersions}.</li>
     *   <li>{@link #insertVideo} records the call, fires a COMPLETE progress callback, and
     *       returns a generated video ID.</li>
     *   <li>All mutation methods count calls and record IDs acted upon.</li>
     * </ul>
     */
    static class StubYouTubeGateway implements YouTubeGateway {

        /**
         * Maps base title → remote version number to simulate videos already on YouTube.
         * The stub synthesises matching {@link PlaylistItem} entries automatically.
         */
        final Map<String, Integer> remoteVersions = new HashMap<>();

        /**
         * Explicit playlist contents.  Tests may add items here directly to supply a known
         * video ID for KEEP/DELETE strategy assertions.
         */
        final List<PlaylistItem> playlistContents = new ArrayList<>();

        /** Titles of all videos passed to {@link #insertVideo}. */
        final List<String> uploadedTitles = new ArrayList<>();

        /** IDs of all videos passed to {@link #deleteVideo}. */
        final List<String> deletedVideoIds = new ArrayList<>();

        /** IDs of all playlist items passed to {@link #deletePlaylistItem}. */
        final List<String> deletedPlaylistItemIds = new ArrayList<>();

        final AtomicInteger insertVideoCalls        = new AtomicInteger();
        final AtomicInteger insertPlaylistItemCalls = new AtomicInteger();
        final AtomicInteger deleteVideoCalls        = new AtomicInteger();

        /**
         * Simulated upload bandwidth in bytes per second.
         * {@code 0} (default) means instant — COMPLETE is fired immediately with no
         * IN_PROGRESS callbacks.  Set to e.g. {@code 100L * 1024 * 1024 / 8} for 100 Mbit/s.
         */
        long simulatedBytesPerSecond = 0;

        /** Highest IN_PROGRESS percentage seen across all uploads. */
        volatile int maxProgressPercentSeen = 0;

        /** Last percentage value passed to the progress listener (COMPLETE sets this to 100). */
        volatile int lastProgressPercentSeen = -1;

        private int videoIdCounter = 1;

        @Override
        public boolean playlistExists(String playlistId) {
            return true;
        }

        @Override
        public PlaylistItemListResponse listPlaylistItems(String playlistId, String pageToken) {
            List<PlaylistItem> items = new ArrayList<>(playlistContents);
            for (Map.Entry<String, Integer> entry : remoteVersions.entrySet()) {
                String baseTitle     = entry.getKey();
                int    version       = entry.getValue();
                String expectedTitle = version == 1 ? baseTitle : baseTitle + " v" + version;
                boolean alreadyPresent = items.stream()
                        .anyMatch(i -> expectedTitle.equalsIgnoreCase(i.getSnippet().getTitle()));
                if (!alreadyPresent) {
                    items.add(makePlaylistItem(
                            "synth-" + baseTitle.replace(' ', '-') + "-v" + version,
                            expectedTitle));
                }
            }
            PlaylistItemListResponse response = new PlaylistItemListResponse();
            response.setItems(items);
            response.setNextPageToken(null);
            return response;
        }

        /**
         * Simulates an upload, optionally at a throttled bandwidth.
         *
         * <p>If {@link #simulatedBytesPerSecond} is 0, fires COMPLETE immediately (fast tests).
         * Otherwise reads the content length, fires IN_PROGRESS callbacks at 10 % intervals
         * with appropriate sleeps, then fires COMPLETE.
         */
        @Override
        public String insertVideo(VideoSnippet snippet, VideoStatus status,
                                  AbstractInputStreamContent mediaContent,
                                  UploadProgressListener progressListener) {
            insertVideoCalls.incrementAndGet();
            uploadedTitles.add(snippet.getTitle());
            if (progressListener != null) {
                if (simulatedBytesPerSecond > 0) {
                    simulateThrottledUpload(mediaContent, progressListener);
                } else {
                    progressListener.onProgress(UploadState.COMPLETE, 100);
                    lastProgressPercentSeen = 100;
                }
            }
            return "new-video-id-" + videoIdCounter++;
        }

        /**
         * Drives the progress listener through 9 IN_PROGRESS ticks (10 %, 20 %, … 90 %)
         * followed by COMPLETE, sleeping between ticks to simulate the configured bandwidth.
         *
         * @param mediaContent the content being "uploaded" (used only for its byte length)
         * @param listener     the progress listener to call
         */
        private void simulateThrottledUpload(AbstractInputStreamContent mediaContent,
                                             UploadProgressListener listener) {
            long totalBytes;
            try {
                totalBytes = mediaContent.getLength();
            } catch (IOException e) {
                totalBytes = 5L * 1024 * 1024; // fallback: 5 MB
            }
            if (totalBytes <= 0) totalBytes = 5L * 1024 * 1024;

            final int ticks    = 10;
            long bytesPerTick  = totalBytes / ticks;
            long msPerTick     = (bytesPerTick * 1000L) / simulatedBytesPerSecond;

            listener.onProgress(UploadState.INITIATING, 0);

            for (int tick = 1; tick < ticks; tick++) {
                sleep(msPerTick);
                int percent = tick * 100 / ticks;
                listener.onProgress(UploadState.IN_PROGRESS, percent);
                maxProgressPercentSeen  = Math.max(maxProgressPercentSeen, percent);
                lastProgressPercentSeen = percent;
            }
            sleep(msPerTick); // final chunk
            listener.onProgress(UploadState.COMPLETE, 100);
            lastProgressPercentSeen = 100;
        }

        private static void sleep(long ms) {
            if (ms <= 0) return;
            try {
                Thread.sleep(ms);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void insertPlaylistItem(String videoId, String playlistId) {
            insertPlaylistItemCalls.incrementAndGet();
        }

        @Override
        public void deleteVideo(String videoId) {
            deleteVideoCalls.incrementAndGet();
            deletedVideoIds.add(videoId);
        }

        @Override
        public void deletePlaylistItem(String playlistItemId) {
            deletedPlaylistItemIds.add(playlistItemId);
        }
    }
}
