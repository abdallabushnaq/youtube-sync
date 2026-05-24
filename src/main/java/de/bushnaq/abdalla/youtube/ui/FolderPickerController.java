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
package de.bushnaq.abdalla.youtube.ui;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Internal REST endpoint that opens a native OS folder-picker dialog and returns the
 * selected path as plain text.
 *
 * <p>Because Spring Boot runs with {@code java.awt.headless=true} by default, AWT dialogs
 * ({@code JFileChooser}) cannot be used.  This controller works around that by spawning an
 * OS-native process:
 * <ul>
 *   <li><strong>Windows</strong> — a PowerShell one-liner that shows a
 *       {@code System.Windows.Forms.FolderBrowserDialog}.</li>
 *   <li><strong>macOS</strong> — {@code osascript} with a "choose folder" AppleScript.</li>
 *   <li><strong>Linux</strong> — {@code zenity --file-selection --directory} (requires
 *       zenity to be installed).</li>
 * </ul>
 *
 * <p>The endpoint is intentionally unauthenticated — this application runs locally on the
 * developer's workstation only, same as {@link ShutdownController}.
 */
@RestController
@Slf4j
public class FolderPickerController {

    /**
     * Opens a native OS folder-picker dialog and returns the selected absolute path.
     *
     * @param initialDir optional starting directory hint (may be empty or a bare folder name);
     *                   ignored if it is not a valid absolute path
     * @return the selected folder path, or an empty string if the user cancelled
     */
    @GetMapping("/api/folder-picker")
    public String pickFolder(@RequestParam(defaultValue = "") String initialDir) {
        log.debug("Native folder picker requested; initialDir='{}'", initialDir);
        String os = System.getProperty("os.name", "").toLowerCase();
        try {
            String path;
            if (os.contains("win")) {
                path = pickWindows(initialDir);
            } else if (os.contains("mac")) {
                path = pickMac(initialDir);
            } else {
                path = pickLinux(initialDir);
            }
            log.debug("Folder picker returned '{}'", path);
            return path == null ? "" : path.trim();
        } catch (Exception ex) {
            log.warn("Native folder picker failed: {}", ex.getMessage());
            return "";
        }
    }

    /**
     * Opens a {@code FolderBrowserDialog} via PowerShell on Windows.
     *
     * <p>If {@code initialDir} is a bare folder name (no path separators), a quick PowerShell
     * search over common roots is run first to find the absolute path automatically.
     *
     * @param initialDir full path, or bare folder name to search for, or empty
     * @return selected path, or {@code null} if cancelled
     * @throws Exception if the PowerShell process cannot be started
     */
    private String pickWindows(String initialDir) throws Exception {
        // If we only have a bare folder name, try to locate it automatically first.
        if (initialDir != null && !initialDir.isBlank() && !isUsablePath(initialDir)) {
            String found = findFolderByName(initialDir);
            if (found != null && !found.isBlank()) {
                log.debug("Found folder '{}' at '{}'", initialDir, found);
                return found;
            }
            // Not found automatically — open the picker without a starting dir hint.
            initialDir = "";
        }

        String init = isUsablePath(initialDir)
                ? "$d.SelectedPath = '" + initialDir.replace("'", "''") + "';"
                : "";
        String script = String.format(
                "Add-Type -AssemblyName System.Windows.Forms; "
                        + "$d = New-Object System.Windows.Forms.FolderBrowserDialog; "
                        + "$d.Description = 'Select video folder'; "
                        + "%s"
                        + "if ($d.ShowDialog() -eq 'OK') { $d.SelectedPath }",
                init);

        List<String> cmd = List.of("powershell", "-NoProfile", "-NonInteractive", "-Command", script);
        return runProcess(cmd);
    }

    /**
     * Searches the local filesystem for a directory whose name matches {@code nameOrPath}.
     *
     * <p>If {@code nameOrPath} is already an absolute path it is returned as-is (after
     * verifying it refers to an existing directory on Windows — no extra search needed).
     * If it is a bare folder name the same search strategy as {@link #pickWindows} uses
     * is run without opening any interactive dialog.
     *
     * @param nameOrPath absolute path or bare folder name to search for
     * @return the absolute path if found, or an empty string if not found
     */
    public String findFolder(String nameOrPath) {
        if (nameOrPath == null || nameOrPath.isBlank()) {
            return "";
        }
        String os = System.getProperty("os.name", "").toLowerCase();
        try {
            if (isUsablePath(nameOrPath)) {
                // Already a full path — return it directly.
                return nameOrPath.trim();
            }
            // Bare name: try to locate on the filesystem.
            if (os.contains("win")) {
                String found = findFolderByName(nameOrPath);
                return found != null ? found.trim() : "";
            }
            // macOS / Linux: bare-name search not implemented; return empty.
            return "";
        } catch (Exception ex) {
            log.warn("Folder search failed for '{}': {}", nameOrPath, ex.getMessage());
            return "";
        }
    }

