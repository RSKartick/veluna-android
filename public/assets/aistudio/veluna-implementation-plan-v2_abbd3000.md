# Veluna Android: Implementation Plan v2

**Source:** Repo and APK audit of 15 Nov 2026 (`RSKartick/veluna-android`, HEAD `709a42e`; APK sha256 `c182f564…`).
**Replaces:** the v1 plan (`ee9328da-implementation_plan.md`), which is kept as background. This version fixes the problems found in review.

---

## 0. Decisions

### Confirmed

| ID | Decision | Note |
|---|---|---|
| D1 | Keep package ID `com.aistudio.veluna.mxkrtq`. Replace the signing identity. | **Has a residual risk. See D1a.** |
| D2 | Sign-out wipes all user-owned synced data, secrets and profile name. Keeps `local_tracks` and `downloaded_tracks`. | Flush pending sync before wiping (see Phase 3). |
| D3 | Remove `scripts/`, foreign lockfiles and duplicate screenshots. Keep `metadata.json`. | Verify AI Studio does not reference `public/assets/aistudio/*` first (D7). |
| D4 | Add CSV export, clarify romanization, wire or prune unused prefs. | Prune crossfade unless it is a priority (D4a). |
| D5 | Pin SDK levels and BOMs. Only take safe minor updates. | No major bumps in this plan. |

### Open: needs your sign-off before the relevant phase

- **D1a. Package ID.** Keeping the ID means every copy already installed still accepts updates signed with the compromised key, and anyone holding the public key can push one. Key rotation does not fix this, because the attacker holds the old key too. Only a new `applicationId` closes it. The cost is that users on the new key must uninstall and reinstall, which loses local data unless they export a backup first. **Recommendation: change the package ID in Phase 1, and keep the old package for one release with a notice.** If you keep the ID, record in the release notes that existing installs stay exposed.
- **D4a. Crossfade.** Real crossfade on a single ExoPlayer needs a second player or a volume ramp. **Recommendation: remove the pref and the field.**
- **D6. Third-party fallbacks.** Piped and Invidious instances (`InnerTubeClient.kt` L573–579) receive video IDs and user IPs. **Recommendation: keep them, but make them opt-in and disclose them in the README and settings.**
- **D7. AI Studio build path.** The plan assumes AI Studio preview works without the embedded `google-services.json` and keystore fallbacks. **Confirm how AI Studio builds the app before Phase 1 removes the fallbacks.**
- **D8. Web deletion page.** Google Play requires a web link for account deletion, as well as the in-app path. **You need to host a page.** It can be a static page on a domain you control.

---

## 1. Phases and Order

| Phase | Scope | Findings |
|---|---|---|
| 0 | Disclosure hygiene | C1, H1 |
| 1 | Release pipeline and build | C1, H1, L6, L11 |
| 2 | Credential storage and backup exclusion | H2, M11 (secrets part), L1 |
| 3 | Account lifecycle: sign-out isolation and deletion | H3, H5 |
| 4 | Sync engine: tombstones, local guard, honest status, efficiency | H4, M1, M4, M6, L5 |
| 5 | Playback and scrobbling | M2, M3, L3, L4 |
| 6 | Persistence and stats | M7, L10 |
| 7 | Export, backup v2, settings | M10, M11, L2 |
| 8 | UI structure, strings, accessibility | L8, L9 |
| 9 | Tests (written alongside each phase, gated in CI) | M5 |
| 10 | Repo cleanup and documentation | M8, M9, L7 |

Phases 0 to 3 are blockers. Do not publish the audit or any new APK until they are done.

---

## 2. Phase 0: Disclosure Hygiene

**Goal:** Stop the exposure before any fix ships.

1. Do not commit `veluna-android-audit-*.md` to the public repo. Keep it in the Veluna project until Phase 1 is merged and the key is rotated.
2. Revoke or rotate anything that depends on the public key:
   - Create a new Android OAuth client in Google Cloud for the new key and package.
   - Restrict the existing Firebase API key to the new package and SHA-1 once it exists.
3. Publish a short notice on the existing release page. It should say the old APK is signed with a public key and that users should install the new release when available.
4. Confirm D7 and D8 before continuing.

**Acceptance:** notice is live; the audit is not in the public repo; new OAuth client exists.

---

## 3. Phase 1: Release Pipeline and Build

**Goal:** A release can only be built with a private key, and the APK that ships is a minified release build.

### 1.1 Gradle

File: `app/build.gradle.kts`

