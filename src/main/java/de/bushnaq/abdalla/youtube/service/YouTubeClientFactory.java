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
import java.nio.file.Path;
import java.security.GeneralSecurityException;
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
        tokenDir.toFile().mkdirs();

        GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(
                transport, JSON_FACTORY, clientSecrets, SCOPES)
                .setDataStoreFactory(new FileDataStoreFactory(tokenDir.toFile()))
                .setAccessType("offline")
                .build();

        // Port 0 = let the OS pick a free port for the local callback server
        LocalServerReceiver receiver = new LocalServerReceiver.Builder().setPort(8888).build();
        Credential credential = new AuthorizationCodeInstalledApp(flow, receiver).authorize("user");

        log.info("OAuth authorisation successful");

        return new YouTube.Builder(transport, JSON_FACTORY, credential)
                .setApplicationName(APPLICATION_NAME)
                .build();
    }
}



