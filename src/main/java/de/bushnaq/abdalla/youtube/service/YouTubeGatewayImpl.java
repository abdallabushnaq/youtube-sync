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
import com.google.api.services.youtube.YouTube;
import com.google.api.services.youtube.model.*;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.List;

/**
 * Production implementation of {@link YouTubeGateway} that delegates to the Google
 * {@link YouTube} Data API v3 client.
 */
@Slf4j
public class YouTubeGatewayImpl implements YouTubeGateway {

    private final YouTube youtube;

    /**
     * Constructs a {@code YouTubeGatewayImpl} wrapping the given authenticated client.
     *
     * @param youtube authenticated YouTube Data API client
     */
    public YouTubeGatewayImpl(YouTube youtube) {
        this.youtube = youtube;
    }

    /** {@inheritDoc} */
    @Override
    public boolean playlistExists(String playlistId) throws IOException {
        PlaylistListResponse response = youtube.playlists()
                .list(List.of("id"))
                .setId(List.of(playlistId))
                .execute();
        return response.getItems() != null && !response.getItems().isEmpty();
    }

    /** {@inheritDoc} */
    @Override
    public PlaylistItemListResponse listPlaylistItems(String playlistId, String pageToken)
            throws IOException {
        YouTube.PlaylistItems.List req = youtube.playlistItems()
                .list(List.of("snippet"))
                .setPlaylistId(playlistId)
                .setMaxResults(50L);
        if (pageToken != null) {
            req.setPageToken(pageToken);
        }
        return req.execute();
    }

    /** {@inheritDoc} */
    @Override
    public String insertVideo(VideoSnippet snippet, VideoStatus status,
                              AbstractInputStreamContent mediaContent,
                              UploadProgressListener progressListener) throws IOException {
        Video videoObject = new Video();
        videoObject.setSnippet(snippet);
        videoObject.setStatus(status);

        YouTube.Videos.Insert insert = youtube.videos()
                .insert(List.of("snippet", "status"), videoObject, mediaContent);
        insert.getMediaHttpUploader().setDirectUploadEnabled(false);

        if (progressListener != null) {
            insert.getMediaHttpUploader().setProgressListener(uploader -> {
                switch (uploader.getUploadState()) {
                    case INITIATION_STARTED ->
                            progressListener.onProgress(UploadState.INITIATING, 0);
                    case MEDIA_IN_PROGRESS ->
                            progressListener.onProgress(UploadState.IN_PROGRESS,
                                    (int) Math.round(uploader.getProgress() * 100));
                    case MEDIA_COMPLETE ->
                            progressListener.onProgress(UploadState.COMPLETE, 100);
                    default -> { /* nothing */ }
                }
            });
        }

        Video response = insert.execute();
        return response.getId();
    }

    /** {@inheritDoc} */
    @Override
    public void insertPlaylistItem(String videoId, String playlistId) throws IOException {
        ResourceId resourceId = new ResourceId();
        resourceId.setKind("youtube#video");
        resourceId.setVideoId(videoId);

        PlaylistItemSnippet itemSnippet = new PlaylistItemSnippet();
        itemSnippet.setPlaylistId(playlistId);
        itemSnippet.setResourceId(resourceId);

        PlaylistItem item = new PlaylistItem();
        item.setSnippet(itemSnippet);

        youtube.playlistItems().insert(List.of("snippet"), item).execute();
    }

    /** {@inheritDoc} */
    @Override
    public void deleteVideo(String videoId) throws IOException {
        youtube.videos().delete(videoId).execute();
    }

    /** {@inheritDoc} */
    @Override
    public void deletePlaylistItem(String playlistItemId) throws IOException {
        youtube.playlistItems().delete(playlistItemId).execute();
    }
}