```kotlin
// Signing reads from env vars. No keystore is committed.
signingConfigs {
    create("release") {
        val path = System.getenv("RELEASE_KEYSTORE_PATH")
        if (path != null) {
            storeFile = file(path)
            storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("RELEASE_KEY_ALIAS")
            keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
        }
    }
}
buildTypes {
    release {
        isMinifyEnabled = true
        isShrinkResources = true
        proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        signingConfig = signingConfigs.getByName("release")
    }
    debug {
        // Local dev uses the default Android Studio debug keystore. Nothing is committed.
    }
}
```

Other changes:

- Remove `debug.keystore` handling from the build and from CI.
- Keep `applicationId`, or change it per D1a.
- Commit the Gradle wrapper: `gradle wrapper --gradle-version 9.3.1`. Commit `gradlew`, `gradle/wrapper/*`.
- Delete the `debugConfig` signing config.

### 1.2 ProGuard / R8 keep rules

File: `app/proguard-rules.pro`

Add explicit keep rules. Do not rely on "Firebase ships rules":

```
# Firestore maps fields by name. R8 must not rename or strip these.
-keep class com.example.data.cloud.Cloud** { *; }
-keepclassmembers class com.example.data.cloud.Cloud** { <init>(); }
-keep class com.example.model.** { *; }   # kotlinx-serialization models
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
```

Test: a release build must complete a full sync on a physical device (see Phase 9).

### 1.3 CI workflow

File: `.github/workflows/release-apk.yml`. Replace the file.

Split into three jobs:

```yaml
jobs:
  test:            # runs on every PR and push, needs no secrets
    steps:
      - run: npm ci
      - run: npx firebase emulators:exec --only firestore,auth "node --test firestore.test.js"
      - run: ./gradlew :app:testDebugUnitTest
        # Robolectric + emulator tests run inside emulators:exec as well.

  build-release:   # only on tags v*, needs secrets
    needs: test
    steps:
      - name: Require secrets
        run: |
          : "${{ secrets.ANDROID_RELEASE_KEYSTORE_BASE64 }}" || exit 1
          [ -n "${{ secrets.ANDROID_RELEASE_KEYSTORE_BASE64 }}" ] || { echo "missing keystore"; exit 1; }
          [ -n "${{ secrets.GOOGLE_SERVICES_JSON }}" ] || { echo "missing google-services.json"; exit 1; }
      - run: echo "$GOOGLE_SERVICES_JSON" > app/google-services.json
      - run: echo "$KS_B64" | base64 -d > "$RUNNER_TEMP/release.jks"
      - run: ./gradlew :app:assembleRelease
      - name: Verify signature
        run: |
          APK=$(find app/build/outputs/apk/release -name "*.apk" | head -1)
          apksigner verify --print-certs "$APK" | tee /dev/stderr | grep -q "$EXPECTED_SHA256" \
            || { echo "signer mismatch"; exit 1; }
        env:
          EXPECTED_SHA256: ${{ vars.RELEASE_CERT_SHA256 }}   # a repo variable, not a secret
      - uses: softprops/action-gh-release@v2
        with:
          tag_name: ${{ github.ref_name }}   # immutable; created per tag
          files: ${{ steps.apk.outputs.path }}
```

Rules:

- Fail fast if a secret is missing. Never fall back to a committed key.
- Fork PRs get the `test` job only.
- No release from a branch push. Only `v*` tags publish. Remove the mutable `latest` release.
- `apksigner verify` must fail the job on a mismatch. The current `|| true` must go.

### 1.4 Dependencies (D5)

- Keep current BOMs and SDK levels.
- Run a dependency check. Take minor and patch updates only, and only where the build stays green.

### Acceptance

- A PR with no secrets runs tests and builds a debug APK.
- A tag with missing secrets fails before building.
- `apksigner verify` output matches `RELEASE_CERT_SHA256`.
- The release APK has `debuggable` absent or `false`, and is smaller than the current 29.9 MB.
- Old keystore and `google-services.json` are gone from the repo and from git history (use `git filter-repo` for the two base64 blobs).

---

## 4. Phase 2: Credential Storage and Backup Exclusion

**Goal:** Scrobble secrets are encrypted, excluded from cloud backup, and never exported.

### 2.1 SecretVault

File: new `app/src/main/java/com/example/data/security/SecretVault.kt`

No `security-crypto`. Its 1.1.0 release notes say APIs are deprecated in favour of direct Keystore use. Use the Keystore directly.

