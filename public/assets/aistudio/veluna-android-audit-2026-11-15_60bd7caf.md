# Veluna Android: repo and APK audit (2026-11-15)

**Repo:** github.com/RSKartick/veluna-android, HEAD `709a42e` (shallow clone; 95 tracked files; 17,750 lines of Kotlin)
**APK:** `edc863e5-veluna-android.apk`, 29.9 MB, sha256 `c182f564…21`. Package `com.aistudio.veluna.mxkrtq`, versionName 1.0.2 (versionCode 12), minSdk 26, targetSdk 36.

## Verdict

Not release-ready. The app code is organised reasonably: feature folders, owner-scoped Firestore rules, cleartext traffic disabled, correct Last.fm request signing, no `http://` endpoints, and no empty catch blocks. The release pipeline is the serious problem. It signs with a private key that is committed to the public repo and ships a debuggable build. The cloud sync also has data-integrity bugs, and several README claims don't match the code.

## How this was checked

- **Executed:** decoded the keystore embedded in the CI workflow and compared its certificate byte-for-byte with the APK's v2 signer. Parsed the APK's binary manifest with androguard. Checked resource strings in the APK against the source. Parsed the decoded `google-services.json`.
- **Fetched:** Last.fm scrobbling rules, Android Auto Backup defaults, and Google Play's account-deletion policy.
- **Read in full:** CI workflow, Gradle files, manifest, `firestore.rules`, the cloud sync layer (`CloudSyncCoordinator`, `CloudSyncRepository`, models, `GoogleAuthHelper`), `UserPrefsRepository`, backup and export code, `ScrobbleClient`, `VelunaPlaybackService`, the scrobble and ticker logic in `PlayerController`, and the Room entities and DB version.
- **Grep and sampled only:** `InnerTubeClient` and parsers, `LyricsRepository`, `TasteEngine`, most UI screens.
- **Not done:** no build (no Android SDK in the sandbox); Firestore rules tests not run (emulator download host unreachable, and the repo has no `package.json`); no two-device sync test; no live Firebase access.

## Critical

**C1. The published APK is signed with a public key.** `.github/workflows/release-apk.yml` L53 embeds a base64 keystore (alias `androiddebugkey`, store password `android`) and uses it when no secret is set. Verified: the keystore opens with that password, and its certificate is byte-identical to the APK's signer (SHA-256 `b04de860…3b7e22`, SHA-1 `056a3c2d…220c683f`). Android accepts an APK as an update to an installed app when it carries the same certificate, and CI's versionCode is `10 + run_number`, so anyone can build a higher-versioned update that existing users can install over the real app. `google-services.json` (L76, also embedded) registers the Android OAuth client to the same SHA-1, so Google's Android checks can't tell that build from the real one.
*Fix:* generate a release key, keep it only in GitHub Secrets, and fail the build if the secret is missing. Treat the current key as compromised. Consider a new applicationId, since existing installs can't be safely moved to a new key. Restrict the Firebase API key in Google Cloud. The `AIza…` key is in the APK and is public by design, so its restrictions are the only protection.

## High

**H1. The shipped build is a debug build.** CI runs `gradle :app:assembleDebug` (L100) and publishes that output as the release (L138–152). Verified in the manifest: `android:debuggable="true"`. The APK also contains `PreviewActivity` from `debugImplementation` ui-tooling. The release build type has `isMinifyEnabled = false` and no signingConfig (`app/build.gradle.kts` L35–42), so the APK is unshrunk and 14 DEX files. Developers commonly report that Play Console rejects debuggable uploads, but I did not check this against current Play policy.
*Fix:* `assembleRelease`, `isMinifyEnabled = true`, and a release signing config.

**H2. Scrobbling secrets are stored in plaintext, backed up, and exported.**
- The Last.fm API secret, Last.fm session key, and ListenBrainz token are plain DataStore strings (`UserPrefsRepository.kt` L50–52, L129–130).
- `allowBackup="true"` (manifest L13) with no `dataExtractionRules` or `fullBackupContent`. Verified in Android's docs: Auto Backup includes shared prefs, files, and databases by default, so the DataStore file goes to Google cloud backup.
- `safeExportBackupJson` (`VelunaRepository.kt` L543–556) serialises the full `VelunaUserPrefs`, including all three secrets, into the JSON file the user saves (`SettingsScreen.kt` L146–150). The name says "safe"; nothing is redacted.
*Fix:* keep secrets in the Android Keystore or EncryptedSharedPreferences, exclude them via `dataExtractionRules`, and strip them from export.

