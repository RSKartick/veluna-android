# Veluna (Android)

[![Veluna CI & Release APK](../../actions/workflows/release-apk.yml/badge.svg)](../../actions/workflows/release-apk.yml)

Native Android music streaming and offline library app built with Kotlin, Jetpack Compose, Material Design 3, Media3 ExoPlayer, Room, DataStore, Android Keystore (`SecretVault`), and Firebase.

## Download & Install (Android 8.0+ / API 26+)

Signed, R8-minified release builds (`com.veluna.music`) are published on **GitHub Releases** from `v*` version tags:

- **[Browse Releases](../../releases)** and download **`veluna-android.apk`** from the tagged release assets.

### Verify APK Signature

Before installing, verify the signing certificate SHA-256 digest using `apksigner` from the Android SDK Build-Tools:

```bash
apksigner verify --print-certs veluna-android.apk
```

Compare the printed `Signer #1 certificate SHA-256 digest` against the release certificate SHA-256 published in the corresponding GitHub Release / `RELEASE_CERT_SHA256` repository variable. See [`RELEASE.md`](RELEASE.md) for full verification and signing details.

## Features

- **Home & Discovery**: Time-of-day greeting, mood/genre filter chips, pinned Shortcuts, Jump Back In, Familiar Artists, Quick Picks, and YouTube Music InnerTube shelves.
- **Search & Moods**: Debounced search suggestions, categorized results (Top, Songs, Videos, Albums, Artists, Playlists), local search history, and Moods & Genres tiles.
- **Library & Offline Playback**: Local playlists with Photo Picker cover editing, `On Repeat` auto-playlist, `Liked Music`, saved albums, followed artists, offline track downloads, and SAF local audio file/folder playback.
- **Player & Audio Engine**: Media3 `MediaSessionService` foreground playback with `WAKE_MODE_NETWORK`, dynamic artwork wash, Queue + Autoplay radio continuation, 10-band Equalizer presets, loudness normalization, playback speed, sleep timer, and toggleable classic/modern Now Playing layouts.
- **Synced Lyrics & Comments**: Multi-provider lyrics waterfall (`Boidu`, `LyricsPlus`, `LRCLIB`, `YouTube Music`, `SimpMusic`) with click-to-seek, timing offset nudge, kana/hangul romanization (`Romanize lyrics`), and YouTube track comments.
- **Listening Stats & History**: Range-filtered SQL aggregations (`Week`, `Month`, `Year`, `All`), daily bar chart, 7×24 weekday-hour heatmap, streaks, top songs/artists/albums, and listening history.
- **Import & Export**: Export any playlist as **M3U8 (`#EXTM3U`)** or **Exportify-compatible CSV** (`title,artists,album,duration_secs,video_id`); import playlists from **M3U/M3U8**, **CSV (Exportify)**, or **YouTube / YouTube Music URLs**.
- **Themes & Encrypted Scrobbling**: 7 theme presets, Light/Dark/System modes, artwork-adaptive or custom accent colors, and Last.fm + ListenBrainz scrobbling with persistent offline retry queue and Android Keystore AES-256-GCM credential storage.

## Data Storage, Cloud Sync & Backup

### Cloud-Synced Data (Firebase Firestore)

When signed in with Google (via Android Credential Manager), Veluna syncs account data under `users/{uid}`:

- Display profile and onboarding taste profile / seeds
- Liked YouTube songs (`LOCAL:` device tracks are strictly blocked from cloud sync)
- Custom playlists and their `playlists/{key}/tracks/{videoId}` track subcollections
- Hidden / blocked artists

### Device-Only Data

The following stay strictly on your device and are never uploaded to Firestore:

- Local device audio files (`local_tracks`) and offline downloaded tracks/files (`downloaded_tracks`)
- Lyrics cache (`lyrics_cache`), pending scrobble retry queue, listening history/stats, search history, saved albums, followed artists, and shortcuts
- Scrobble credentials (API key, API secret, session key, ListenBrainz token), stored in `SecretVault` (`noBackupFilesDir/secrets.bin`) encrypted with Android Keystore AES-256-GCM and excluded from Android Auto Backup and Device Transfer

### Sync & Tombstone Limitations