```
class SecretVault(context):
    keyAlias = "veluna_secret_key_v1"
    file     = context.noBackupFilesDir / "secrets.bin"   # excluded from backup by platform

    fun key():
        if not keystore.containsAlias(keyAlias):
            KeyGenerator("AES", "AndroidKeyStore").init(
                KeyGenParameterSpec(keyAlias, ENCRYPT|DECRYPT)
                    .setBlockModes(GCM).setEncryptionPaddings(NoPadding)
                    .setKeySize(256))
        return keystore.getKey(keyAlias)

    fun put(name, value):    # AES/GCM/NoPadding, random 12-byte IV, store iv || ciphertext
    fun get(name) -> String?
    fun delete(name)
    fun wipeAll()            # delete file and key
```

Notes:

- `noBackupFilesDir` is excluded from Auto Backup and device transfer by the platform. Rules in 2.2 are a second layer.
- Hardware-backed keys are not restored on a new device. Restored installs get an empty vault, which is the correct outcome.

### 2.2 Backup exclusion rules

Files: new `app/src/main/res/xml/data_extraction_rules.xml` (API 31+) and `app/src/main/res/xml/backup_rules.xml` (API 26–30).

```xml
<!-- data_extraction_rules.xml -->
<data-extraction-rules>
  <cloud-backup>
    <exclude domain="sharedpref" path="."/>
    <exclude domain="file" path="datastore/veluna_prefs.preferences_pb"/>
  </cloud-backup>
  <device-transfer>
    <exclude domain="file" path="datastore/veluna_prefs.preferences_pb"/>
  </device-transfer>
</data-extraction-rules>
```

Manifest: `android:fullBackupContent="@xml/backup_rules"` and `android:dataExtractionRules="@xml/data_extraction_rules"`.

Decision point: excluding the whole DataStore file also drops theme and EQ settings from cloud backup. That is acceptable, since settings are cloud-synced already. Confirm.

### 2.3 Migration out of plaintext DataStore

Run once in `Application.onCreate`, guarded by a `secrets_migrated` flag:

```
for name in [LASTFM_SECRET, LASTFM_SESSION, LISTENBRAINZ_TOKEN, LASTFM_KEY]:
    v = datastore.read(name)
    if v not blank: vault.put(name, v)
    datastore.remove(name)
flag = true
```

`UserPrefsRepository` reads secrets from `SecretVault`, not DataStore. Keep `lastFmUsername` and `scrobblingEnabled` in DataStore.

### 2.4 Redacted export

`safeExportBackupJson` must strip every scrobble field. Rename it to `exportBackupJson`, since "safe" was the misleading part.

```
fun exportBackupJson(prefs):
    p = prefs.copy(lastFmApiKey="", lastFmApiSecret="", lastFmSessionKey="", listenBrainzToken="")
    return json.encode(payload(prefs = p, ...))
```

Old v1 backup files may contain secrets. Tell users to delete them. Add a banner on the restore screen for v1 files.

### 2.5 Logging (L1)

`FirestoreErrorHandler`: remove email and provider metadata. Keep the UID hash, the operation, the path and the error code, and only in debug builds.

### Acceptance

- Export file contains zero occurrences of the secret strings (unit test).
- After migration, DataStore file has no scrobble keys (test).
- `adb backup` and Auto Backup do not include `secrets.bin` (manual check).
- Logs contain no email addresses (grep in a debug run).

---

## 5. Phase 3: Account Lifecycle

**Goal:** Sign-out leaves no trace of the previous account. The user can delete their account in-app.

### 3.1 Sign-out isolation (H3)

```
fun signOut():
    flushPendingSync(timeout = 10s)
    if unsyncedCount > 0 and not confirmedByUser:
        ask "N changes have not synced and will be lost. Sign out anyway?"
    stopWatchers()
    db.withTransaction {
        for t in [liked_songs, local_playlists, local_playlist_tracks,
                  taste_seeds, taste_profile, blocked_artists, shortcuts,
                  listens, listen_songs, search_history,
                  sync_tombstones, pending_scrobbles, saved_albums, followed_artists]:
            t.deleteAll()
    }
    vault.wipeAll()
    prefs.resetUserKeys()          # display name, onboarding, scrobble config
    // KEEP: local_tracks, downloaded_tracks, offline files, theme/EQ prefs
    firebaseAuth.signOut()
    credentialManager.clearCredentialState()
```

`resetUserKeys()` must list each key explicitly. Do not wipe all of DataStore.

Note: `saved_albums` and `followed_artists` are not in the v1 plan's list. Add them, because they are account data.

### 3.2 Account deletion (H5, D8)

