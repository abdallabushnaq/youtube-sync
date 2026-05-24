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

/**
 * Callback interface that {@link SyncService} uses to report execution progress to the caller.
 *
 * <p>Two distinct events are produced:
 * <ul>
 *   <li>{@link #onUploadProgress} — fired periodically while a single video is being uploaded,
 *       allowing the UI to display a per-file progress indicator.</li>
 *   <li>{@link #onActionCompleted} — fired once after each action (of any kind) finishes,
 *       allowing the UI to advance an overall progress bar.</li>
 * </ul>
 *
 * <p>Implementations may be called from a background thread; callers are responsible for
 * dispatching UI updates to the correct thread (e.g. via {@code UI.access()}).
 *
 * <p>Use {@link #NO_OP} as the default when no progress reporting is needed (e.g. in tests).
 */
public interface SyncProgressListener {

    /**
     * No-operation implementation; ignores all events.
     * Used as the default when {@link SyncService} is constructed without an explicit listener.
     */
    SyncProgressListener NO_OP = new SyncProgressListener() {
        @Override
        public void onUploadProgress(SyncAction action, int percent) {
        }

        @Override
        public void onActionCompleted(int completed, int total, SyncAction action) {
        }
    };

    /**
     * Called periodically while a single video file is being uploaded to YouTube.
     *
     * @param action  the action whose upload is in progress
     * @param percent current upload completion percentage (0–100)
     */
    void onUploadProgress(SyncAction action, int percent);

    /**
     * Called once after each action — regardless of kind — has been fully processed
     * (or skipped due to quota exhaustion or an error).
     *
     * @param completed number of actions completed so far (1-based)
     * @param total     total number of actions in the plan
     * @param action    the action that was just completed
     */
    void onActionCompleted(int completed, int total, SyncAction action);
}

