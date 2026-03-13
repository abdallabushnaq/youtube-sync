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

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory stub for {@link YouTubeGateway}, used by all {@code SyncService*Test} classes.
 *
 * <ul>
 *   <li>{@link #playlistExists} always returns {@code true}.</li>
 *   <li>{@link #listPlaylistItems} returns {@link #playlistContents} merged with items
 *       synthesised from {@link #remoteVersions}.</li>
 *   <li>{@link #insertVideo} records the call, fires a throttled or instant progress
 *       simulation, and returns a generated video ID.</li>
 *   <li>All mutation methods count calls and record IDs acted upon.</li>
 * </ul>
 */
class StubYouTubeGateway implements YouTubeGateway {

    /**
     * Maps base title → remote version number to simulate videos already on YouTube.
     * The stub synthesises matching {@link PlaylistItem} entries automatically.
     */
    final Map<String, Integer> remoteVersions = new HashMap<>();

    /**
     * Explicit playlist contents. Tests may add items here directly to supply a known
     * video ID for KEEP/DELETE strategy assertions.
     */
    final List<PlaylistItem> playlistContents = new ArrayList<>();

    /** Titles of all videos passed to {@link #insertVideo}. */
    final List<String> uploadedTitles = new ArrayList<>();

    /** IDs of all videos passed to {@link #deleteVideo}. */
    final List<String> deletedVideoIds = new ArrayList<>();

    /** IDs of all playlist items passed to {@link #deletePlaylistItem}. */
    final List<String> deletedPlaylistItemIds = new ArrayList<>();

    /** Number of times {@link #insertVideo} has been called. */
    final AtomicInteger insertVideoCalls = new AtomicInteger();

    /** Number of times {@link #insertPlaylistItem} has been called. */
    final AtomicInteger insertPlaylistItemCalls = new AtomicInteger();

    /** Number of times {@link #deleteVideo} has been called. */
    final AtomicInteger deleteVideoCalls = new AtomicInteger();

    /**
     * Simulated upload bandwidth in bytes per second.
     * Defaults to 20 Mbit/s so tests exercise the throttled IN_PROGRESS callback path
     * without running too slowly.  Override per-test when instant completion is needed.
     */
    long simulatedBytesPerSecond = 20L * 1024 * 1024 / 8;

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
     *
     * @param snippet         video metadata
     * @param status          video privacy status
     * @param mediaContent    the content being "uploaded"
     * @param progressListener callback for upload progress events
     * @return a generated video ID
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

        final int ticks   = 10;
        long bytesPerTick = totalBytes / ticks;
        long msPerTick    = (bytesPerTick * 1000L) / simulatedBytesPerSecond;

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

    /**
     * Builds a fake {@link PlaylistItem} with the given video ID and title.
     *
     * @param videoId the video ID to embed in the resource
     * @param title   the snippet title
     * @return a fully populated {@link PlaylistItem}
     */
    static PlaylistItem makePlaylistItem(String videoId, String title) {
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
}