UI: "Delete Account & Cloud Data" in the Profile & Cloud Sync card. Two-step dialog. Explain what is erased and what stays on the device.

Order matters. Re-authenticate before deleting anything, so a failed re-auth never leaves half-deleted data.

```
fun deleteAccount():
    user = firebaseAuth.currentUser ?: return
    // 1. Re-auth first. Firebase requires a recent sign-in for user.delete().
    if now - user.lastSignInAt > 5 min:
        cred = googleCredentialManager.getCredential(...)        # interactive
        user.reauthenticate(cred)
    // 2. Delete cloud data. Idempotent: safe to retry.
    for sub in [taste_seeds, liked_songs, playlists, hidden_artists]:
        deleteAllInBatches(users/{uid}/sub, batch = 400)
    for pl in playlists: deleteAllInBatches(pl/tracks, batch = 400)   # after Phase 4 moves tracks
    delete(users/{uid})
    // 3. Delete auth user. If this fails, show Retry. Data is already gone.
    user.delete()
    // 4. Local wipe, same as sign-out, without the sync flush.
    localWipe()
    navigate(signedOut)
```

Also add a web deletion link in the Play listing. Google Play requires an in-app path and a web link that references the app or developer name.

Handle these errors explicitly:

- `FirebaseAuthRecentLoginRequiredException`: re-auth, then retry from step 2.
- `FirebaseFirestoreException` `PERMISSION_DENIED`: show an error, do not delete the Auth user.
- Network failure during batches: show progress and resume on next attempt.

### Acceptance

- Deleting an account removes every Firestore document under `users/{uid}` (emulator test).
- Auth user is gone and sign-in fails afterward (emulator test).
- Local liked songs, playlists and secrets are gone; downloaded files remain (instrumented test).
- Signing in as account B after account A sees zero data from A (test: Phase 4 merge).

---

## 6. Phase 4: Sync Engine

**Goal:** Deletions propagate. Accounts stay separate. Status is honest. Launches are cheap.

### 4.1 Tombstones (H4)

Room table (added in Phase 6, schema v2):

```
sync_tombstones(
  entityType TEXT,    -- LIKED | PLAYLIST | HIDDEN_ARTIST | SEED
  entityKey  TEXT,
  clientUpdatedAt INTEGER,
  deletedAt  INTEGER,
  PRIMARY KEY(entityType, entityKey))
```

Firestore document shape (all three collections): add `deleted: bool`, `clientUpdatedAt: int`. Deletion writes `{deleted: true, clientUpdatedAt, updatedAt: serverTimestamp}` rather than deleting the doc. Tombstone docs are purged by a client sweep after 30 days.

Rules: add `deleted` to each `hasOnly` list and allow `deleted == true` documents with a reduced key set.

### 4.2 Merge (last-write-wins per item)

```
fun mergeCollection(local: Map<key, Row>, cloud: Map<key, Doc>):
    for key in union(local.keys, cloud.keys):
        l = local[key]; c = cloud[key]
        winner = maxBy(clientUpdatedAt, [l, c].filterNotNull())
        if winner.deleted:
            localDelete(key); cloudTombstone(key, winner.clientUpdatedAt)
        else:
            localUpsert(key, winner)   if winner is c and differs
            cloudUpsert(key, winner)   if winner is l and differs
```

Known limitation: `clientUpdatedAt` comes from device clocks. A device with a clock far behind can lose writes. Document this. Option: use server timestamps for conflicts (costs one extra round trip per write).

Known limitation: a device offline for more than 30 days can resurrect items whose tombstones were purged. Document this in the README.

### 4.3 Taste seeds (H4)

- Write `[]` explicitly when the list is empty. Delete the `if (isNotEmpty)` guard at `CloudSyncCoordinator.kt` L305.
- Stop hashing seed IDs twice. Use the stored seed ID as the document key.

### 4.4 Local-file guard (M1)

- Track menu "Add to Liked Music" and the Now Playing like button: hide when `song.isLocal`.
- `CloudSyncRepository.syncLikedSong`: `if (song.isLocal) return`. Same for playlists and blocked artists.
- Add a data migration to remove any `LOCAL:` rows from `liked_songs` on first run, and from Firestore.

### 4.5 Honest status (M4)

Replace every `runCatching { … }` in the watchers with `Result` handling:

```
result = runCatching { push(...) }
result.onFailure { status = SyncError(message = it.userMessage(), retryAt = now+backoff) }
if all succeeded: status = UpToDate(lastSyncedAt = now)
```

