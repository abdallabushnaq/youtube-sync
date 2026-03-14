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

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.auth.oauth2.StoredCredential;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.services.youtube.YouTube;
import com.google.api.services.youtube.YouTubeScopes;
import lombok.extern.slf4j.Slf4j;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.Comparator;
import java.util.List;

/**
 * Builds an authenticated {@link YouTube} service client using the OAuth 2.0 installed-app flow.
 *
 * <h2>First run</h2>
 * <p>On the first run a browser window opens for the user to grant consent.  The resulting
 * tokens are stored in {@code <folder>/oauth-tokens/} and reused on subsequent runs.
 *
 * <h2>Credentials file</h2>
 * <p>The Google Cloud OAuth client-secrets JSON file ({@code client_secret.json}) must be
 * placed in the same folder as the video files (the watched folder).
 */
@Slf4j
public class YouTubeClientFactory {

    /** Application name sent to the YouTube Data API. */
    private static final String APPLICATION_NAME = "youtube-sync";

    /** OAuth 2.0 scopes required: upload, manage playlists, delete videos. */
    private static final List<String> SCOPES = List.of(
            YouTubeScopes.YOUTUBE_UPLOAD,
            YouTubeScopes.YOUTUBE_FORCE_SSL
    );

    private static final GsonFactory JSON_FACTORY = GsonFactory.getDefaultInstance();

    /**
     * Builds and returns an authenticated {@link YouTube} service client.
     *
     * @param folder the watched folder containing {@code client_secret.json}; OAuth tokens are
     *               persisted in a sub-directory {@code oauth-tokens} inside this folder
     * @return a ready-to-use {@link YouTube} client
     * @throws IOException              if the secrets file cannot be read
     * @throws GeneralSecurityException if the HTTPS transport cannot be initialised
     */
    public YouTube build(Path folder) throws IOException, GeneralSecurityException {
        Path secretsFile = folder.resolve("client_secret.json");
        if (!secretsFile.toFile().exists()) {
            throw new IOException(
                    "client_secret.json not found in " + folder
                    + ". Download it from the Google Cloud Console and place it in the video folder.");
        }

        NetHttpTransport transport = GoogleNetHttpTransport.newTrustedTransport();

        GoogleClientSecrets clientSecrets;
        try (InputStreamReader reader = new InputStreamReader(
                new FileInputStream(secretsFile.toFile()), StandardCharsets.UTF_8)) {
            clientSecrets = GoogleClientSecrets.load(JSON_FACTORY, reader);
        }

        Path tokenDir = folder.resolve("oauth-tokens");
        Files.createDirectories(tokenDir);

        GoogleAuthorizationCodeFlow flow = buildFlow(transport, clientSecrets, tokenDir, false);

        // Detect a stored credential that is missing a refresh_token.
        //
        // Google only issues a refresh_token on the *first* authorization with
        // access_type=offline, or when the consent screen is forced again via
        // approval_prompt=force.  If the token store was seeded by an earlier version of
        // this tool (or by a manual grant without offline access), the stored credential
        // has an access_token but no refresh_token.  As soon as that access_token expires,
        // Credential.refreshToken() fails with "400 Missing required parameter: refresh_token".
        //
        // Recovery: wipe the store and rebuild the flow with approval_prompt=force so that
        // the consent screen is always shown and Google re-issues a refresh_token.
        try {
            StoredCredential stored = flow.getCredentialDataStore().get("user");
            if (stored != null && stored.getRefreshToken() == null) {
                log.warn("Stored credential is missing refresh_token; deleting '{}' and "
                        + "requesting fresh consent (a browser window will open).", tokenDir);
                deleteDirectory(tokenDir);
                Files.createDirectories(tokenDir);
                flow = buildFlow(transport, clientSecrets, tokenDir, true);
            }
        } catch (IOException e) {
            // The token store is unreadable — most commonly a Java-serialisation version
            // mismatch between an old StoredCredential file (written when the google-api-client
            // library serialised the non-transient 'lock: ReentrantLock' field) and the current
            // runtime, which no longer includes ReentrantLock in the native image's serialisation
            // metadata.  Simply continuing would let authorize() re-run the browser flow without
            // approval_prompt=force, causing Google to omit the refresh_token from the response
            // (Google only re-issues it on forced re-consent).  Delete the stale store and rebuild
            // with forceConsent=true so a clean credential with a refresh_token is written.
            log.warn("Stored credential is unreadable ({}); deleting '{}' and requesting fresh consent "
                    + "(a browser window will open).", e.getMessage(), tokenDir);
            deleteDirectory(tokenDir);
            Files.createDirectories(tokenDir);
            flow = buildFlow(transport, clientSecrets, tokenDir, true);
        }

        // Port 0 = let the OS pick a free port for the local callback server
        LocalServerReceiver receiver = new LocalServerReceiver.Builder().setPort(8888).build();
        // Use a ProcessBuilder-based browser opener to avoid java.awt.Desktop, which requires
        // the AWT native library that is not available in a GraalVM native image.
        Credential credential = new AuthorizationCodeInstalledApp(flow, receiver, this::openBrowser).authorize("user");

        log.info("OAuth authorisation successful");

        return new YouTube.Builder(transport, JSON_FACTORY, credential)
                .setApplicationName(APPLICATION_NAME)
                .build();
    }