**H3. Signing out keeps all local data, and the next sign-in merges it into the new account.** `signOut()` (`CloudSyncCoordinator.kt` L172) only stops sync and signs out. No code clears Room or DataStore. On the next sign-in, `runBidirectionalMerge` pushes the previous account's local likes (L326–331), playlists (L432–437) and blocked artists (L470–478) into the new account's Firestore data. Code-read, not reproduced.

**H4. Deletions are undone by sync.** The merge is a union with no tombstones. Each merge pushes anything local that the cloud lacks.
- Unlike a song on device A: the cloud doc is deleted. Device B, which still has the song locally, re-pushes it on its next merge, so the unlike is reverted.
- Delete a playlist on A: B recreates it from its local copy (L432).
- Clear all taste seeds: the write is skipped when the lists are empty (L305), so the cloud keeps them and the next merge restores them.
Code-read, not reproduced with two devices.
*Fix (pseudocode):*
```
row  = { id, updated_at, deleted: bool }          # local and cloud
merge(local, cloud):
  for id in union(local.ids, cloud.ids):
      winner = max_by(updated_at, [local[id], cloud[id]])
      if winner.deleted: delete_both(id)  # keep the tombstone ~30 days
      else:              upsert_both(id, winner)
```

**H5. No in-app account or data deletion.** Accounts are created through Google sign-in, and nothing in the code deletes the Firebase user, the Firestore subtree, or local data. Google Play requires an in-app path to delete the account and its data, plus a web link (support.google.com/googleplay/android-developer/answer/13327111). This blocks Play distribution, and it is a privacy gap for sideloaded users.

## Medium

- **M1. Local files can be liked and sync to the cloud as broken tracks.** "Add to Liked Music" (`TrackDialogs.kt` L139) and the Now Playing like button have no `isLocal` guard. `syncLikedSong` (`CloudSyncRepository.kt` L272) passes `LOCAL:…` through `sanitizeDocId`, which hashes it because the colon fails the regex. Other devices then show a `trk_<hash>` track that can't play.
- **M2. Scrobbles fire at the wrong time.** `PlayerController.kt` L273 scrobbles after 30 seconds of heard time, ignoring track length. Last.fm's rule is: the track must be longer than 30 seconds, and it must be played for half its duration or 4 minutes, whichever comes first (last.fm/api/scrobbling). So tracks longer than 60 seconds are scrobbled too early, and a 30-second track that plays through is scrobbled when it should not be.
*Fix:*
```
if not scrobbled and duration > 30 and heard >= min(duration / 2, 240):
    scrobbled = true; enqueue(track, started_at)
```
- **M3. Scrobble failures are dropped silently.** `ScrobbleClient.kt` L79 and L115 call `execute().close()` and never check `isSuccessful`. There is no retry queue, so offline scrobbles and invalid Last.fm session errors are lost without any signal.
- **M4. Sync status lies.** The watchers set "Cloud sync up to date" (`CloudSyncCoordinator.kt` L575, L628, L661) after `runCatching` blocks that swallow failures (L562–569, L602–619, L649–655).
- **M5. Test coverage is thin and not run in CI.** There are two test files, both for Firestore. The workflow runs no tests (zero matches for test commands). `firestore.test.js` has no `package.json` and can't run as shipped. Nothing covers playback, parsers, the merge, scrobbling, or backup.
- **M6. Every launch costs reads and writes.** `runBidirectionalMerge` fetches the full account, always rewrites the profile (L298) and taste seeds (L306), and `syncLikedSong` does a get before every update. Cost scales with library size on every launch.
- **M7. Room has no migration path.** `VelunaDatabase.kt` is version 1 with `exportSchema = false` and no migrations or fallback. The next schema change will break upgrades unless a migration is written.
- **M8. Listening data goes to undisclosed third parties.** `InnerTubeClient.kt` L573–579 uses community Piped and Invidious instances as fallback resolvers. Lyrics come from unofficial APIs (lrclib.net, lyricsplus.binimum.org, lyrics-api.boidu.dev, api-lyrics.simpmusic.org). Client identities are spoofed (YouTube VR, iOS Music, Firefox UA). The README discloses none of this.
- **M9. The repo contains another project's files.** `Cargo.lock` has no `Cargo.toml`. `ui/` has lockfiles with no `package.json` or source. `scripts/` covers AppImage, `.rpm`, Arch packaging, `rustypipe`, and a `velunahq/veluna` release flow, none of which relate to this app. `metadata.json` declares `SERVER_SIDE_GEMINI_API`, and the code has no Gemini usage.
- **M10. README claims that don't hold.**
  - "M3U8/CSV … import & export": CSV is import-only (`TrackDialogs.kt` L457, L480). Export is M3U only (`DetailScreens.kt` L430).
  - "Build locally with `gradle :app:assembleDebug`": `app/google-services.json` is not in the repo, and the google-services plugin needs it. A fresh clone would fail at build time. I didn't run the build, so this is from the plugin's requirement.
  - "Publishes a verified APK": the only checks are `unzip -tqq` and a `keytool` line ending in `|| true` (L114), so the second check can't fail.
  - "Release APK": it's a debug build (H1).