    /**
     * Searches common filesystem roots for a directory with the given bare name
     * using PowerShell with a limited recursion depth for performance.
     *
     * <p>Checked locations (in order):
     * <ol>
     *   <li>Well-known shortcuts: {@code %USERPROFILE%\Videos\name},
     *       {@code %USERPROFILE%\Documents\name}, {@code %USERPROFILE%\Desktop\name},
     *       {@code %USERPROFILE%\name}</li>
     *   <li>Up to 4 levels deep under {@code %USERPROFILE%}</li>
     *   <li>Up to 3 levels deep on D:\ and E:\ (common secondary drives)</li>
     * </ol>
     *
     * @param name bare folder name to look for
     * @return absolute path of the first match, or {@code null} if not found
     * @throws Exception if the PowerShell process cannot be started
     */
    private String findFolderByName(String name) throws Exception {
        String safeName = name.replace("'", "''");
        String script = String.format("""
                $n = '%s'
                # 1 — try well-known shortcuts first (instant)
                $shortcuts = @(
                    "$env:USERPROFILE\\Videos\\$n",
                    "$env:USERPROFILE\\Documents\\$n",
                    "$env:USERPROFILE\\Desktop\\$n",
                    "$env:USERPROFILE\\$n"
                )
                foreach ($s in $shortcuts) {
                    if (Test-Path $s -PathType Container) { Write-Output $s; exit }
                }
                # 2 — search up to 4 levels under user profile
                $r = Get-ChildItem -LiteralPath $env:USERPROFILE -Directory -Depth 4 -Filter $n -ErrorAction SilentlyContinue |
                     Select-Object -First 1 -ExpandProperty FullName
                if ($r) { Write-Output $r; exit }
                # 3 — search common secondary drives (depth 3)
                foreach ($drive in @('D:\\', 'E:\\')) {
                    if (-not (Test-Path $drive)) { continue }
                    $r = Get-ChildItem -LiteralPath $drive -Directory -Depth 3 -Filter $n -ErrorAction SilentlyContinue |
                         Select-Object -First 1 -ExpandProperty FullName
                    if ($r) { Write-Output $r; exit }
                }
                """, safeName);
        List<String> cmd = List.of("powershell", "-NoProfile", "-NonInteractive", "-Command", script);
        return runProcess(cmd);
    }

    /**
     * Opens a folder chooser via AppleScript on macOS.
     *
     * @param initialDir starting directory, or empty/invalid for no hint
     * @return selected path, or {@code null} if cancelled
     * @throws Exception if the osascript process cannot be started
     */
    private String pickMac(String initialDir) throws Exception {
        String defaultClause = isUsablePath(initialDir)
                ? " default location POSIX file \"" + initialDir.replace("\"", "\\\"") + "\""
                : "";
        String script = "POSIX path of (choose folder with prompt \"Select video folder\""
                + defaultClause + ")";
        List<String> cmd = List.of("osascript", "-e", script);
        return runProcess(cmd);
    }

    /**
     * Opens a folder chooser via {@code zenity} on Linux.
     *
     * @param initialDir starting directory, or empty/invalid for no hint
     * @return selected path, or {@code null} if cancelled
     * @throws Exception if zenity is not installed or the process cannot be started
     */
    private String pickLinux(String initialDir) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("zenity", "--file-selection", "--directory",
                "--title=Select video folder"));
        if (isUsablePath(initialDir)) {
            cmd.add("--filename=" + initialDir);
        }
        return runProcess(cmd);
    }

    /**
     * Runs a process and returns its trimmed standard-output, or {@code null} if the process
     * exits with a non-zero code (which indicates "cancel" for most native dialog tools).
     *
     * @param command the command and arguments
     * @return trimmed stdout, or {@code null} on cancellation / empty output
     * @throws Exception if the process cannot be started
     */
    private String runProcess(List<String> command) throws Exception {
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(false)
                .start();
        // Read stdout on the calling thread; the process is short-lived (user interaction).
        String output;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), Charset.defaultCharset()))) {
            output = reader.lines().collect(Collectors.joining("\n")).trim();
        }
        int exitCode = process.waitFor();
        if (exitCode != 0 || output.isBlank()) {
            log.debug("Folder picker process exited with code {} (user cancelled or no selection)", exitCode);
            return null;
        }
        return output;
    }

    /**
     * Returns {@code true} if {@code path} looks like a valid absolute path (contains a
     * path separator or a Windows drive letter), as opposed to a bare folder name.
     *
     * @param path the candidate path string
     * @return {@code true} if the string looks like an absolute path
     */
    private boolean isUsablePath(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        // Windows drive letter (C:\...) or UNC (\\server\...) or Unix absolute (/)
        return (path.length() >= 3 && path.charAt(1) == ':' && (path.charAt(2) == '\\' || path.charAt(2) == '/'))
                || path.startsWith("\\\\")
                || path.startsWith("/");
    }
}

