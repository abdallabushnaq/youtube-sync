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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Dry-run tests for {@link SyncService#sync}.
 *
 * <p>All tests run with {@code dryRun = true}: the plan is built and displayed but
 * no mutations (uploads, deletions) are performed.
 */
@Tag("UnitTest")
class SyncServiceDryRunTest extends AbstractSyncServiceTest {

    private static final String PLAYLIST_ID = "PLtest1234567890";

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
     * Verifies dry-run with nothing to do makes no mutations.
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

    /**
     * Folder: 10 new videos, quota budget set to exactly 2 uploads worth of units (3,300).
     *
     * <p>Verifies the plan table marks 8 videos as QUOTA-deferred and that no uploads are made.
     *
     * @throws Exception on unexpected failure
     */
    @Test
    void quota_tenVideos_budgetForTwo_planShowsEightDeferred() throws Exception {
        final int costPerUpload = QuotaTracker.COST_VIDEO_INSERT + QuotaTracker.COST_PLAYLIST_ITEM_INSERT;
        final int planCost      = 11; // 1 playlists.list (validatePlaylists) + 10 playlistItems.list (buildPlan)
        final int budget        = planCost + (2 * costPerUpload);

        for (int i = 1; i <= 10; i++) {
            addVideo(tempDir, "My-Video-" + i, 1, PLAYLIST_ID);
        }

        SyncService service = new SyncService(stub, OldVersionStrategy.KEEP,
                new QuotaTracker(budget), true);
        service.sync(tempDir);

        assertEquals(0, stub.insertVideoCalls.get(), "dry-run must not upload anything");
    }
}

