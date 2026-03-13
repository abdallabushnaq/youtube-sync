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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.util.List;

/**
 * Metadata for a single YouTube video, deserialised from a {@code <videofile>.json} sidecar file.
 *
 * <p>The video title is <strong>not</strong> stored here — it is derived automatically from the
 * video filename: the mandatory {@code -<n>} version suffix is stripped and every {@code -} is
 * replaced with a space.  For example, {@code My-Product-Intro-v2.mp4} yields the base title
 * {@code "My Product Intro"}, which is uploaded as {@code "My Product Intro v2"}.
 *
 * <p>Example sidecar JSON:
 * <pre>{@code
 * {
 *   "description":   "Full description here.",
 *   "tags":          ["tutorial", "demo"],
 *   "categoryId":    "28",
 *   "privacyStatus": "public",
 *   "playlistId":    "PLxxxxxxxxxxxxxxxx"
 * }
 * }</pre>
 */
@Getter
@Setter
@NoArgsConstructor
@ToString
@JsonIgnoreProperties(ignoreUnknown = true)
public class VideoMetadata {


    /** Full video description. Supports newlines. */
    private String description;

    /** Optional list of tags/keywords. May be {@code null} or empty. */
    private List<String> tags;

    /**
     * YouTube category ID (numeric string).
     * See <a href="https://developers.google.com/youtube/v3/docs/videoCategories/list">videoCategories.list</a>.
     * Defaults to {@code "22"} (People &amp; Blogs) if not set.
     */
    private String categoryId = "22";

    /**
     * Privacy status of the video: {@code public}, {@code unlisted}, or {@code private}.
     * Defaults to {@code "private"} so accidental uploads are not immediately public.
     */
    private String privacyStatus = "private";

    /**
     * ID of an existing YouTube playlist the video should belong to.
     * Must be a playlist owned by the authenticated account.
     * If {@code null} the video is not added to any playlist.
     */
    private String playlistId;
}

