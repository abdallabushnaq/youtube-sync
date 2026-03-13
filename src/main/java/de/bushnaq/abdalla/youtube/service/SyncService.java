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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.FileContent;
import com.google.api.services.youtube.model.*;
import de.bushnaq.abdalla.youtube.dto.OldVersionStrategy;
import de.bushnaq.abdalla.youtube.dto.SyncAction;
import de.bushnaq.abdalla.youtube.dto.SyncAction.Kind;
import de.bushnaq.abdalla.youtube.dto.VideoMetadata;
import de.bushnaq.abdalla.youtube.service.QuotaTracker.QuotaBudgetExceededException;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Core synchronisation logic.
 *
 * <h2>Two-phase algorithm</h2>
 * <ol>
 *   <li><strong>Plan phase</strong> — validate playlists, then inspect every video file and
 *       determine the intended action ({@code UPLOAD}, {@code SKIP}, or {@code ERROR}) by
 *       comparing the local filename version against the remote playlist version.
 *       No mutations are made during this phase.</li>
 *   <li><strong>Display phase</strong> — print a human-readable plan table to the console so the
 *       operator can see exactly what will happen before anything is uploaded.</li>
 *   <li><strong>Execute phase</strong> — carry out each {@code UPLOAD} action, displaying
 *       per-video progress ({@code [n/total] filename [=====>   ] 42%}) while uploading.
 *       Skipped and errored files are left untouched.</li>
 * </ol>
 * <p>In dry-run mode the execute phase is skipped entirely.</p>
 */
@Slf4j
public class SyncService {

    /** Video file extensions recognised by this tool. */
    private static final Set<String> VIDEO_EXTENSIONS = Set.of(
            ".mp4", ".mov", ".mkv", ".avi", ".webm"
    );

    /**
     * Matches a filename stem ending with {@code -<digits>}.
     * Example: {@code "My-Product-Intro-2"} → group 1 = {@code "My-Product-Intro"},
     * group 2 = {@code "2"}.
     */
    private static final Pattern VERSION_PATTERN = Pattern.compile("^(.*)-([0-9]+)$");

    /**
     * Matches a YouTube title suffix {@code " v<digits>"} at the end of the string.
     * Example: {@code " v3"} → group 1 = {@code "3"}.
     */
    private static final Pattern TITLE_VERSION_PATTERN = Pattern.compile("^ v([0-9]+)$");

    /** Width of the inline upload progress bar in characters. */
    private static final int BAR_WIDTH = 30;

    private final YouTubeGateway gateway;
    private final ObjectMapper objectMapper;
    private final OldVersionStrategy oldVersionStrategy;
    private final QuotaTracker quotaTracker;
    private final boolean dryRun;

