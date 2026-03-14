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
package de.bushnaq.abdalla.youtube;

import com.google.api.services.youtube.YouTube;
import de.bushnaq.abdalla.youtube.dto.OldVersionStrategy;
import de.bushnaq.abdalla.youtube.service.QuotaTracker;
import de.bushnaq.abdalla.youtube.service.SyncService;
import de.bushnaq.abdalla.youtube.service.YouTubeClientFactory;
import de.bushnaq.abdalla.youtube.service.YouTubeGateway;
import de.bushnaq.abdalla.youtube.service.YouTubeGatewayImpl;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.PrintStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * Entry point for the youtube-sync CLI tool.
 *
 * <h2>Usage</h2>
 * <pre>
 * java -jar youtube-sync.jar \
 *     --folder /path/to/videos \
 *     [--old-version-strategy delete|keep]  (default: keep) \
 *     [--quota-budget 10000]                (default: 10000) \
 *     [--dry-run]
 * </pre>
 *
 * <h2>Required files inside --folder</h2>
 * <ul>
 *   <li>{@code client_secret.json} — Google Cloud OAuth client credentials
 *       (downloaded from the Google Cloud Console).</li>
 *   <li>One {@code <videofile>.json} sidecar per video file containing description,
 *       tags, categoryId, privacyStatus, and playlistId.</li>
 *   <li>Video filenames must end with a version suffix {@code -<n>} before the extension,
 *       e.g. {@code My-Product-Intro-2.mp4}.  Dashes are converted to spaces for the YouTube
 *       title; the version suffix is stripped for the base title.</li>
 * </ul>
 */
@Slf4j
@Command(
        name = "youtube-sync",
        mixinStandardHelpOptions = true,
        version = "1.0.0",
        description = "Synchronise a local video folder with a YouTube channel."
)
public class App implements Callable<Integer> {

    /** Path to the folder containing video files and sidecar JSON files. */
    @Option(names = "--folder", required = true,
            description = "Path to the folder containing video files and sidecar JSON files.")
    private Path folder;

    /**
     * Strategy for handling old video versions after a new version is uploaded.
     * {@code keep} (default) removes the old video from the playlist but leaves it on YouTube
     * so that existing comments and direct links are preserved.
     * {@code delete} removes the video from YouTube entirely (also deletes comments).
     */
    @Option(names = "--old-version-strategy", defaultValue = "KEEP",
            description = "What to do with old video versions after upload: KEEP or DELETE (default: ${DEFAULT-VALUE}).")
    private OldVersionStrategy oldVersionStrategy;

    /**
     * Maximum estimated YouTube Data API quota units to consume in this run.
     * The free tier provides 10,000 units per day.  The tool stops before making a call that
     * would exceed this budget.  Note: this is an <em>estimate</em>; actual consumption may
     * differ if other applications share the same Google Cloud project.
     */
    @Option(names = "--quota-budget", defaultValue = "10000",
            description = "Estimated daily quota budget in API units (default: 10000).")
    private int quotaBudget;

    /** When set, no changes are made to YouTube — all mutations are logged but skipped. */
    @Option(names = "--dry-run",
            description = "Log what would happen without actually uploading or modifying YouTube.")
    private boolean dryRun;

    /**
     * CLI entry point — delegates to picocli.
     *
     * @param args command-line arguments
     */
    public static void main(String[] args) {
        configureWindowsConsole();
        int exitCode = new CommandLine(new App()).execute(args);
        System.exit(exitCode);
    }

    /**
     * On Windows, switches the console output code page to UTF-8 (65001) and enables
     * Virtual Terminal Processing so that Unicode box-drawing characters and ANSI colour
     * escape codes render correctly in CMD and PowerShell without any manual {@code chcp}
     * invocation by the user.
     *
     * <p>Uses the Java 25 Foreign Function &amp; Memory API to call {@code kernel32.dll}
     * directly — no extra dependencies are required.  On non-Windows platforms this method
     * is a no-op.  Any failure is silently swallowed; the application continues with whatever
     * encoding the OS default provides.
     */
    private static void configureWindowsConsole() {
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
            return;
        }
        try {
            Linker       linker   = Linker.nativeLinker();
            SymbolLookup kernel32 = SymbolLookup.libraryLookup("kernel32", Arena.global());

            // SetConsoleOutputCP(65001) — switch console output to UTF-8
            MethodHandle setConsoleOutputCP = linker.downcallHandle(
                    kernel32.find("SetConsoleOutputCP").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
            setConsoleOutputCP.invoke(65001);

            // GetStdHandle(STD_OUTPUT_HANDLE = -11)
            MethodHandle getStdHandle = linker.downcallHandle(
                    kernel32.find("GetStdHandle").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
            MemorySegment hOut = (MemorySegment) getStdHandle.invoke(-11);

            try (Arena arena = Arena.ofConfined()) {
                MemorySegment modePtr = arena.allocate(ValueLayout.JAVA_INT);

                // GetConsoleMode(hOut, &mode)
                MethodHandle getConsoleMode = linker.downcallHandle(
                        kernel32.find("GetConsoleMode").orElseThrow(),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT,
                                ValueLayout.ADDRESS, ValueLayout.ADDRESS));
                getConsoleMode.invoke(hOut, modePtr);

                // SetConsoleMode(hOut, mode | ENABLE_VIRTUAL_TERMINAL_PROCESSING)
                int mode = modePtr.get(ValueLayout.JAVA_INT, 0);
                MethodHandle setConsoleMode = linker.downcallHandle(
                        kernel32.find("SetConsoleMode").orElseThrow(),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT,
                                ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
                setConsoleMode.invoke(hOut, mode | 0x0004); // 0x0004 = ENABLE_VIRTUAL_TERMINAL_PROCESSING
            }

            // Re-wrap System.out so Java encodes characters as UTF-8 bytes
            System.setOut(new PrintStream(System.out, true, StandardCharsets.UTF_8));

        } catch (Throwable t) {
            // Non-critical — continue with the platform default encoding
            log.debug("Could not configure Windows console for UTF-8/ANSI: {}", t.getMessage());
        }
    }

    /**
     * Executes the sync run.
     *
     * @return exit code: {@code 0} on success, {@code 1} on error
     */
    @Override
    public Integer call() {
        if (!folder.toFile().isDirectory()) {
            log.error("Folder does not exist or is not a directory: {}", folder);
            return 1;
        }

        if (dryRun) {
            log.info("DRY-RUN mode — no changes will be made to YouTube");
        }
        log.info("Folder           : {}", folder.toAbsolutePath());
        log.info("Old version      : {}", oldVersionStrategy.name().toLowerCase());
        log.info("Quota budget     : {} units (estimated)", quotaBudget);

        try {
            YouTubeClientFactory factory = new YouTubeClientFactory();
            YouTube youtube = factory.build(folder);

            YouTubeGateway gateway = new YouTubeGatewayImpl(youtube);
            QuotaTracker quotaTracker = new QuotaTracker(quotaBudget);
            SyncService syncService = new SyncService(gateway, oldVersionStrategy, quotaTracker, dryRun);
            syncService.sync(folder);
            return 0;
        } catch (Exception e) {
            log.error("Fatal error: {}", e.getMessage(), e);
            return 1;
        }
    }
}