Remove the unconditional `"Cloud sync up to date"` assignments at `CloudSyncCoordinator.kt` L575, L628, L661.

UI states: `Syncing…`, `Up to date at HH:mm`, `Sync failed: <message>. Retry`.

### 4.6 Efficiency (M6)

- Keep `profileHash` and `seedsHash` in DataStore, computed from the last pushed value.
- Push the profile and seeds only when the local hash differs from the stored one.
- Replace the `doc.get()` before `update` in `syncLikedSong` with `set(..., SetOptions.merge())` plus `FieldValue.serverTimestamp()`. Use `createdAt` only on first write, with a rule that allows it.
- On cold start, query `where updatedAt > watermark` per collection, and store the watermark after each successful pull.

### 4.7 Firestore rules (L5)

The v1 plan asked for element-by-element validation of arrays. **Rules cannot iterate list elements.** The `List` members are `concat`, `hasAll`, `hasAny`, `hasOnly`, `join`, `removeAll`, `size` and `toSet`. Verified against the rules reference.

Choose one:

- **A (recommended):** move playlist tracks into a subcollection, `users/{uid}/playlists/{key}/tracks/{videoId}`. Each track is a document, so the rules check each one. This also removes the array size limit. Migration: on first run, copy `tracks` arrays into the subcollection, then delete the array field.
- **B:** keep the array, enforce a maximum length, and validate element shape in the client only. Owner-only data, so the risk is low. Document the limit.

Also:

- Rename `isValidServerTimestamp` to `isNotFutureTimestamp`. It only checks `ts <= request.time`, so clients can backdate timestamps. Say so in a comment.

### Acceptance

- Unlike on device A is still unliked after device B syncs (two-device test).
- Deleted playlist stays deleted after the other device syncs.
- Cleared seeds stay cleared.
- Signing in as account B shows none of account A's data.
- Local-file like never reaches Firestore (rules test: write attempt from client is blocked or never sent).
- A failed push shows an error state and retries.
- Second cold start issues zero writes when nothing changed (count writes in emulator).

---

## 7. Phase 5: Playback and Scrobbling

**Goal:** Scrobbles follow the Last.fm spec. Failures are retried, not dropped.

### 5.1 Trigger (M2)

Last.fm rule: the track is longer than 30 seconds, and it has been played for at least half its duration or 4 minutes, whichever comes first.

```
// PlayerController
if not scrobbled
   and track.durationSecs > 30
   and heardSecs >= min(track.durationSecs / 2, 240):
        scrobbled = true
        pendingScrobbles.enqueue(track, startedAt)
        flushScrobbles()
```

- Skip tracks with unknown duration (`durationSecs == 0`). Last.fm needs the duration.
- Test: 25 s track never scrobbles; 31 s track scrobbles at 15.5 s; 300 s track scrobbles at 150 s; 600 s track at 240 s.

### 5.2 Response handling (M3)

```
fun submit(req): Result
    resp = http.execute(req)
    if not resp.isSuccessful: return Failure(retryable = resp.code >= 500 or resp.code == 429)
    body = json.parse(resp)
    if body.has("error"):
        code = body.error
        return when code:
            9, 14, 26 -> Failure(retryable = false)       # invalid session / auth; surface to user
            11, 16, 29 -> Failure(retryable = true)       # service offline / temp / rate limit
            else -> Failure(retryable = false)
    return Success
```

Verify the Last.fm error code meanings against the API docs before merging. The codes above are from memory.

### 5.3 Retry queue (M3)

Room table `pending_scrobbles(id, service, payloadJson, attempts, nextAttemptAt, lastError)`.

```
fun flushScrobbles():
    for row in pending where nextAttemptAt <= now order by id:
        r = submit(row)
        if r.ok: delete(row)
        elif not r.retryable or row.attempts >= 8: delete(row); surfaceError(r)
        else: row.attempts++; row.nextAttemptAt = now + backoff(attempts)
```

Triggers: after each enqueue, on `ConnectivityManager.NetworkCallback.onAvailable`, and on playback start.

Age limit: Last.fm rejects scrobbles older than a certain window. **Look up the limit before setting the cap** and drop anything older.

Show status in Settings: "N scrobbles pending" and "Last scrobble failed: <reason>".

### 5.4 Credentials UI

- Mask the API secret, session key and ListenBrainz token with `PasswordVisualTransformation`.
- Read them from `SecretVault` (Phase 2).

### 5.5 Playback service (L3)

