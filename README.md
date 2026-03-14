o a# youtube-sync

A command-line tool that synchronises a local folder of video files with your YouTube channel.

## Features

- **Upload new videos** — any local video file not yet on YouTube is uploaded automatically.
- **Version management** — video files follow a `<title>-<n>.<ext>` naming convention (e.g.
  `My-Tutorial-1.mp4`, `My-Tutorial-2.mp4`). When a higher-numbered version is found locally
  than what is currently on YouTube, the new version is uploaded and the previous one becomes
  the *old version*. Old versions are handled according to `--old-version-strategy`:
    - `keep` *(default)* — removed from the playlist but left on YouTube, preserving comments and the direct URL.
    - `delete` — deleted from YouTube entirely (**this also deletes all user comments**).
- **Playlist management** — newly uploaded videos are added to the playlist specified in the sidecar JSON; old versions
  are removed from it.
- **Quota guard** — tracks estimated API quota consumption and stops before exceeding the configured daily budget.
- **Dry-run mode** — validates credentials and playlists, then prints what *would* happen without making any changes.

## Prerequisites

1. A **Google Cloud project** with the YouTube Data API v3 enabled.
2. An **OAuth 2.0 client ID** of type *Desktop app* downloaded as `client_secret.json`.
   dd3. **Java 25** (or later) on your `PATH`. Any distribution works — Temurin, Corretto, GraalVM CE, etc.

## Setup

1. Place `client_secret.json` in the video folder.
2. Create a `<videofile>.json` sidecar file alongside each video (see format below).
3. Run the tool — on first run a browser window opens for OAuth consent; tokens are stored in `oauth-tokens/` inside the
   video folder.

### Sidecar JSON format

```json
{
  "title": "My Tutorial",
  "description": "A full description.\nSupports newlines.",
  "tags": [
    "tutorial",
    "demo"
  ],
  "categoryId": "28",
  "privacyStatus": "public",
  "playlistId": "PLxxxxxxxxxxxxxxxx"
}
```

All fields except `title` are optional.

## Building

```bash
# Compile and run tests
mvn test

# Fat JAR — all dependencies bundled, runs on any Java 25 JVM
mvn package -DskipTests
# → target/youtube-sync.jar
```

A pre-built fat JAR is available as a **GitHub Actions artifact** on every successful CI run
(`youtube-sync` artifact on the *Build* workflow).

## Usage

```bash
# Windows — suppress the JVM FFM access-warning for the console-setup helper
java --enable-native-access=ALL-UNNAMED -jar youtube-sync.jar --folder <path> [options]

# Linux / macOS
java -jar youtube-sync.jar --folder <path> [options]
```

| Option                   | Default      | Description                                                                    |
|--------------------------|--------------|--------------------------------------------------------------------------------|
| `--folder`               | *(required)* | Folder containing video files, sidecar JSON, and `client_secret.json`          |
| `--old-version-strategy` | `keep`       | `keep` — remove old version from playlist only; `delete` — delete from YouTube |
| `--quota-budget`         | `10000`      | Max estimated API quota units per run (free tier = 10 000/day)                 |
| `--dry-run`              | `false`      | Plan and display only; no uploads or deletions                                 |

### Examples

```bash
# Normal sync — keep old versions on YouTube but remove from playlist
java -jar youtube-sync.jar --folder /videos/tutorials

# Sync and delete old versions entirely (comments will be lost)
java -jar youtube-sync.jar --folder /videos/tutorials --old-version-strategy delete

# Limit API quota consumption to 5 000 units
java -jar youtube-sync.jar --folder /videos/tutorials --quota-budget 5000

# See what would happen without touching YouTube
java -jar youtube-sync.jar --folder /videos/tutorials --dry-run
```

## State file

`youtube-sync-state.json` is written to the video folder after each upload. It records the YouTube
video ID, SHA-256 checksum, upload timestamp, and version number for every processed file.
**Do not delete it** — it is what prevents duplicate uploads.

## Supported video formats

`.mp4`, `.mov`, `.mkv`, `.avi`, `.webm`

## License

Copyright (C) 2025-2026 Abdalla Bushnaq. Licensed under
the [Apache License 2.0](http://www.apache.org/licenses/LICENSE-2.0).
