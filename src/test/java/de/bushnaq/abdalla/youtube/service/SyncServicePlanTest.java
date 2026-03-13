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

import de.bushnaq.abdalla.youtube.dto.SyncAction;
import de.bushnaq.abdalla.youtube.dto.SyncAction.Kind;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Plan-only tests for {@link SyncService#buildPlan}.
 *
 * <p>These tests call {@link SyncService#buildPlan} directly and never invoke
 * {@link SyncService#sync}, so no uploads or deletions can occur.
 */
@Tag("UnitTest")
class SyncServicePlanTest extends AbstractSyncServiceTest {

    private static final String PLAYLIST_ID = "PLtest1234567890";

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
        List<java.nio.file.Path> files = List.of(
                tempDir.resolve("Bad-filename.mp4"),
                tempDir.resolve("My-Product-Intro-1.mp4"),
                tempDir.resolve("My-Release-Notes-2.mp4")
        );
        List<SyncAction> plan = service.buildPlan(files, tempDir);
        service.printPlanTable(plan);

        assertEquals(3, plan.size());

        assertEquals(Kind.ERROR, findAction(plan, "Bad-filename.mp4").kind());

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
        List<java.nio.file.Path> files = List.of(tempDir.resolve("My-Product-Intro-1.mp4"));
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
        List<java.nio.file.Path> files = List.of(tempDir.resolve("My-Product-Intro-1.mp4"));
        List<SyncAction> plan = service.buildPlan(files, tempDir);
        service.printPlanTable(plan);

        assertEquals(Kind.SKIP, plan.get(0).kind());
        assertEquals(5, plan.get(0).remoteVersion());
    }
}