- **M11. Backup is partial, and restore is unsafe.** The payload (`VelunaRepository.kt` L57–64) omits the taste profile, saved albums, followed artists, shortcuts, listen history, and search history. `applyBackup` writes `cornerRadiusDp`, `crossfadeSecs` and `playbackSpeed` without the clamping the setters apply (`UserPrefsRepository.kt` L279, L283, L284). `createPlaylist` (L245) has no dedupe, so re-importing duplicates every playlist.

## Low

- **L1.** `FirestoreErrorHandler.kt` L51 logs the user's email, UID and provider emails at `Log.e` on every Firestore error, in every build.
- **L2.** `crossfadeSecs`, `translateLyrics` and `classicPlayerLayout` are stored and backed up, but nothing in the UI calls their setters (L194, L206, L238). The Translate icon in the lyrics panel toggles romanisation, not translation.
- **L3.** `VelunaPlaybackService.onServiceReady` (L95, L186) is never assigned, so it is dead code. The static `instance` and lambda hold references that can outlive the service. `WAKE_LOCK` is declared but unused, and `setWakeMode` is never called. `USE_BIOMETRIC` and `USE_FINGERPRINT` are merged from dependencies, and no code uses biometrics.
- **L4.** `PlayerController` runs a `while (true)` ticker every 250 ms even when nothing is playing.
- **L5.** `firestore.rules` L54 and L89 validate only the first element of `selectedGenres` and `tracks`. This only affects the owner's own data. `isValidServerTimestamp` accepts any past client timestamp, so the name overstates what it checks.
- **L6.** The CI fallback writes a different Firestore database ID (`…veluna-19b2b641…`) than the one committed (`…velunaan-1642b652…`). The fallback is only used if the committed file is missing. Every push to `main` overwrites the mutable `latest` release, so there is no versioned rollback.
- **L7.** Two screenshots show "App not installed as package conflicts with an existing package". That is Android's signature-mismatch error, the same failure that would occur if the CI signing key changed. The screenshots also live in the product repo and look like duplicates.
- **L8.** Accessibility and i18n: 89 hard-coded `Text("…")` literals against 24 string resources. 53 of 104 `Icon` calls set `contentDescription = null`, which may be fine for decorative icons, but each needs review. The onboarding section headings in the screenshot look very low-contrast.
- **L9.** Code size: `MainActivity.kt` is 1,124 lines, `SettingsScreen.kt` 971, and `NowPlayingSheet.kt` 963.
- **L10.** Stats loads the entire listen history into memory (`getAllListensAsc`, `getAllListenSongs`).
- **L11.** Dependencies are pinned to 2025-era versions (Compose BOM 2025.05.00, Media3 1.6.1, OkHttp 4.12.0, Room 2.7.1). I did not check current releases.

## What is fine

- Firestore rules are deny-by-default, owner-scoped, and type-checked per field, with immutable `createdAt`.
- Cleartext traffic is off, and there are no `http://` endpoints.
- No third-party secrets are hard-coded. Last.fm and ListenBrainz credentials are user-entered.
- Last.fm signing is correct: keys are sorted, and `format` and `api_sig` are excluded from the signed string.
- The shared OkHttp client has no interceptors or cookie jar, so YouTube headers don't leak to scrobbling endpoints.
- The two force-unwraps are guarded, the percentage maths is guarded, and the lyrics ratio can't divide by zero.
- Local-file pickers use SAF, and PendingIntents use `FLAG_IMMUTABLE`.

## Suggested order

1. C1 and H1: new release key in secrets, `assembleRelease` with minify, fail on missing key, add an `apksigner` check. Then rotate the signing identity and the Firebase Android client.
2. H2 and H5: move secrets to encrypted storage, add `dataExtractionRules`, redact the export, and add in-app account deletion.
3. H3 and H4: clear or key local data by UID on sign-out, and introduce tombstones in the merge.
4. M2 and M3: fix the scrobble threshold and check HTTP status with a retry queue.
5. M5: add CI steps for `:app:testDebugUnitTest` and the rules tests, with a `package.json`.
6. Clean up M9 and M10, and correct the README.
