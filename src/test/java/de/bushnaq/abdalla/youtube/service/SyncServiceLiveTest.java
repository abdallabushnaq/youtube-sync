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

import de.bushnaq.abdalla.youtube.dto.OldVersionStrategy;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Live-execution tests for {@link SyncService#sync}.
 *
 * <p>All tests run with {@code dryRun = false}: uploads, playlist insertions, and deletions
 * are performed against the in-memory {@link StubYouTubeGateway}.
 */
@Slf4j
@Tag("UnitTest")
class SyncServiceLiveTest extends AbstractSyncServiceTest {

    private static final String PLAYLIST_ID = "PLtest1234567890";

    /**
     * Folder: two new videos (remote v0).
     * Verifies exactly two uploads and two playlist insertions are made.
     *
     * @throws Exception on unexpected failure
     */
    @Test
    void execute_twoNewVideos_uploadsExactlyTwice() throws Exception {
        addVideo(tempDir, "My-Product-Intro", 1, PLAYLIST_ID);
        addVideo(tempDir, "My-Release-Notes", 2, PLAYLIST_ID);

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
     * Folder: single 5 MB video (remote v0), uploaded at the default simulated bandwidth.
     *
     * <p>The stub fires progress callbacks at 10 % intervals so we can verify that IN_PROGRESS
     * events are actually delivered before COMPLETE.
     *
     * @throws Exception on unexpected failure
     */
    @Test
    void execute_upload_progressCallbacksFiredInOrder() throws Exception {
        addLargeVideo(tempDir, "My-Product-Intro", 1, PLAYLIST_ID, 5 * 1024 * 1024);

        SyncService service = newService(stub, false);

        long start   = System.currentTimeMillis();
        service.sync(tempDir);
        long elapsed = System.currentTimeMillis() - start;

        assertEquals(1, stub.insertVideoCalls.get(), "video should be uploaded");

        assertTrue(stub.maxProgressPercentSeen > 0,
                "expected at least one IN_PROGRESS callback before COMPLETE");
        assertEquals(100, stub.lastProgressPercentSeen,
                "last callback must be COMPLETE (100%)");

        log.info("Simulated upload elapsed: {} ms", elapsed);
        assertTrue(elapsed >= 100,
                "upload should take at least 100 ms with throttled simulation");
    }

    /**
     * Folder: 10 new videos, quota budget set to exactly 2 uploads worth of units (3,300).
     *
     * <p>Verifies that only the first 2 uploads execute before the budget is exhausted
     * and the remaining 8 are silently skipped.
     *
     * @throws Exception on unexpected failure
     */
    @Test
    void quota_tenVideos_budgetForTwo_stopsAfterTwo() throws Exception {
        final int costPerUpload = QuotaTracker.COST_VIDEO_INSERT + QuotaTracker.COST_PLAYLIST_ITEM_INSERT;
        final int planCost      = 11; // 1 playlists.list (validatePlaylists) + 10 playlistItems.list (buildPlan)
        final int budget        = planCost + (2 * costPerUpload);

        for (int i = 1; i <= 10; i++) {
            addVideo(tempDir, "My-Video-" + i, 1, PLAYLIST_ID);
        }

        SyncService service = new SyncService(stub, OldVersionStrategy.KEEP,
                new QuotaTracker(budget), false);
        service.sync(tempDir);

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
}