- Remove `onServiceReady` (`VelunaPlaybackService.kt` L95, L186).
- Keep `WAKE_LOCK` and call `setWakeMode(C.WAKE_MODE_NETWORK)` on the player. **The v1 plan proposed removing the permission, which would conflict with this.** I could not confirm the Media3 permission requirement from the docs I could fetch, so test streaming with the screen off on a real device.
- Remove `USE_BIOMETRIC` and `USE_FINGERPRINT` with `tools:node="remove"`. Then sign in with Google on a device, because they are merged from `androidx.biometric` and removing them could break a credential path.

### 5.6 Ticker (L4)

Run the 250 ms position ticker only while `isPlaying` or the Now Playing sheet is open. Stop it otherwise.

```
ticker = combine(isPlaying, nowPlayingOpen, buffering)
    .distinctUntilChanged()
    .collectLatest { active -> if active: tick every 250ms else: idle }
```

### Acceptance

- Scrobble timing tests pass (5.1).
- Offline scrobble persists across process restart and is sent once back online.
- Invalid session code surfaces to the user and does not retry.
- Idle CPU check: no wakeups while paused with sheet closed (Android Studio profiler).

---

## 8. Phase 6: Persistence and Stats

**Goal:** Room has a real migration path. Stats do not load the whole history.

### 6.1 Room v2 (M7)

1. Before any schema change, build the current commit with `exportSchema = true` and commit `app/schemas/1.json`. This is the baseline for `MigrationTestHelper`.
2. Set `ksp { arg("room.schemaLocation", "$projectDir/schemas") }`.
3. Bump to version 2. Add `sync_tombstones` and `pending_scrobbles`.
4. Write `Migration(1, 2)`: create the two tables. Do not touch existing data.
5. Test: `MigrationTestHelper` runs 1 → 2 and compares against `schemas/2.json`.

Do not use `fallbackToDestructiveMigration`. Losing a user's library on update is worse than a crash.

### 6.2 Stats queries (L10)

Replace `getAllListensAsc()` and `getAllListenSongs()` for ranged views:

```kotlin
@Query("SELECT * FROM listens WHERE playedAt >= :sinceMs ORDER BY playedAt")
suspend fun getListensSince(sinceMs: Long): List<ListenEntity>

@Query("SELECT videoId, COUNT(*) plays, SUM(secs) secs FROM listens WHERE playedAt >= :sinceMs GROUP BY videoId ORDER BY secs DESC LIMIT :limit")
suspend fun topSongs(sinceMs: Long, limit: Int): List<TopSongRow>
```

Load full history only for All-Time, and aggregate in SQL rather than in memory.

### Acceptance

- Migration test passes.
- Existing user data survives an upgrade from v1 build (test with a seeded v1 DB).
- Stats for 30-day range does not load rows outside the window (query-count test).

---

## 9. Phase 7: Export, Backup v2, Settings

**Goal:** Backups are complete, safe to restore, and every setting does something.

### 7.1 CSV export (M10)

```
fun exportPlaylistAsCsv(playlist) -> String:
    header = "title,artists,album,duration_secs,video_id"
    rows = tracks.map { csvEscape(it.title), csvEscape(it.artists), ... }
    return header + "\n" + rows.join("\n")

fun csvEscape(s): if s has , " or newline: return "\"" + s.replace("\"", "\"\"") + "\""; else s
```

- Add "Export CSV" next to "Export M3U8" in the playlist overflow menu.
- Use `ActivityResultContracts.CreateDocument("text/csv")`.
- Round-trip test: export, then import with the existing Exportify-style importer, and compare.

### 7.2 Backup v2 (M11)

- Bump `VelunaBackupPayload.version` to 2.
- Add: `tasteProfile`, `savedAlbums`, `followedArtists`, `shortcuts`, `searchHistory` (last 100), `listens` (last 90 days).
- Exclude every scrobble secret (Phase 2.4).
- Keep v1 import working. On v1 import, map fields that exist and skip the rest.

Restore:

```
fun applyBackup(b):
    prefs.cornerRadiusDp  = b.cornerRadiusDp.coerceIn(4, 24)
    prefs.crossfadeSecs   = b.crossfadeSecs.coerceIn(0, 12)   // only if D4a keeps crossfade
    prefs.playbackSpeed   = b.playbackSpeed.coerceIn(0.5f, 2.0f)
    for pl in b.playlists:
        existing = findPlaylist(normalize(pl.title), signature(pl.tracks))
        if existing == null: create(pl)
    // Liked songs, blocked artists and the rest use upsert keyed by id, so they are idempotent.
```

