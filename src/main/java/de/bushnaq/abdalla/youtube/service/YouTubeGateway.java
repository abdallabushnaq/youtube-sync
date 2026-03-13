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
import java.util.List;

/**
 * Thin abstraction over the YouTube Data API v3 operations used by {@link SyncService}.
 *
 * <p>Wrapping the concrete {@link com.google.api.services.youtube.YouTube} client behind this
 * interface serves two purposes:
 * <ol>
 *   <li>It makes {@link SyncService} testable without starting the real YouTube API client
 *       (which cannot be mocked easily on recent JDKs).</li>
 *   <li>It documents exactly which API surface the tool uses.</li>
 * </ol>
 *
 * <p>The production implementation is {@link YouTubeGatewayImpl}.
 */
public interface YouTubeGateway {

    /**
     * Checks whether the given playlist ID exists and is accessible by the authenticated account.
     *
     * @param playlistId the playlist ID to validate
     * @return {@code true} if the playlist exists; {@code false} if not found
     * @throws IOException on API failure
     */
    boolean playlistExists(String playlistId) throws IOException;

    /**
     * Retrieves one page of playlist items from the given playlist.
     *
     * @param playlistId the playlist ID to query
     * @param pageToken  page token for pagination; {@code null} for the first page
     * @return the API response for this page
     * @throws IOException on API failure
     */
    PlaylistItemListResponse listPlaylistItems(String playlistId, String pageToken) throws IOException;

    /**
     * Uploads a video to YouTube and returns the assigned video ID.
     *
     * @param snippet      the video snippet (title, description, tags, categoryId)
     * @param status       the video status (privacyStatus)
     * @param mediaContent the video file content
     * @param progressListener called periodically during upload; may be {@code null}
     * @return the YouTube video ID of the newly uploaded video
     * @throws IOException on upload failure
     */
    String insertVideo(VideoSnippet snippet, VideoStatus status,
                       AbstractInputStreamContent mediaContent,
                       UploadProgressListener progressListener) throws IOException;

    /**
     * Adds a video to a playlist.
     *
     * @param videoId    the YouTube video ID to add
     * @param playlistId the target playlist ID
     * @throws IOException on API failure
     */
    void insertPlaylistItem(String videoId, String playlistId) throws IOException;

    /**
     * Deletes a video from YouTube entirely.
     *
     * @param videoId the YouTube video ID to delete
     * @throws IOException on API failure
     */
    void deleteVideo(String videoId) throws IOException;

    /**
     * Removes one playlist item (identified by its playlist-item ID, not the video ID) from
     * its playlist.
     *
     * @param playlistItemId the playlist-item ID (returned by {@link #listPlaylistItems})
     * @throws IOException on API failure
     */
    void deletePlaylistItem(String playlistItemId) throws IOException;

    // -------------------------------------------------------------------------
    // Nested type
    // -------------------------------------------------------------------------

    /**
     * Callback invoked by {@link #insertVideo} to report upload progress.
     */
    @FunctionalInterface
    interface UploadProgressListener {

        /**
         * Called when the upload state or progress changes.
         *
         * @param state   the current upload state
         * @param percent upload completion percentage (0–100); meaningful only when
         *                {@code state} is {@link UploadState#IN_PROGRESS}
         */
        void onProgress(UploadState state, int percent);
    }

    /**
     * Upload states reported to {@link UploadProgressListener}.
     */
    enum UploadState {
        /** The upload initiation request has started. */
        INITIATING,
        /** Media bytes are being transferred. */
        IN_PROGRESS,
        /** The entire file has been transferred to YouTube. */
        COMPLETE
    }
}

