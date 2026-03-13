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

/**
 * Describes what to do with the old YouTube video after a new version has been uploaded.
 */
public enum OldVersionStrategy {

    /**
     * Delete the old video from YouTube entirely.
     * <strong>Warning:</strong> this also permanently deletes all user comments on that video.
     */
    DELETE,

    /**
     * Keep the old video on YouTube but remove it from the playlist so it is no longer
     * featured.  The video remains accessible via its direct URL, preserving all comments.
     * This is the default strategy.
     */
    KEEP
}

