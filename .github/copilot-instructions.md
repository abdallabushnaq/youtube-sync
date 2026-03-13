# Copilot Instructions — youtube-sync

## Project purpose

`youtube-sync` is a **command-line tool** that synchronises a local folder of video files with a
YouTube channel via the YouTube Data API v3.  It is an open-source hobby project owned by
Abdalla Bushnaq and licensed under Apache 2.0.

---

## Technology versions

| Technology | Version |
|---|---|
| Java | 25 (GraalVM Community Edition) |
| Maven | 3.9+ |
| Lombok | 1.18.x (annotation-processor only — not a runtime dep) |
| picocli | 4.7.x |
| Google YouTube API client | v3-rev20240514-2.0.0 |
| Jackson | 2.17.x |
| SLF4J + Logback | 2.0.x / 1.5.x |
| JUnit Jupiter | 5.10.x |
| GraalVM native-maven-plugin | 0.10.x |

---

## Module layout

```
de.bushnaq.abdalla.youtube
├── App.java                   — picocli entry point; wires and starts SyncService
├── dto/
│   ├── OldVersionStrategy     — enum: KEEP | DELETE
│   ├── SyncAction             — record: planned action for one video file (plan phase output)
│   └── VideoMetadata          — record: parsed sidecar JSON fields
└── service/
    ├── YouTubeGateway         — interface abstracting every YouTube API call used by SyncService
    ├── YouTubeGatewayImpl     — production implementation backed by the real YouTube client
    ├── YouTubeClientFactory   — builds and caches an authenticated YouTube client from client_secret.json
    ├── QuotaTracker           — counts estimated API quota units; throws when budget is exceeded
    └── SyncService            — core two-phase sync algorithm (plan → execute)
```

---

## Core algorithm (`SyncService`)

1. **Plan phase** — validate playlists, scan local video files, compare local filename version
   against the remote playlist version, and build a list of `SyncAction` records
   (`UPLOAD`, `SKIP`, or `ERROR`).  No mutations.
2. **Display phase** — print a formatted plan table to the console so the operator can review
   what will happen.
3. **Execute phase** — perform each `UPLOAD` action with a per-video progress bar.  Skipped in
   `--dry-run` mode.

### File-naming convention

Video files must end with a **version suffix** `-<n>` before the extension:

```
My-Product-Intro-1.mp4   →  base title "My Product Intro",  version 1
My-Product-Intro-2.mp4   →  base title "My Product Intro",  version 2
```

Dashes are converted to spaces for the YouTube title.  The YouTube title carries a
` v<n>` suffix for version > 1 (e.g. `"My Product Intro v2"`).

### Sidecar JSON (`<videofile>.json`)

```json
{
  "title":         "My Tutorial",
  "description":   "Full description.",
  "tags":          ["tutorial"],
  "categoryId":    "28",
  "privacyStatus": "public",
  "playlistId":    "PLxxxxxxxxxxxxxxxx"
}
```

---

## CLI options

| Option | Required | Default | Description |
|---|---|---|---|
| `--folder` | Yes | — | Folder containing video files + sidecar JSON + `client_secret.json` |
| `--old-version-strategy` | No | `keep` | `keep` — remove old version from playlist only; `delete` — delete from YouTube entirely |
| `--quota-budget` | No | `10000` | Estimated daily API quota units (free tier = 10 000/day) |
| `--dry-run` | No | false | Plan + display only; no mutations |

---

## Build commands

```bash
# Compile and run tests
mvn test

# Fat JAR (no external deps)
mvn package -DskipTests
# Output: target/youtube-sync-1.0.0-SNAPSHOT.jar

# Native image (requires GraalVM JDK 25 on PATH)
mvn -Pnative package -DskipTests
# Output: target/youtube-sync  (Linux/macOS)  or  target/youtube-sync.exe  (Windows)
```

---

## Test strategy

- **No Spring, no H2** — tests are plain JUnit 5 with no application context.
- All test classes carry `@Tag("UnitTest")`.
- `StubYouTubeGateway` is an in-memory `YouTubeGateway` implementation used by all tests.
- `AbstractSyncServiceTest` — base class; provides `@TempDir tempDir` and a fresh
  `StubYouTubeGateway stub` via `@BeforeEach`.

| Test class | What it tests |
|---|---|
| `SyncServicePlanTest` | Calls `buildPlan()` directly; asserts plan contents without any I/O |
| `SyncServiceDryRunTest` | Calls `sync()` with `dryRun = true`; asserts zero mutations on the stub |
| `SyncServiceLiveTest` | Calls `sync()` with `dryRun = false`; asserts upload/delete counts on the stub |

Run the fast suite:
```bash
mvn test
```

---

## CI pipeline (GitHub Actions)

Workflow: `.github/workflows/native-image.yml`

- Triggers on push/PR to `main`.
- Matrix: **Linux x86-64** · **macOS arm64** · **Windows x86-64**, all with `fail-fast: false`.
- Sets up **GraalVM CE JDK 25** via `graalvm/setup-graalvm@v1`.
- Caches the Maven local repository keyed on `pom.xml`.
- Runs `mvn -Pnative package -DskipTests`.
- Uploads binaries as workflow artifacts:
  - `youtube-sync-linux-x86_64`
  - `youtube-sync-macos-arm64`
  - `youtube-sync-windows-x86_64`  (`.exe`)

---

## Coding standards

- **Apache 2.0 licence header** on every source file (`Copyright (C) 2025-2026 Abdalla Bushnaq`).
- **Javadoc** required on every `public` method and `public` field (include `@param`, `@return`,
  `@throws` where applicable).
- **Lombok** for boilerplate (`@Getter`, `@Setter`, `@Slf4j`, `@NoArgsConstructor`, etc.).
  Do **not** add it as a `<dependency>` — it is already an annotation-processor path in `pom.xml`.
- **Logging**: `@Slf4j` + `log.debug/info/warn/error`.  Never `System.out.println`.
- **Formatting**: Spotless / Eclipse formatter.  Run `mvn spotless:apply` before committing.
- **Language**: All code, comments, Javadoc, and commit messages in English.
- Prefer imports over fully-qualified names.

### Package conventions

| Package | Naming | Purpose |
|---|---|---|
| `dto/` | plain class name | Immutable value objects / records / enums shared across layers |
| `service/` | `*Service`, `*Gateway`, `*Factory`, `*Tracker` | Business logic and API adapters |
| Root | `App` | CLI entry point only |

---

## Files that must never be committed

`client_secret.json`, `oauth-tokens/`, `youtube-sync-state.json` (all covered by `.gitignore`).