Signature: sorted list of videoIds, hashed. Normalization: trim, lowercase, collapse whitespace.

Make restore transactional: read the whole payload, validate, then write in one Room transaction.

### 7.3 Settings (L2)

| Pref | Action |
|---|---|
| `translateLyrics` | Remove. Rename the Translate icon to "Romanize lyrics" with a tooltip. |
| `classicPlayerLayout` | Wire it: a Settings toggle that switches the Now Playing layout. |
| `crossfadeSecs` | Remove, per D4a. |

Remove the setters and backup fields for anything removed. Add a migration that drops the DataStore keys.

### Acceptance

- CSV round-trip test passes.
- Backup v2 round-trip restores every listed table.
- v1 backup still imports.
- Restoring the same backup twice creates no duplicate playlists.
- Out-of-range values in a hand-edited backup are clamped (unit test).
- Every pref listed in `UserPrefsRepository` has a UI setter or a backup path that uses it (a lint-style test that greps for each key).

---

## 10. Phase 8: UI Structure, Strings, Accessibility

**Goal:** Files under about 500 lines, strings ready for translation, meaningful labels.

### 8.1 Split large files (L9)

| File | Lines | Split into |
|---|---|---|
| `MainActivity.kt` | 1124 | `AppNavHost.kt`, `AppScaffold.kt`, `AppPermissions.kt`, `MainActivity.kt` (under 200) |
| `SettingsScreen.kt` | 971 | `settings/AccountSection.kt`, `settings/PlaybackSection.kt`, `settings/ScrobblingSection.kt`, `settings/BackupSection.kt`, `settings/AppearanceSection.kt` |
| `NowPlayingSheet.kt` | 963 | `nowplaying/Controls.kt`, `nowplaying/QueueList.kt`, `nowplaying/Artwork.kt` |

Refactor only. No behaviour change. Keep the existing `testTag` values so UI tests still find the elements.

### 8.2 Strings (L8)

- Move the 89 hard-coded `Text("…")` literals into `res/values/strings.xml`.
- Add a lint check: `HardcodedText` as an error for `ui/`.
- Localization itself is out of scope. This phase only makes it possible.

### 8.3 Accessibility (L8)

- Audit the 53 `Icon(..., contentDescription = null)` calls. For each one:
  - Decorative and next to a text label: keep `null`.
  - Interactive or informational with no text: give it a label.
- Check contrast on the taste onboarding section headings against the artwork backdrop. Target WCAG AA (4.5:1 for body text, 3:1 for large text). Use a scrim if needed.

### Acceptance

- No Kotlin file in `ui/` or `MainActivity.kt` exceeds 500 lines.
- Lint passes with `HardcodedText` as an error.
- TalkBack walkthrough of Home, Settings and Now Playing reads every control.
- Contrast check passes on onboarding headings in light and dark artwork cases.

---

## 11. Phase 9: Tests (M5)

Write tests alongside each phase, not at the end. CI gates on them (Phase 1.3).

| Area | Test type | Covers |
|---|---|---|
| Firestore rules | Node, emulator (`node --test`) | owner-only access, tombstone shapes, list size limits, account deletion writes |
| Cloud sync repository | Robolectric + emulator (`CloudSyncRepositoryRuleTest`) | tombstone writes, deletions, empty seeds, account deletion |
| Merge | JVM unit | LWW per item, tombstone winners, two-account isolation |
| Scrobbling | JVM unit | threshold table (5.1), error-code mapping (5.2), retry backoff (5.3) |
| Backup | JVM unit | redaction, clamping, playlist dedupe, v1 import |
| Export | JVM unit | CSV escaping, M3U8 output, round-trip |
| Secrets | Robolectric | vault put/get/wipe, migration out of DataStore |
| Room | Instrumented | migration 1 → 2 with `MigrationTestHelper` |
| Playback | Instrumented on device | streaming with screen off, release build sync |

Release-build smoke test: install the signed release APK on a physical device, sign in, make a like, unlike it on a second device, and check both.

---

## 12. Phase 10: Repo Cleanup and Documentation

### 10.1 Remove (D3, M9, L7)

- `scripts/` (all files).
- `Cargo.lock`.
- `ui/package-lock.json` and `ui/pnpm-lock.yaml`.
- `public/assets/aistudio/*.png` and `*.jpg`, after D7 confirms AI Studio does not reference them.
- Keep `metadata.json` and `firebase-blueprint.json`. Add a note in the README that `metadata.json` is required by the AI Studio platform.