    /**
     * Opens {@code url} in the system default browser without using {@code java.awt.Desktop}.
     *
     * <p>{@code java.awt.Desktop} requires the AWT native library ({@code awt.dll} / {@code
     * libawt.so}), which is not included in a GraalVM native image.  This method delegates to the
     * OS-native command instead:
     * <ul>
     *   <li>Windows — {@code rundll32 url.dll,FileProtocolHandler <url>} (avoids {@code cmd.exe}
     *       interpreting {@code &} in the URL as a command separator)
     *   <li>macOS — {@code open <url>}
     *   <li>Linux/other — {@code xdg-open <url>}
     * </ul>
     *
     * @param url the URL to open
     * @throws IOException if the OS process cannot be started
     */
    private void openBrowser(String url) throws IOException {
        // Print directly to stdout so the URL is always visible in the terminal regardless of
        // the Logback configuration (the native image routes logs to a file, not the console).
        System.out.println("Opening browser for OAuth consent:");
        System.out.println("  " + url);
        String os = System.getProperty("os.name", "").toLowerCase();
        ProcessBuilder pb;
        if (os.contains("win")) {
            // cmd /c start would treat '&' in the query string as a command separator.
            // rundll32 url.dll,FileProtocolHandler passes the URL directly to the Windows
            // URL handler without any shell interpretation.
            pb = new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url);
        } else if (os.contains("mac")) {
            pb = new ProcessBuilder("open", url);
        } else {
            pb = new ProcessBuilder("xdg-open", url);
        }
        pb.start();
    }

    /**
     * Builds the OAuth authorisation-code flow, backed by the given token directory.
     *
     * <p>If the existing token store is unreadable or corrupt the directory is wiped and a fresh
     * store is created, which will trigger the browser-based consent prompt on the next
     * {@code authorize()} call.
     *
     * @param transport     the HTTPS transport to use
     * @param clientSecrets the loaded OAuth client secrets
     * @param tokenDir      directory in which tokens are persisted
     * @param forceConsent  when {@code true}, adds {@code approval_prompt=force} to the
     *                      authorisation URL so Google always shows the consent screen and
     *                      re-issues a {@code refresh_token} — required after wiping a stale store
     * @return a ready-to-use {@link GoogleAuthorizationCodeFlow}
     * @throws IOException if the token directory cannot be created or if building the flow fails
     *                     for a reason other than a corrupt store
     */
    private GoogleAuthorizationCodeFlow buildFlow(NetHttpTransport transport,
            GoogleClientSecrets clientSecrets,
            Path tokenDir,
            boolean forceConsent) throws IOException {
        try {
            GoogleAuthorizationCodeFlow.Builder builder =
                    new GoogleAuthorizationCodeFlow.Builder(transport, JSON_FACTORY, clientSecrets, SCOPES)
                            .setDataStoreFactory(new FileDataStoreFactory(tokenDir.toFile()))
                            .setAccessType("offline");
            if (forceConsent) {
                // Forces the Google consent screen even if the user has already authorised this
                // client, ensuring Google re-issues a refresh_token in the response.
                // approval_prompt=force is the legacy parameter name accepted by the Google
                // OAuth 2.0 token endpoint; the modern equivalent is prompt=consent, but the
                // Google Java API client library encodes setApprovalPrompt() as approval_prompt
                // and both values are still accepted by the server.
                builder.setApprovalPrompt("force");
            }
            return builder.build();
        } catch (IOException e) {
            // Token store is corrupt (e.g. EOFException from a truncated file written by a
            // different JVM or a failed previous run).  Delete it and start fresh so that the
            // OAuth consent prompt runs and writes a clean store.
            log.warn("OAuth token store is unreadable ({}); deleting '{}' and re-authenticating.",
                    e.getMessage(), tokenDir);
            deleteDirectory(tokenDir);
            Files.createDirectories(tokenDir);
            return new GoogleAuthorizationCodeFlow.Builder(transport, JSON_FACTORY, clientSecrets, SCOPES)
                    .setDataStoreFactory(new FileDataStoreFactory(tokenDir.toFile()))
                    .setAccessType("offline")
                    .build();
        }
    }

    /**
     * Recursively deletes {@code dir} and all its contents.
     *
     * @param dir the directory to remove
     * @throws IOException if any file or directory cannot be deleted
     */
    private void deleteDirectory(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (IOException ex) {
                            log.warn("Could not delete '{}': {}", p, ex.getMessage());
                        }
                    });
        }
    }
}
