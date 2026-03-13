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

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * Tracks estimated YouTube Data API quota consumption within a single sync run.
 *
 * <h2>How YouTube quota works</h2>
 * <p>Every YouTube Data API v3 project starts with a daily budget of <strong>10,000 units</strong>.
 * Each API call costs a fixed number of units documented at
 * <a href="https://developers.google.com/youtube/v3/determine_quota_cost">developers.google.com</a>.
 * The representative costs used here are:
 * <ul>
 *   <li>{@code videos.insert}        — 1,600 units</li>
 *   <li>{@code playlistItems.insert} —    50 units</li>
 *   <li>{@code playlistItems.delete} —    50 units</li>
 *   <li>{@code playlistItems.list}   —     1 unit</li>
 *   <li>{@code playlists.list}       —     1 unit</li>
 *   <li>{@code videos.delete}        —    50 units</li>
 * </ul>
 *
 * <p><strong>Important:</strong> YouTube does <em>not</em> expose the remaining quota via the API.
 * This tracker operates on <em>estimates</em> based on the costs above.  If other applications or
 * manual API calls share the same Google Cloud project the actual remaining quota will be lower
 * than this tracker believes.  You can view real quota usage (with a few minutes of lag) at
 * <a href="https://console.cloud.google.com/apis/api/youtube.googleapis.com/quotas">
 * Google Cloud Console → APIs &amp; Services → YouTube Data API v3 → Quotas</a>.
 *
 * <p>When the estimated consumed quota would exceed the configured budget the next
 * {@link #charge(int, String)} call throws {@link QuotaBudgetExceededException}, stopping the
 * sync run immediately so no further API calls are made.
 */
@Slf4j
public class QuotaTracker {

    /** Quota cost for {@code videos.insert}. */
    public static final int COST_VIDEO_INSERT = 1_600;

    /** Quota cost for {@code playlistItems.insert}. */
    public static final int COST_PLAYLIST_ITEM_INSERT = 50;

    /** Quota cost for {@code playlistItems.delete}. */
    public static final int COST_PLAYLIST_ITEM_DELETE = 50;

    /** Quota cost for one page of {@code playlistItems.list}. */
    public static final int COST_PLAYLIST_ITEM_LIST = 1;

    /** Quota cost for one page of {@code playlists.list}. */
    public static final int COST_PLAYLISTS_LIST = 1;

    /** Quota cost for {@code videos.delete}. */
    public static final int COST_VIDEO_DELETE = 50;

    /** Total units consumed so far in this run. */
    @Getter
    private int consumed;

    /** Maximum units allowed before a hard stop. */
    private final int budget;

    /**
     * Constructs a {@code QuotaTracker} with the given daily budget.
     *
     * @param budget maximum estimated API units to spend; must be positive
     */
    public QuotaTracker(int budget) {
        if (budget <= 0) {
            throw new IllegalArgumentException("Quota budget must be positive, got: " + budget);
        }
        this.budget = budget;
        log.info("Quota budget set to {} units (estimated)", budget);
    }

    /**
     * Charges the given cost against the budget.
     *
     * <p>Throws {@link QuotaBudgetExceededException} <em>before</em> the call is made if adding
     * {@code cost} would exceed the budget, so the caller can safely skip the API call.
     *
     * @param cost        the estimated quota units the upcoming API call will consume; must be &gt;= 0
     * @param description a short human-readable description of the call (for logging)
     * @throws QuotaBudgetExceededException if {@code consumed + cost > budget}
     */
    public void charge(int cost, String description) {
        if (consumed + cost > budget) {
            throw new QuotaBudgetExceededException(
                    String.format(
                            "Quota budget exhausted. Budget: %d units, already consumed: %d, "
                            + "next call (%s) would cost %d more. "
                            + "Check real usage at https://console.cloud.google.com/apis/api/youtube.googleapis.com/quotas",
                            budget, consumed, description, cost));
        }
        consumed += cost;
        log.debug("Quota charge: +{} for '{}' — total consumed: {}/{}", cost, description, consumed, budget);
    }

    /**
     * Returns the number of quota units still available ({@code budget - consumed}).
     *
     * @return remaining budget units
     */
    public int remaining() {
        return budget - consumed;
    }

    /**
     * Returns {@code true} if charging {@code cost} additional units would exceed the budget,
     * without actually modifying the consumed counter.
     *
     * @param cost the hypothetical cost to check
     * @return {@code true} if the budget would be exceeded
     */
    public boolean wouldExceed(int cost) {
        return consumed + cost > budget;
    }

    /**
     * Returns a formatted summary line, e.g. {@code "Quota consumed: 1702 / 10000 units (estimated)"}.
     *
     * @return human-readable quota summary
     */
    public String summary() {
        return String.format("Quota consumed: %d / %d units (estimated)", consumed, budget);
    }

    // -------------------------------------------------------------------------
    // Inner exception
    // -------------------------------------------------------------------------

    /**
     * Thrown by {@link QuotaTracker#charge(int, String)} when the estimated quota budget would be
     * exceeded.  The sync loop catches this and stops immediately without making the API call.
     */
    public static class QuotaBudgetExceededException extends RuntimeException {

        /**
         * @param message detail message describing the quota state
         */
        public QuotaBudgetExceededException(String message) {
            super(message);
        }
    }
}