    /**
     * Constructs a {@code SyncService}.
     *
     * @param gateway            abstraction over the YouTube Data API
     * @param oldVersionStrategy what to do with old video versions after a re-upload
     * @param quotaTracker       tracks and enforces the estimated daily quota budget
     * @param dryRun             when {@code true} no API mutations are performed
     */
    public SyncService(YouTubeGateway gateway, OldVersionStrategy oldVersionStrategy,
                       QuotaTracker quotaTracker, boolean dryRun) {
        this.gateway = gateway;
        this.oldVersionStrategy = oldVersionStrategy;
        this.quotaTracker = quotaTracker;
        this.dryRun = dryRun;
        this.objectMapper = new ObjectMapper();
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Runs the full synchronisation pass over the given folder.
     *
     * <p>Phase 1: build a {@link SyncAction} for every video file (no mutations).<br>
     * Phase 2: print the plan table to the console.<br>
     * Phase 3: execute uploads (skipped when {@code dryRun = true}).
     *
     * @param folder the watched video folder
     * @throws IOException if the folder cannot be listed
     */
    public void sync(Path folder) throws IOException {
        List<Path> videoFiles = listVideoFiles(folder);
        log.info("Found {} video file(s) in {}", videoFiles.size(), folder);

        // --- Phase 1a: validate playlists ---
        Set<String> referencedPlaylists = collectPlaylistIds(videoFiles, folder);
        if (!validatePlaylists(referencedPlaylists)) {
            log.error("Aborting sync due to missing playlist(s). Fix the sidecar JSON files and retry.");
            return;
        }

        // --- Phase 1b: build plan ---
        List<SyncAction> plan = buildPlan(videoFiles, folder);

        // --- Phase 2: print plan table ---
        printPlanTable(plan);

        // --- Phase 2b: quota projection ---
        warnIfQuotaInsufficient(plan);

        if (dryRun) {
            log.info("[DRY-RUN] Execute phase skipped — no changes made to YouTube.");
            printSummary(plan);
            return;
        }

        // --- Phase 3: execute ---
        executePlan(plan);

        log.info(quotaTracker.summary());
        printSummary(plan);
    }

    /**
     * Builds the sync plan by inspecting each video file and querying its remote version.
     * This method makes read-only API calls ({@code playlistItems.list}) but no mutations.
     *
     * @param videoFiles sorted list of video file paths in the watched folder
     * @param folder     the watched folder (used to resolve sidecar JSON files)
     * @return ordered list of {@link SyncAction} — one per video file
     * @throws IOException if any API call fails fatally
     */
    public List<SyncAction> buildPlan(List<Path> videoFiles, Path folder) throws IOException {
        List<SyncAction> plan = new ArrayList<>();
        for (Path videoFile : videoFiles) {
            plan.add(planFile(folder, videoFile));
        }
        return plan;
    }

    // =========================================================================
    // Plan phase
    // =========================================================================

    /**
     * Determines the {@link SyncAction} for a single video file.
     *
     * @param folder    the watched folder
     * @param videoFile absolute path to the video file
     * @return the planned action
     */
    private SyncAction planFile(Path folder, Path videoFile) {
        String filename = videoFile.getFileName().toString();
        String stem = stripExtension(filename);

        Matcher m = VERSION_PATTERN.matcher(stem);
        if (!m.matches()) {
            return new SyncAction(videoFile, filename, null, -1, -1, null, Kind.ERROR,
                    "Filename stem does not end with '-<number>' (e.g. My-Video-1.mp4)");
        }
        String dashBaseTitle = m.group(1);
        int localVersion     = Integer.parseInt(m.group(2));
        String baseTitle     = dashBaseTitle.replace('-', ' ');

        VideoMetadata metadata;
        try {
            metadata = loadMetadata(folder, filename);
        } catch (IOException e) {
            return new SyncAction(videoFile, filename, baseTitle, localVersion, -1, null,
                    Kind.ERROR, "Cannot read sidecar JSON: " + e.getMessage());
        }

        int remoteVersion;
        try {
            remoteVersion = fetchRemoteVersion(metadata.getPlaylistId(), baseTitle);
        } catch (QuotaBudgetExceededException e) {
            throw e; // propagate immediately — stops the whole plan phase
        } catch (IOException e) {
            return new SyncAction(videoFile, filename, baseTitle, localVersion, -1,
                    metadata.getPlaylistId(), Kind.ERROR,
                    "Cannot fetch remote version: " + e.getMessage());
        }

        Kind kind = localVersion > remoteVersion ? Kind.UPLOAD : Kind.SKIP;
        return new SyncAction(videoFile, filename, baseTitle, localVersion, remoteVersion,
                metadata.getPlaylistId(), kind, null);
    }

    // =========================================================================
    // Display phase
    // =========================================================================

    /**
     * Prints a formatted plan table to {@code System.err} so the operator sees every file and
     * its intended action before any upload begins.
     *
     * <p>Example:
     * <pre>
     * ┌──────────────────────────────────┬────────┬───────┬────────┐
     * │ File                             │ Action │ Local │ Remote │
     * ├──────────────────────────────────┼────────┼───────┼────────┤
     * │ My-Product-Intro-1.mp4           │ UPLOAD │   1   │   0    │
     * │ My-Release-Notes-2.mp4           │  SKIP  │   2   │   2    │
     * │ bad-filename.mp4                 │ ERROR  │   -   │   -    │
     * └──────────────────────────────────┴────────┴───────┴────────┘
     * </pre>
     *
     * @param plan the list of planned actions
     */
    public void printPlanTable(List<SyncAction> plan) {
        // Compute column widths
         int fileCol = Math.max(80, plan.stream()
                .mapToInt(a -> a.filename().length()).max().orElse(40));

        String top = "┌" + "─".repeat(fileCol) + "──┬────────┬───────┬────────┐";
        String mid = "├" + "─".repeat(fileCol) + "──┼────────┼───────┼────────┤";
        String bot = "└" + "─".repeat(fileCol) + "──┴────────┴───────┴────────┘";

        System.err.println();
        System.err.println(top);
        System.err.printf("│ %-" + fileCol + "s │ Action │ Local │ Remote │%n", "File");
        System.err.println(mid);

        // Width available for text inside the file cell (between "│ " and " │")
        final int cellWidth = fileCol;
        // Width available for the error message after the "↳ " prefix (4 chars: "  ↳ ")
        final int msgWidth  = cellWidth - 4;

        for (SyncAction a : plan) {
            String action = switch (a.kind()) {
                case UPLOAD -> "UPLOAD";
                case SKIP   -> " SKIP ";
                case ERROR  -> " ERR  ";
            };
            String local  = a.localVersion()  >= 0 ? String.valueOf(a.localVersion())  : "-";
            String remote = a.remoteVersion() >= 0 ? String.valueOf(a.remoteVersion()) : "-";
            System.err.printf("│ %-" + cellWidth + "s │ %-6s │  %-5s│  %-5s │%n",
                    a.filename(), action, local, remote);
            if (a.kind() == Kind.ERROR && a.errorMessage() != null) {
                // Wrap the error message into lines of at most msgWidth characters
                String remaining = a.errorMessage();
                boolean first = true;
                while (!remaining.isEmpty()) {
                    String chunk;
                    if (remaining.length() <= msgWidth) {
                        chunk = remaining;
                        remaining = "";
                    } else {
                        // Break at the last space within msgWidth, or hard-break if none
                        int breakAt = remaining.lastIndexOf(' ', msgWidth);
                        if (breakAt <= 0) breakAt = msgWidth;
                        chunk     = remaining.substring(0, breakAt).stripTrailing();
                        remaining = remaining.substring(breakAt).stripLeading();
                    }
                    String prefix = first ? "  ↳ " : "    ";
                    first = false;
                    System.err.printf("│ %-" + cellWidth + "s │        │       │        │%n",
                            prefix + chunk);
                }
            }
        }
        System.err.println(bot);
        System.err.println();
    }

    /**
     * Projects the estimated quota cost of all planned uploads against the remaining budget and
     * logs a warning for every upload that would be blocked due to insufficient quota.
     *
     * <p>This is a read-only projection — no quota is actually charged here.  It lets the
     * operator see the problem <em>before</em> any upload starts, even in dry-run mode.
     *
     * <p>Cost per upload: {@code videos.insert} (1,600) + {@code playlistItems.insert} (50)
     * = 1,650 units.  Old-version handling is ignored here as it is conditional and cheap.
     *
     * @param plan the list of planned actions
     */
    private void warnIfQuotaInsufficient(List<SyncAction> plan) {
        final int costPerUpload = QuotaTracker.COST_VIDEO_INSERT + QuotaTracker.COST_PLAYLIST_ITEM_INSERT;
        // Simulate remaining budget as uploads are consumed one by one
        int remaining = quotaTracker.remaining();
        boolean warned = false;
        for (SyncAction action : plan) {
            if (action.kind() != Kind.UPLOAD) continue;
            if (remaining < costPerUpload) {
                if (!warned) {
                    log.warn("──────────────────────────────────────────────────────────");
                    log.warn("QUOTA WARNING: not enough estimated quota for all uploads.");
                    log.warn("  Remaining budget : {} units", quotaTracker.remaining());
                    log.warn("  Cost per upload  : {} units (videos.insert + playlistItems.insert)",
                            costPerUpload);
                    log.warn("  The following upload(s) will be blocked at runtime:");
                    warned = true;
                }
                log.warn("    ✗ {} (needs {} units, only {} remaining)",
                        action.filename(), costPerUpload, remaining);
            } else {
                remaining -= costPerUpload;
            }
        }
        if (warned) {
            log.warn("  Increase --quota-budget or wait for the daily quota to reset.");
            log.warn("──────────────────────────────────────────────────────────");
        }
    }

    // =========================================================================
    // Execute phase
    // =========================================================================

    /**
     * Executes all {@link Kind#UPLOAD} actions in the plan, in order.
     * Stops on quota exhaustion; logs errors for individual failures without aborting the run.
     *
     * @param plan the list of planned actions
     */
    private void executePlan(List<SyncAction> plan) {
        long uploadCount = plan.stream().filter(a -> a.kind() == Kind.UPLOAD).count();
        int  uploadIndex = 0;

        for (SyncAction action : plan) {
            if (action.kind() != Kind.UPLOAD) continue;
            uploadIndex++;
            try {
                executeUpload(action, uploadIndex, (int) uploadCount);
            } catch (QuotaBudgetExceededException e) {
                log.error("Quota budget exhausted. Stopping sync. {}", e.getMessage());
                log.error("Check real usage at https://console.cloud.google.com/apis/api/youtube.googleapis.com/quotas");
                break;
            } catch (QuotaExceededException e) {
                log.error("YouTube API daily quota exceeded — remaining uploads cancelled.");
                break;
            } catch (Exception e) {
                log.error("Unexpected error uploading {}: {}", action.filename(), e.getMessage(), e);
            }
        }
    }

    /**
     * Uploads a single video, adds it to the playlist, and handles the old version.
     *
     * @param action      the planned upload action
     * @param index       1-based index of this upload within all uploads in the plan
     * @param totalUploads total number of UPLOAD actions in the plan
     * @throws IOException on API failure
     */
    private void executeUpload(SyncAction action, int index, int totalUploads) throws IOException {
        String prefix = String.format("[%d/%d] %s", index, totalUploads, action.filename());

        // Locate old video ID before uploading (so we can handle it afterwards)
        String oldVideoId = action.remoteVersion() > 0
                ? findVideoIdInPlaylist(action.playlistId(), action.baseTitle(), action.remoteVersion())
                : null;

        String newVideoId = uploadVideo(action, prefix);
        addToPlaylist(newVideoId, action.playlistId());

        if (oldVideoId != null) {
            handleOldVersion(oldVideoId, action.playlistId());
        }
    }

    // =========================================================================
    // API helpers
    // =========================================================================

    /**
     * Scans the given playlist and returns the highest version number found for videos whose
     * title starts with {@code baseTitle} (case-insensitive prefix match).
     * Returns {@code 0} when the playlist is empty or no matching title is found.
     *
     * @param playlistId the YouTube playlist ID to scan; returns 0 if {@code null} or blank
     * @param baseTitle  the base title to search for (e.g. {@code "My Product Intro"})
     * @return the highest remote version, or {@code 0} if not found
     * @throws IOException            on API failure
     * @throws QuotaExceededException if quota is exhausted
     */
    private int fetchRemoteVersion(String playlistId, String baseTitle) throws IOException {
        if (playlistId == null || playlistId.isBlank()) {
            log.debug("No playlistId — treating remote version as 0 for '{}'", baseTitle);
            return 0;
        }
        String lowerBase = baseTitle.toLowerCase(Locale.ROOT);
        int highest = 0;
        String pageToken = null;
        do {
            quotaTracker.charge(QuotaTracker.COST_PLAYLIST_ITEM_LIST, "playlistItems.list");
            PlaylistItemListResponse resp;
            try {
                resp = gateway.listPlaylistItems(playlistId, pageToken);
            } catch (GoogleJsonResponseException e) {
                checkQuota(e);
                throw e;
            }
            if (resp.getItems() != null) {
                for (PlaylistItem item : resp.getItems()) {
                    String itemTitle = item.getSnippet().getTitle();
                    if (itemTitle == null) continue;
                    if (!itemTitle.toLowerCase(Locale.ROOT).startsWith(lowerBase)) continue;
                    String suffix = itemTitle.substring(baseTitle.length());
                    if (suffix.isBlank()) {
                        highest = Math.max(highest, 1);
                    } else {
                        Matcher vm = TITLE_VERSION_PATTERN.matcher(suffix);
                        if (vm.matches()) highest = Math.max(highest, Integer.parseInt(vm.group(1)));
                    }
                }
            }
            pageToken = resp.getNextPageToken();
        } while (pageToken != null);

        log.debug("Remote version for '{}' in playlist {}: {}", baseTitle, playlistId, highest);
        return highest;
    }

    /**
     * Finds the YouTube video ID of the video in the playlist that matches the given base title
     * and version number.
     *
     * @param playlistId    the playlist to scan; returns {@code null} if blank
     * @param baseTitle     the base title (e.g. {@code "My Product Intro"})
     * @param targetVersion the version number to look for
     * @return the YouTube video ID, or {@code null} if not found
     * @throws IOException            on API failure
     * @throws QuotaExceededException if quota is exhausted
     */
    private String findVideoIdInPlaylist(String playlistId, String baseTitle, int targetVersion)
            throws IOException {
        if (playlistId == null || playlistId.isBlank()) return null;
        String expectedTitle = buildTitle(baseTitle, targetVersion);
        String pageToken = null;
        do {
            quotaTracker.charge(QuotaTracker.COST_PLAYLIST_ITEM_LIST, "playlistItems.list");
            PlaylistItemListResponse resp;
            try {
                resp = gateway.listPlaylistItems(playlistId, pageToken);
            } catch (GoogleJsonResponseException e) {
                checkQuota(e);
                throw e;
            }
            if (resp.getItems() != null) {
                for (PlaylistItem item : resp.getItems()) {
                    if (expectedTitle.equalsIgnoreCase(item.getSnippet().getTitle())) {
                        return item.getSnippet().getResourceId().getVideoId();
                    }
                }
            }
            pageToken = resp.getNextPageToken();
        } while (pageToken != null);

        log.warn("Could not find videoId for '{}' (v{}) in playlist {}", baseTitle, targetVersion, playlistId);
        return null;
    }

    /**
     * Uploads a video file to YouTube, rendering a per-video progress bar prefixed with
     * {@code [n/total] filename}.
     *
     * @param action the planned upload action
     * @param prefix display prefix string shown to the left of the progress bar
     * @return the YouTube video ID assigned by the API
     * @throws IOException            on upload failure
     * @throws QuotaExceededException if the API reports quota exhaustion
     */
    private String uploadVideo(SyncAction action, String prefix) throws IOException {
        quotaTracker.charge(QuotaTracker.COST_VIDEO_INSERT, "videos.insert");
        String title = buildTitle(action.baseTitle(), action.localVersion());

        VideoMetadata metadata;
        try {
            metadata = loadMetadata(action.videoFile().getParent(), action.filename());
        } catch (IOException e) {
            throw new IOException("Cannot read metadata for " + action.filename(), e);
        }

        VideoSnippet snippet = new VideoSnippet();
        snippet.setTitle(title);
        snippet.setDescription(metadata.getDescription());
        snippet.setTags(metadata.getTags());
        snippet.setCategoryId(metadata.getCategoryId());

        VideoStatus status = new VideoStatus();
        status.setPrivacyStatus(metadata.getPrivacyStatus());

        String mimeType = guessMimeType(action.videoFile());
        FileContent mediaContent = new FileContent(mimeType, action.videoFile().toFile());

        log.info("Uploading '{}' ({}) as '{}' ...",
                action.filename(),
                humanReadableSize(action.videoFile().toFile().length()),
                title);

        try {
            String videoId = gateway.insertVideo(snippet, status, mediaContent, (state, percent) -> {
                switch (state) {
                    case INITIATING -> log.info("Upload initialising ...");
                    case IN_PROGRESS -> printProgress(prefix, percent);
                    case COMPLETE -> {
                        // Clear the progress line
                        System.err.print("\r" + " ".repeat(prefix.length() + BAR_WIDTH + 15) + "\r");
                        log.info("Upload transfer complete");
                    }
                }
            });
            log.info("Upload complete — videoId={}, title='{}'", videoId, title);
            return videoId;
        } catch (GoogleJsonResponseException e) {
            checkQuota(e);
            throw e;
        }
    }

    /**
     * Adds the given video to a YouTube playlist.
     *
     * @param videoId    the YouTube video ID to add
     * @param playlistId the target playlist ID; skipped if {@code null} or blank
     * @throws IOException            on API failure
     * @throws QuotaExceededException if quota is exhausted
     */
    private void addToPlaylist(String videoId, String playlistId) throws IOException {
        if (playlistId == null || playlistId.isBlank()) {
            log.debug("No playlistId set — skipping playlist insertion for videoId={}", videoId);
            return;
        }
        quotaTracker.charge(QuotaTracker.COST_PLAYLIST_ITEM_INSERT, "playlistItems.insert");
        try {
            gateway.insertPlaylistItem(videoId, playlistId);
            log.info("Added videoId={} to playlist {}", videoId, playlistId);
        } catch (GoogleJsonResponseException e) {
            checkQuota(e);
            throw e;
        }
    }

    /**
     * Handles the old video version according to the configured {@link OldVersionStrategy}.
     *
     * @param oldVideoId the YouTube video ID of the old version
     * @param playlistId the playlist from which to remove the old video (KEEP strategy)
     * @throws IOException            on API failure
     * @throws QuotaExceededException if quota is exhausted
     */
    private void handleOldVersion(String oldVideoId, String playlistId) throws IOException {
        if (oldVersionStrategy == OldVersionStrategy.DELETE) {
            log.info("Strategy=DELETE — deleting old videoId={}", oldVideoId);
            quotaTracker.charge(QuotaTracker.COST_VIDEO_DELETE, "videos.delete");
            try {
                gateway.deleteVideo(oldVideoId);
                log.info("Deleted old videoId={}", oldVideoId);
            } catch (GoogleJsonResponseException e) {
                checkQuota(e);
                throw e;
            }
        } else {
            log.info("Strategy=KEEP — removing old videoId={} from playlist {}", oldVideoId, playlistId);
            removeFromPlaylist(oldVideoId, playlistId);
        }
    }

    /**
     * Removes a specific video from a playlist by finding its playlist-item entry and deleting it.
     *
     * @param videoId    the YouTube video ID to remove
     * @param playlistId the playlist to remove it from
     * @throws IOException            on API failure
     * @throws QuotaExceededException if quota is exhausted
     */
    private void removeFromPlaylist(String videoId, String playlistId) throws IOException {
        if (playlistId == null || playlistId.isBlank()) {
            log.debug("No playlistId — nothing to remove for old videoId={}", videoId);
            return;
        }
        try {
            String pageToken = null;
            do {
                quotaTracker.charge(QuotaTracker.COST_PLAYLIST_ITEM_LIST, "playlistItems.list");
                PlaylistItemListResponse response = gateway.listPlaylistItems(playlistId, pageToken);
                for (PlaylistItem item : response.getItems()) {
                    if (videoId.equals(item.getSnippet().getResourceId().getVideoId())) {
                        quotaTracker.charge(QuotaTracker.COST_PLAYLIST_ITEM_DELETE, "playlistItems.delete");
                        gateway.deletePlaylistItem(item.getId());
                        log.info("Removed old videoId={} (playlistItemId={}) from playlist {}",
                                videoId, item.getId(), playlistId);
                        return;
                    }
                }
                pageToken = response.getNextPageToken();
            } while (pageToken != null);
            log.warn("Old videoId={} was not found in playlist {} — nothing removed", videoId, playlistId);
        } catch (GoogleJsonResponseException e) {
            checkQuota(e);
            throw e;
        }
    }

    // =========================================================================
    // Playlist / metadata helpers
    // =========================================================================

    /**
     * Collects the set of distinct playlist IDs referenced across all sidecar JSON files.
     *
     * @param videoFiles list of video file paths
     * @param folder     the watched folder
     * @return set of playlist IDs (never {@code null}; may be empty)
     */
    private Set<String> collectPlaylistIds(List<Path> videoFiles, Path folder) {
        Set<String> ids = new LinkedHashSet<>();
        for (Path vf : videoFiles) {
            String filename = vf.getFileName().toString();
            Path sidecar = folder.resolve(stripExtension(filename) + ".json");
            if (!sidecar.toFile().exists()) continue;
            try {
                VideoMetadata metadata = objectMapper.readValue(sidecar.toFile(), VideoMetadata.class);
                if (metadata.getPlaylistId() != null && !metadata.getPlaylistId().isBlank()) {
                    ids.add(metadata.getPlaylistId());
                }
            } catch (IOException e) {
                log.warn("Could not parse sidecar {}: {}", sidecar.getFileName(), e.getMessage());
            }
        }
        return ids;
    }

    /**
     * Validates that every given playlist ID exists on the authenticated user's YouTube channel.
     *
     * @param playlistIds the set of playlist IDs to validate
     * @return {@code true} if all playlists exist; {@code false} if any are missing or on API error
     */
    private boolean validatePlaylists(Set<String> playlistIds) {
        if (playlistIds.isEmpty()) return true;
        boolean allOk = true;
        for (String id : playlistIds) {
            try {
                quotaTracker.charge(QuotaTracker.COST_PLAYLISTS_LIST, "playlists.list");
                boolean exists = gateway.playlistExists(id);
                if (!exists) {
                    log.error("Playlist '{}' does not exist or is not accessible by the authenticated account.", id);
                    allOk = false;
                } else {
                    log.info("Playlist '{}' found — OK", id);
                }
            } catch (GoogleJsonResponseException e) {
                log.error("API error validating playlist '{}': {}", id, e.getDetails().getMessage());
                allOk = false;
            } catch (IOException e) {
                log.error("I/O error validating playlist '{}': {}", id, e.getMessage());
                allOk = false;
            }
        }
        return allOk;
    }

    /**
     * Loads and deserialises the sidecar JSON file for a given video filename.
     * If the sidecar does not exist, a {@link VideoMetadata} with defaults is returned.
     *
     * @param folder   the watched folder
     * @param filename the video filename (no path component)
     * @return the parsed {@link VideoMetadata}
     * @throws IOException if the sidecar exists but cannot be parsed
     */
    private VideoMetadata loadMetadata(Path folder, String filename) throws IOException {
        Path sidecar = folder.resolve(stripExtension(filename) + ".json");
        if (sidecar.toFile().exists()) {
            return objectMapper.readValue(sidecar.toFile(), VideoMetadata.class);
        }
        log.warn("No sidecar JSON found for {} — using default settings", filename);
        return new VideoMetadata();
    }

    /**
     * Lists all video files in the given folder (non-recursive), sorted by filename.
     *
     * @param folder the folder to scan
     * @return sorted list of video file paths
     * @throws IOException if the folder cannot be read
     */
    private List<Path> listVideoFiles(Path folder) throws IOException {
        try (Stream<Path> stream = Files.list(folder)) {
            return stream
                    .filter(p -> !Files.isDirectory(p))
                    .filter(p -> isVideoFile(p.getFileName().toString()))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
        }
    }

    // =========================================================================
    // Small utilities
    // =========================================================================

    /**
     * Returns {@code true} if the filename has a recognised video extension.
     *
     * @param filename the filename to check
     * @return {@code true} for recognised video files
     */
    private boolean isVideoFile(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot < 0) return false;
        return VIDEO_EXTENSIONS.contains(filename.substring(dot).toLowerCase(Locale.ROOT));
    }