- Conflict resolution uses per-item **last-write-wins (`clientUpdatedAt`)** based on device clocks. A device with an inaccurate clock significantly behind real time may lose writes in conflicts.
- Deletions write soft tombstones that are automatically purged after **30 days**. A device kept offline longer than 30 days can resurrect items whose tombstones were already purged.

### Backup v2 (JSON Export & Restore)

- Exports playlists, liked tracks, taste profile, saved albums, followed artists, shortcuts, recent search history (up to 100), listening history (last 90 days), and theme/playback preferences.
- **Scrobble secrets are never exported.** Preferences are clamped to valid bounds and playlists are deduplicated by normalized title + track signature on restore.
- Legacy v1 backups remain importable and trigger an in-app warning banner advising you to delete unredacted v1 files.

### Account Sign-Out & Deletion

- **Sign-Out**: Flushes pending sync changes (or prompts before discarding), wipes synced account Room tables, clears `SecretVault` and user profile preferences, and signs out of Firebase and Credential Manager while keeping offline downloads and local audio files on the device.
- **In-App Deletion**: Open **Settings → Profile & Cloud Sync → Delete Account & Cloud Data** for a two-step confirmation that re-authenticates with Google (if required), removes all Firestore documents and subcollections under `users/{uid}`, deletes the Firebase Auth user, and wipes local account data and secrets while keeping downloaded/local media intact.
- **Web Deletion Path**: If distributing publicly on Google Play or an external store, host a static account-deletion instruction page referencing **Veluna** and the in-app path above (along with a support contact) and link it in your store listing's Data Safety / Account Deletion URL field.

## Third-Party Services & Privacy

Veluna communicates with the following external services depending on features you use:

| Service | Host(s) | Purpose & Privacy Notes |
|---|---|---|
| **YouTube Music (InnerTube)** | `music.youtube.com` | Search, browse shelves, comments, track metadata, and primary audio stream resolution. |
| **Piped / Invidious Mirrors (Opt-In, off by default)** | `pipedapi.kavin.rocks`, `pipedapi.adminforge.de`, `api.piped.private.coffee`, `inv.nadeko.net`, `yewtu.be` | Only contacted if you enable **Settings → Audio & Playback → Enable third-party Piped/Invidious fallback mirrors**. Receives the track `videoId` and your IP address when primary InnerTube playback fails. |
| **Lyrics Providers** | `lrclib.net`, `lyricsplus.binimum.org`, `lyrics-api.boidu.dev`, `api-lyrics.simpmusic.org` | Fetches synced/plain lyrics (individually toggleable and reorderable in Settings). |
| **Scrobbling (Opt-In)** | `ws.audioscrobbler.com` (Last.fm), `api.listenbrainz.org` (ListenBrainz) | Submits scrobbles and "now playing" updates when configured with your credentials. |
| **Firebase** | Google Identity / Credential Manager, Firebase Auth, Cloud Firestore | Optional Google Sign-In and cross-device library sync. |

## Build & Test Locally

### Prerequisites

- **JDK 21** and **Gradle 9.3.1** (or Android Studio with Gradle 9.3.1; maintainers can optionally generate a local wrapper via `gradle wrapper --gradle-version 9.3.1`)
- **Android SDK** (`compileSdk 36`, `minSdk 26`, `targetSdk 36`)
- **Node.js 20+** and **Firebase CLI** (for Firestore security rules emulator tests)
- A Firebase project config at `app/google-services.json` (gitignored):
  1. Create a Firebase project and register Android app `com.veluna.music` with your release/debug SHA-1 & SHA-256 certificate fingerprints.
  2. Enable **Authentication → Google** and **Cloud Firestore**.
  3. Download `google-services.json` into `app/google-services.json`.

### Build Commands

```bash
# Signed Release APK (requires RELEASE_KEYSTORE_* environment variables; R8 minified, non-debuggable)
gradle :app:assembleRelease

# Android Lint (HardcodedText is enforced as an error)
gradle :app:lintDebug
```

### Running Tests

```bash
# 1. Install Node test dependencies for Firestore rules testing
npm install

# 2. Run Firestore security rules & Android unit/Robolectric/Room migration tests
npx firebase emulators:exec --only firestore,auth "node --test firestore.test.js && gradle :app:testDebugUnitTest --stacktrace"
```

## License

See [`LICENSE`](LICENSE).
