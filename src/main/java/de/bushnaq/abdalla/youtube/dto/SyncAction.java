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
package de.bushnaq.abdalla.youtube.dto;

import java.nio.file.Path;

/**
 * Represents the planned action for a single local video file, computed during the
 * <em>plan phase</em> of a sync run before any mutations are made.
 *
 * @param videoFile     absolute path to the local video file
 * @param filename      video filename (no path component), for display
 * @param baseTitle     human-readable title derived from the filename stem (dashes → spaces,
 *                      version suffix stripped); {@code null} when {@link #kind} is
 *                      {@link Kind#ERROR}
 * @param localVersion  version number parsed from the filename; {@code -1} on parse error
 * @param remoteVersion highest version number found on YouTube for this title;
 *                      {@code 0} if the video has never been uploaded; {@code -1} when
 *                      {@link #kind} is {@link Kind#ERROR}
 * @param playlistId    target YouTube playlist ID from the sidecar JSON; may be {@code null}
 * @param kind          what the execute phase will do with this file
 * @param errorMessage  human-readable error detail; non-{@code null} only when
 *                      {@link #kind} is {@link Kind#ERROR}
 */
public record SyncAction(
        Path videoFile,
        String filename,
        String baseTitle,
        int localVersion,
        int remoteVersion,
        String playlistId,
        Kind kind,
        String errorMessage
) {

    /**
     * The intended action for a video file.
     */
    public enum Kind {

        /**
         * The local version is newer than the remote version — upload will be performed.
         * For remote version 0 this is the first-ever upload.
         */
        UPLOAD,

        /**
         * The local version is not newer than the remote version — no upload needed.
         */
        SKIP,

        /**
         * The file cannot be processed (e.g. filename missing version suffix, sidecar parse
         * error).  No upload will be attempted.
         */
        ERROR
    }
}