### 10.2 Add

- `package.json` at the root, with `@firebase/rules-unit-testing` and `firebase-tools` as devDependencies, and a `test` script.
- Gradle wrapper (1.1).
- `RELEASE.md`: how to cut a tagged release, how to verify the signing certificate, and the rotation procedure.

### 10.3 README rewrite (M8, M10)

Must state:

- **Build:** Gradle wrapper, `google-services.json` requirement, how to get a Firebase project, and that release builds need the release keystore. Local builds use the debug keystore.
- **Third-party services:** InnerTube (YouTube Music), Piped and Invidious instances (D6), lrclib.net, lyricsplus.binimum.org, lyrics-api.boidu.dev, api-lyrics.simpmusic.org, ws.audioscrobbler.com, api.listenbrainz.org, Firebase.
- **Data:** what is stored in Firestore, what stays on the device, what the backup contains and excludes.
- **Deletion:** in-app path and the web link (D8).
- **Export/import:** M3U8 and CSV export; M3U8, CSV (Exportify) and YouTube playlist import.
- **Tombstone limitation** from 4.2.
- **Signing:** the release certificate SHA-256, and how to verify an APK.

Do not claim "verified" unless the workflow checks it.

---

## 13. Audit Coverage Map

| Finding | Phase | Status in plan |
|---|---|---|
| C1 public signing key | 0, 1 | Fixed. Residual risk on existing installs unless D1a is accepted as the new package. |
| H1 debug build shipped | 1 | Fixed |
| H2 secrets in DataStore, backup, export | 2 | Fixed |
| H3 sign-out keeps data | 3 | Fixed |
| H4 sync resurrects deletions | 4 | Fixed, with clock and 30-day limits documented |
| H5 no account deletion | 3 | Fixed, with web link (D8) |
| M1 local-file likes sync | 4 | Fixed |
| M2 scrobble threshold | 5 | Fixed |
| M3 scrobble failures silent | 5 | Fixed |
| M4 false "up to date" | 4 | Fixed |
| M5 tests thin and not in CI | 9, 1 | Fixed |
| M6 launch cost | 4 | Fixed |
| M7 no Room migrations | 6 | Fixed |
| M8 undisclosed third parties | 10, D6 | Documented; fallbacks become opt-in if D6 accepted |
| M9 foreign files in repo | 10 | Fixed |
| M10 README overclaims, no CSV export | 7, 10 | Fixed |
| M11 backup partial, unsafe restore | 2, 7 | Fixed |
| L1 PII in logs | 2 | Fixed |
| L2 dead settings | 7 | Fixed |
| L3 dead service code, permissions | 5 | Fixed. `WAKE_LOCK` kept per 5.5. |
| L4 idle ticker | 5 | Fixed |
| L5 rules element checks | 4 | Reworded: subcollection (4.7 option A) |
| L6 CI fallbacks, mutable release | 1 | Fixed |
| L7 screenshot error dialogs | 10 | Fixed |
| L8 strings, labels, contrast | 8 | Fixed |
| L9 large files | 8 | Fixed |
| L10 stats load full history | 6 | Fixed |
| L11 dependency pins | 1 | Deferred per D5. Minor updates only. |

---

## 14. Open Questions Summary

1. **D1a:** change package ID, or accept residual risk on existing installs?
2. **D4a:** remove crossfade?
3. **D6:** keep Piped and Invidious fallbacks, opt-in or removed?
4. **D7:** how does AI Studio build the app, and does it need the embedded config or the screenshots?
5. **D8:** where will the web deletion page be hosted?
6. **4.7:** playlist tracks as a subcollection (recommended) or a length-capped array?
7. **4.2:** keep device-clock LWW, or use server timestamps?
8. **Audit disclosure:** confirm the audit stays out of the public repo until Phase 1 ships.

---

## 15. Verification Notes

Checked against primary sources:

- Firestore `List` members (no element iteration).
- `androidx.security:security-crypto` 1.1.0 release notes (deprecation of APIs in favour of Keystore).
- Google Play account-deletion policy (in-app path and web link).
- Auto Backup defaults (shared prefs, files, databases).
- Last.fm scrobble rule (over 30 s; half duration or 4 min, whichever first).

**Not verified, must be checked before implementation:**

- Media3 `WAKE_LOCK` requirement for `setWakeMode` (5.5).
- Last.fm error code meanings and the scrobble age limit (5.2, 5.3).
- R8 effect on Firestore `toObject` mapping (1.2). Test on a release build.
- How AI Studio builds the app (D7).