    /**
     * Strips the file extension from a filename.
     *
     * @param filename the full filename
     * @return filename without extension, or the original string if no dot is found
     */
    private String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }

    /**
     * Builds the YouTube title for the given base title and version number.
     * Version 1 has no suffix; v2+ get {@code " v<n>"} appended.
     *
     * @param baseTitle the base title (spaces, no version suffix)
     * @param version   the version number (&gt;= 1)
     * @return the YouTube title string
     */
    private String buildTitle(String baseTitle, int version) {
        return version == 1 ? baseTitle : baseTitle + " v" + version;
    }

    /**
     * Renders an inline upload progress bar to {@code System.err}, prefixed with
     * {@code [n/total] filename}.
     *
     * <p>Example: {@code [2/3] My-Product-Intro-1.mp4 [=============>    ] 58%}
     *
     * @param prefix  the prefix string ({@code "[n/total] filename"})
     * @param percent upload completion percentage 0–100
     */
    private void printProgress(String prefix, int percent) {
        int filled = (int) Math.round(percent / 100.0 * BAR_WIDTH);
        StringBuilder bar = new StringBuilder("[");
        for (int i = 0; i < BAR_WIDTH; i++) {
            if (i < filled - 1)      bar.append('=');
            else if (i == filled - 1) bar.append('>');
            else                      bar.append(' ');
        }
        bar.append("] ").append(percent).append('%');
        System.err.print("\r" + prefix + " " + bar);
    }

    /**
     * Guesses the MIME type of a video file by its extension.
     *
     * @param file the video file path
     * @return a MIME type string; falls back to {@code "video/mp4"} for unknown extensions
     */
    private String guessMimeType(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".mp4"))  return "video/mp4";
        if (name.endsWith(".mov"))  return "video/quicktime";
        if (name.endsWith(".mkv"))  return "video/x-matroska";
        if (name.endsWith(".avi"))  return "video/x-msvideo";
        if (name.endsWith(".webm")) return "video/webm";
        return "video/mp4";
    }

    /**
     * Returns a human-readable file size string (e.g. {@code "142.3 MB"}).
     *
     * @param bytes file size in bytes
     * @return formatted size string
     */
    private String humanReadableSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format("%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format("%.1f MB", mb);
        return String.format("%.1f GB", mb / 1024.0);
    }

    /**
     * Inspects a {@link GoogleJsonResponseException} and throws {@link QuotaExceededException}
     * if the error reason indicates quota exhaustion.
     *
     * @param e the API exception to inspect
     * @throws QuotaExceededException if the error is a quota error
     */
    private void checkQuota(GoogleJsonResponseException e) {
        if (e.getDetails() != null && e.getDetails().getErrors() != null) {
            boolean isQuota = e.getDetails().getErrors().stream()
                    .anyMatch(err -> "quotaExceeded".equals(err.getReason())
                            || "dailyLimitExceeded".equals(err.getReason()));
            if (isQuota) {
                throw new QuotaExceededException(
                        "YouTube API quota exceeded: " + e.getDetails().getMessage());
            }
        }
    }

    /**
     * Logs a summary of the sync run using the completed plan.
     *
     * @param plan the list of planned actions
     */
    private void printSummary(List<SyncAction> plan) {
        long uploads = plan.stream().filter(a -> a.kind() == Kind.UPLOAD).count();
        long skips   = plan.stream().filter(a -> a.kind() == Kind.SKIP).count();
        long errs    = plan.stream().filter(a -> a.kind() == Kind.ERROR).count();
        log.info("─────────────────────────────────────────");
        log.info("Sync summary");
        log.info("  Files scanned : {}", plan.size());
        log.info("  Uploaded      : {}", dryRun ? "0 (dry-run)" : uploads);
        log.info("  Skipped       : {}", skips);
        log.info("  Errors        : {}", errs);
        log.info("─────────────────────────────────────────");
    }

    // =========================================================================
    // Inner exception
    // =========================================================================

    /**
     * Thrown when the YouTube Data API returns a quota-exceeded error.
     * Signals the sync loop to stop immediately.
     */
    static class QuotaExceededException extends RuntimeException {

        /**
         * @param message the detail message
         */
        QuotaExceededException(String message) {
            super(message);
        }
    }
}

