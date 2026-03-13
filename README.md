# youtube-sync

A command-line tool that synchronises a local folder of video files with your YouTube channel.

## Features

- **Upload new videos** — any video file that has no state entry is uploaded automatically.
- **Detect changed videos** — SHA-256 checksums detect when a local file has been updated since the last upload.
- **Version re-upload** — because YouTube does not allow replacing a video's media, changed videos are re-uploaded as a new version with a title suffix (`v2`, `v3`, …).
- **Old version handling** — after a re-upload, the previous version is either:
  - `keep` *(default)* — removed from the playlist but left on YouTube, preserving all user comments and the direct URL.
  - `delete` — deleted from YouTube entirely (**this also deletes all user comments**).
- **Playlist management** — new and re-uploaded videos are added to the playlist specified in the sidecar JSON; old versions are removed from it.
- **Quota guard** — if the YouTube API returns a `quotaExceeded` error, the tool logs clearly and stops immediately.
- **Dry-run mode** — validates credentials and playlists, then logs what *would* happen without making any changes.

## Prerequisites

1. **Java 21+** on your `PATH`.
2. A **Google Cloud project** with the YouTube Data API v3 enabled.
3. An **OAuth 2.0 client ID** of type *Desktop app* downloaded as `client_secret.json`.

## Setup

1. Place `client_secret.json` in the video folder.
2. Create a `<videofile>.json` sidecar file alongside each video (see format below).
3. Run the tool — on first run a browser window opens for OAuth consent; tokens are stored in `oauth-tokens/` inside the video folder.

### Sidecar JSON format

```json
{
  "title":         "My Tutorial",
  "description":   "A full description.\nSupports newlines.",
  "tags":          ["tutorial", "demo"],
  "categoryId":    "28",
  "privacyStatus": "public",
  "playlistId":    "PLxxxxxxxxxxxxxxxx"
}
```

| Field | Required | Default | Notes |
|---|---|---|---|
| `title` | Yes | filename (no ext) | v2, v3 … suffix added automatically on re-upload |
| `description` | No | `""` | |
| `tags` | No | `[]` | |
| `categoryId` | No | `"22"` | See [YouTube category list](https://developers.google.com/youtube/v3/docs/videoCategories/list) |
| `privacyStatus` | No | `"private"` | `public`, `unlisted`, or `private` |
| `playlistId` | No | none | Must be an existing playlist owned by your account |

## Building

```bash
mvn package -DskipTests
```

Produces `target/youtube-sync-1.0.0-SNAPSHOT.jar` (fat JAR, no external deps needed).

## Usage

```
java -jar youtube-sync.jar \
    --folder /path/to/videos \
    [--old-version-strategy delete|keep]   # default: keep
    [--dry-run]
```

### Examples

```bash
# Normal sync — keep old versions on YouTube but remove from playlist
java -jar youtube-sync.jar --folder /videos/tutorials

# Sync and delete old versions entirely (comments will be lost)
java -jar youtube-sync.jar --folder /videos/tutorials --old-version-strategy delete

# See what would happen without touching YouTube
java -jar youtube-sync.jar --folder /videos/tutorials --dry-run
```

## State file

`youtube-sync-state.json` is written to the video folder after each upload. It records the YouTube video ID, SHA-256 checksum, upload timestamp, and version number for every processed file. **Do not delete it** — it is what prevents duplicate uploads.

## Files kept out of git

`client_secret.json`, `oauth-tokens/`, and `youtube-sync-state.json` are listed in `.gitignore` and should never be committed.

## Supported video formats

`.mp4`, `.mov`, `.mkv`, `.avi`, `.webm`

## License

Copyright (C) 2025-2026 Abdalla Bushnaq. Licensed under the [Apache License 2.0](http://www.apache.org/licenses/LICENSE-2.0).

