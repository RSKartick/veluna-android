package com.example.data.cloud

import android.content.Context
import com.example.R
import com.example.data.local.SyncTombstoneEntity
import com.example.data.local.VelunaUserPrefs
import com.example.model.LOCAL_SONG_PREFIX
import com.example.model.SongItem
import com.example.model.TasteProfile
import com.example.model.TasteSeed
import com.example.util.SecurityValidators
import com.google.firebase.Firebase
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.QuerySnapshot
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import java.security.MessageDigest
import java.util.Collections
import java.util.Date
import java.util.UUID

class StalePlaylistRevisionException(
    val remoteRevision: Long,
    val attemptedRevision: Long
) : IllegalStateException(
    "Remote playlist revision $remoteRevision supersedes local revision $attemptedRevision"
)

data class SyncWatermarks(
    val likedMs: Long = 0L,
    val playlistsMs: Long = 0L,
    val seedsMs: Long = 0L,
    val hiddenMs: Long = 0L
)

class CloudSyncRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth
) {
    internal var onBeforeCommitPlaylistSnapshot: (suspend (snapshotId: String, chunkIndex: Int) -> Unit)? = null
    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        ),
        Firebase.auth
    )

    // Cache document paths confirmed to exist on server to avoid pre-read doc.get() round trips
    private val knownExistingPaths: MutableSet<String> = Collections.synchronizedSet(HashSet())

    fun clearSessionCache() {
        knownExistingPaths.clear()
    }

    private fun requireUserId(): String {
        return auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in with Google before accessing Firestore.")
    }

    fun observeUserProfile(userId: String): Flow<CloudUserProfile?> = flow {
        val docRef = db.collection("users").document(userId)
        emitAll(
            docRef.snapshots()
                .map { it.toObject(CloudUserProfile::class.java) }
                .catch { error ->
                    val firestoreError = unwrapFirestoreError(error)
                    if (firestoreError == null && error is CancellationException) throw error
                    if (auth.currentUser?.uid == userId) {
                        handleFirestoreError(firestoreError ?: error, OperationType.GET, docRef.path)
                    }
                    throw error
                }
        )
    }

    fun observeLikedSongs(userId: String): Flow<List<CloudLikedSong>> = flow {
        val colRef = db.collection("users").document(userId).collection("liked_songs")
        emitAll(
            colRef.snapshots()
                .map { snap -> snap.toObjects(CloudLikedSong::class.java) }
                .catch { error ->
                    val firestoreError = unwrapFirestoreError(error)
                    if (firestoreError == null && error is CancellationException) throw error
                    if (auth.currentUser?.uid == userId) {
                        handleFirestoreError(firestoreError ?: error, OperationType.LIST, colRef.path)
                    }
                    throw error
                }
        )
    }

    fun observePlaylists(userId: String): Flow<List<CloudPlaylist>> = flow {
        val colRef = db.collection("users").document(userId).collection("playlists")
        emitAll(
            colRef.snapshots()
                .map { snap -> snap.toObjects(CloudPlaylist::class.java) }
                .catch { error ->
                    val firestoreError = unwrapFirestoreError(error)
                    if (firestoreError == null && error is CancellationException) throw error
                    if (auth.currentUser?.uid == userId) {
                        handleFirestoreError(firestoreError ?: error, OperationType.LIST, colRef.path)
                    }
                    throw error
                }
        )
    }

    fun observeTasteSeeds(userId: String): Flow<List<CloudTasteSeed>> = flow {
        val colRef = db.collection("users").document(userId).collection("taste_seeds")
        emitAll(
            colRef.snapshots()
                .map { snap -> snap.toObjects(CloudTasteSeed::class.java) }
                .catch { error ->
                    val firestoreError = unwrapFirestoreError(error)
                    if (firestoreError == null && error is CancellationException) throw error
                    if (auth.currentUser?.uid == userId) {
                        handleFirestoreError(firestoreError ?: error, OperationType.LIST, colRef.path)
                    }
                    throw error
                }
        )
    }

    fun observeHiddenArtists(userId: String): Flow<List<CloudHiddenArtist>> = flow {
        val colRef = db.collection("users").document(userId).collection("hidden_artists")
        emitAll(
            colRef.snapshots()
                .map { snap -> snap.toObjects(CloudHiddenArtist::class.java) }
                .catch { error ->
                    val firestoreError = unwrapFirestoreError(error)
                    if (firestoreError == null && error is CancellationException) throw error
                    if (auth.currentUser?.uid == userId) {
                        handleFirestoreError(firestoreError ?: error, OperationType.LIST, colRef.path)
                    }
                    throw error
                }
        )
    }

    suspend fun getUserProfile(): CloudUserProfile? {
        val uid = requireUserId()
        val docRef = db.collection("users").document(uid)
        return try {
            val snap = docRef.get().await()
            if (snap.exists()) knownExistingPaths.add(docRef.path)
            snap.toObject(CloudUserProfile::class.java)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, docRef.path)
            throw e
        }
    }

    suspend fun fetchAccountSnapshot(watermarks: SyncWatermarks = SyncWatermarks()): CloudAccountSnapshot {
        val uid = requireUserId()
        val userDoc = db.collection("users").document(uid)
        return try {
            val profileSnap = userDoc.get().await()
            if (profileSnap.exists()) knownExistingPaths.add(userDoc.path)
            val profile = profileSnap.toObject(CloudUserProfile::class.java)

            val seedsSnap = queryCollectionSince(userDoc.collection("taste_seeds"), watermarks.seedsMs)
            seedsSnap.documents.forEach { knownExistingPaths.add(it.reference.path) }
            val seeds = seedsSnap.toObjects(CloudTasteSeed::class.java)

            val likedSnap = queryCollectionSince(userDoc.collection("liked_songs"), watermarks.likedMs)
            likedSnap.documents.forEach { knownExistingPaths.add(it.reference.path) }
            val liked = likedSnap.toObjects(CloudLikedSong::class.java)

            val playlistsSnap = queryCollectionSince(userDoc.collection("playlists"), watermarks.playlistsMs)
            playlistsSnap.documents.forEach { knownExistingPaths.add(it.reference.path) }
            val playlistDocs = playlistsSnap.toObjects(CloudPlaylist::class.java)

            val playlistsWithTracks = playlistDocs.map { pl ->
                val subTracks = if (!pl.deleted) {
                    val plRef = userDoc.collection("playlists").document(pl.playlistKey)
                    val activeSnapId = pl.activeSnapshotId?.trim()?.takeIf { it.isNotEmpty() }
                    val snapTracks = if (activeSnapId != null) {
                        val snapRef = plRef.collection("snapshots").document(activeSnapId)
                        val trSnap = snapRef.collection("tracks").get().await()
                        trSnap.documents.forEach { knownExistingPaths.add(it.reference.path) }
                        trSnap.toObjects(CloudPlaylistTrack::class.java)
                    } else {
                        emptyList()
                    }
                    if (snapTracks.isNotEmpty() || (activeSnapId != null && pl.trackCount == 0L)) {
                        snapTracks
                    } else {
                        val trSnap = plRef.collection("tracks").get().await()
                        trSnap.documents.forEach { knownExistingPaths.add(it.reference.path) }
                        trSnap.toObjects(CloudPlaylistTrack::class.java)
                    }
                } else {
                    emptyList()
                }
                CloudPlaylistWithTracks(
                    playlist = pl,
                    subcollectionTracks = subTracks,
                    hasLegacyArrayTracks = pl.tracks.isNotEmpty()
                )
            }

            val hiddenSnap = queryCollectionSince(userDoc.collection("hidden_artists"), watermarks.hiddenMs)
            hiddenSnap.documents.forEach { knownExistingPaths.add(it.reference.path) }
            val hidden = hiddenSnap.toObjects(CloudHiddenArtist::class.java)

            CloudAccountSnapshot(
                profile = profile,
                tasteSeeds = seeds,
                likedSongs = liked,
                playlists = playlistsWithTracks,
                hiddenArtists = hidden,
                maxLikedUpdatedAtMs = maxTimestampMillis(liked.map { it.updatedAt }, watermarks.likedMs),
                maxSeedsUpdatedAtMs = maxTimestampMillis(seeds.map { it.updatedAt }, watermarks.seedsMs),
                maxPlaylistsUpdatedAtMs = maxTimestampMillis(playlistDocs.map { it.updatedAt }, watermarks.playlistsMs),
                maxHiddenUpdatedAtMs = maxTimestampMillis(hidden.map { it.updatedAt }, watermarks.hiddenMs)
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, userDoc.path)
            throw e
        }
    }

    private suspend fun queryCollectionSince(colRef: CollectionReference, sinceMs: Long): QuerySnapshot {
        return if (sinceMs > 0L) {
            colRef.whereGreaterThan("updatedAt", Timestamp(Date(sinceMs))).get().await()
        } else {
            colRef.get().await()
        }
    }

    private fun maxTimestampMillis(timestamps: List<Timestamp?>, baseline: Long): Long {
        var maxMs = baseline
        timestamps.forEach { ts ->
            val ms = ts?.toDate()?.time ?: 0L
            if (ms > maxMs) maxMs = ms
        }
        return maxMs
    }

    /**
     * Merges fields without performing a pre-read `doc.get()`. Uses [knownExistingPaths] to decide
     * whether `createdAt` should be included on first write; if a concurrent device already created
     * the doc, falls back once to writing without `createdAt`.
     */
    private suspend fun setMergedWithoutPreRead(
        docRef: DocumentReference,
        mutableFields: Map<String, Any?>,
        extraCreateFields: Map<String, Any?> = emptyMap()
    ) {
        val cleanMutable = mutableFields.filterValues { it != null }
        val knownExists = docRef.path in knownExistingPaths
        if (knownExists) {
            docRef.set(cleanMutable, SetOptions.merge()).await()
            return
        }

        val createPayload = (cleanMutable + extraCreateFields + ("createdAt" to FieldValue.serverTimestamp()))
            .filterValues { it != null }
        try {
            docRef.set(createPayload, SetOptions.merge()).await()
            knownExistingPaths.add(docRef.path)
        } catch (e: FirebaseFirestoreException) {
            if (e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                // Likely already exists on server with an earlier createdAt; merge mutable fields only
                docRef.set(cleanMutable, SetOptions.merge()).await()
                knownExistingPaths.add(docRef.path)
            } else {
                throw e
            }
        }
    }

    suspend fun upsertUserProfile(
        prefs: VelunaUserPrefs,
        tasteProfile: TasteProfile,
        email: String?,
        photoUrl: String?,
        overrideDisplayName: String? = null,
        prefsUpdatedAtMs: Long = System.currentTimeMillis()
    ) {
        val uid = requireUserId()
        val docRef = db.collection("users").document(uid)
        val safeName = (overrideDisplayName ?: prefs.userDisplayName)
            .trim()
            .ifBlank { "Listener" }
            .take(80)
        val safeEmail = email?.trim()?.takeIf { it.isNotEmpty() }?.take(200)
        val safePhoto = SecurityValidators.sanitizeHttpsUrl(photoUrl, 500)
        val safeGenres = tasteProfile.selectedGenres
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(25)
        val safeAccent = prefs.customAccentHex?.trim()?.takeIf { SecurityValidators.isValidHexColor(it) }

        val mutableFields = mapOf<String, Any?>(
            "displayName" to safeName,
            "email" to safeEmail,
            "photoUrl" to safePhoto,
            "themePreset" to prefs.themePreset.id.take(32),
            "themeMode" to prefs.themeMode.name,
            "customAccentHex" to safeAccent,
            "adaptToArtwork" to prefs.adaptToArtwork,
            "cornerRadiusDp" to prefs.cornerRadiusDp.coerceIn(4, 24).toLong(),
            "audioQuality" to prefs.audioQuality.name,
            "autoplay" to prefs.autoplay,
            "loudnessNormalization" to prefs.loudnessNormalization,
            "hideMusicVideos" to prefs.hideMusicVideos,
            "selectedGenres" to safeGenres,
            "onboardingCompleted" to tasteProfile.onboardingCompleted,
            "prefsUpdatedAt" to prefsUpdatedAtMs.coerceAtLeast(0L),
            "updatedAt" to FieldValue.serverTimestamp()
        )

        try {
            setMergedWithoutPreRead(
                docRef = docRef,
                mutableFields = mutableFields,
                extraCreateFields = mapOf("userId" to uid)
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path)
            throw e
        }
    }

    /**
     * Upserts active seeds using stable docIds and preserves each seed's real client timestamp
     * ([TasteSeed.updatedAtMs]) rather than fabricating `now`. Writes explicit tombstone documents
     * for deleted/cleared seeds so empty lists propagate across devices.
     */
    suspend fun upsertTasteSeeds(
        artists: List<TasteSeed>,
        songs: List<TasteSeed>,
        tombstones: List<SyncTombstoneEntity> = emptyList(),
        nowMs: Long = System.currentTimeMillis()
    ) {
        val uid = requireUserId()
        val colRef = db.collection("users").document(uid).collection("taste_seeds")
        val targetMap = linkedMapOf<String, Map<String, Any?>>()

        artists.take(30).forEach { seed ->
            val docId = resolveSeedDocId("art", seed)
            val seedTs = (seed.updatedAtMs.takeIf { it > 0L } ?: nowMs).coerceAtLeast(0L)
            targetMap[docId] = mapOf(
                "kind" to "ARTIST",
                "name" to seed.name.trim().ifBlank { "Artist" }.take(160),
                "subtitle" to seed.subtitle?.take(160),
                "thumbnail" to SecurityValidators.sanitizeHttpsUrl(seed.thumbnail, 500),
                "deleted" to false,
                "clientUpdatedAt" to seedTs,
                "updatedAt" to FieldValue.serverTimestamp()
            )
        }
        songs.take(30).forEach { seed ->
            val docId = resolveSeedDocId("sng", seed)
            val seedTs = (seed.updatedAtMs.takeIf { it > 0L } ?: nowMs).coerceAtLeast(0L)
            targetMap[docId] = mapOf(
                "kind" to "SONG",
                "name" to seed.name.trim().ifBlank { "Track" }.take(160),
                "subtitle" to seed.subtitle?.take(160),
                "thumbnail" to SecurityValidators.sanitizeHttpsUrl(seed.thumbnail, 500),
                "deleted" to false,
                "clientUpdatedAt" to seedTs,
                "updatedAt" to FieldValue.serverTimestamp()
            )
        }

        try {
            for ((docId, fields) in targetMap) {
                setMergedWithoutPreRead(colRef.document(docId), fields)
            }
            for (tomb in tombstones) {
                if (tomb.entityKey !in targetMap && isValidFirestoreId(tomb.entityKey)) {
                    val tombFields = mapOf(
                        "deleted" to true,
                        "clientUpdatedAt" to tomb.clientUpdatedAt.coerceAtLeast(0L),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                    setMergedWithoutPreRead(colRef.document(tomb.entityKey), tombFields)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, colRef.path)
            throw e
        }
    }

    suspend fun writeTasteSeedTombstone(seedDocId: String, clientUpdatedAt: Long) {
        val uid = requireUserId()
        val safeId = sanitizeDocId(seedDocId, prefix = "art")
        val docRef = db.collection("users").document(uid).collection("taste_seeds").document(safeId)
        try {
            val fields = mapOf(
                "deleted" to true,
                "clientUpdatedAt" to clientUpdatedAt.coerceAtLeast(0L),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            setMergedWithoutPreRead(docRef, fields)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docRef.path)
            throw e
        }
    }

    suspend fun syncLikedSong(
        song: SongItem,
        liked: Boolean,
        likedAtMs: Long = System.currentTimeMillis(),
        clientUpdatedAtMs: Long = likedAtMs
    ) {
        if (song.isLocal || song.videoId.startsWith(LOCAL_SONG_PREFIX)) return
        val uid = requireUserId()
        val safeId = sanitizeDocId(song.videoId, prefix = "trk")
        val docRef = db.collection("users").document(uid).collection("liked_songs").document(safeId)
        try {
            if (!liked) {
                val tombFields = mapOf(
                    "deleted" to true,
                    "clientUpdatedAt" to clientUpdatedAtMs.coerceAtLeast(0L),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                setMergedWithoutPreRead(docRef, tombFields)
                return
            }
            val mutableFields = mapOf<String, Any?>(
                "title" to song.title.trim().ifBlank { "Track" }.take(200),
                "artists" to song.artists.trim().take(200),
                "album" to song.album?.take(200),
                "albumId" to song.albumId?.take(128),
                "artistId" to song.artistId?.take(128),
                "duration" to song.duration?.take(24),
                "durationSecs" to song.durationSeconds.coerceAtLeast(0L),
                "thumbnail" to SecurityValidators.sanitizeHttpsUrl(song.thumbnail, 500),
                "explicit" to song.explicit,
                "likedAt" to likedAtMs.coerceAtLeast(0L),
                "deleted" to false,
                "clientUpdatedAt" to clientUpdatedAtMs.coerceAtLeast(0L),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            setMergedWithoutPreRead(docRef, mutableFields)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, if (liked) OperationType.WRITE else OperationType.DELETE, docRef.path)
            throw e
        }
    }

    /**
     * Removes any erroneously synced `LOCAL:` doc from `liked_songs` (hard delete migration).
     */
    suspend fun purgeLocalLikedDocsFromCloud(videoIds: List<String>) {
        if (videoIds.isEmpty()) return
        val uid = requireUserId()
        val col = db.collection("users").document(uid).collection("liked_songs")
        for (id in videoIds) {
            try {
                val safeId = sanitizeDocId(id, prefix = "trk")
                col.document(safeId).delete().await()
                knownExistingPaths.remove(col.document(safeId).path)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Best-effort cleanup of legacy local docs
            }
        }
    }

    /**
     * Atomically publishes a versioned playlist snapshot:
     * 1. Creates staging snapshot doc (`state = STAGING`)
     * 2. Writes track documents in batches <= 400 under `snapshots/{snapshotId}/tracks`
     * 3. Marks snapshot `COMMITTED`
     * 4. Runs a Firestore transaction on the parent playlist doc verifying monotonic
     *    `syncRevision` / `clientUpdatedAt` before switching `activeSnapshotId`
     * 5. Best-effort deletes superseded snapshots and legacy `tracks` subcollection docs.
     */
    suspend fun upsertPlaylist(
        playlistKey: String,
        title: String,
        description: String,
        coverUri: String?,
        tracks: List<SongItem>,
        clientUpdatedAt: Long,
        syncRevision: Long = 1L,
        removeLegacyTracksField: Boolean = false
    ) {
        val uid = requireUserId()
        val safeKey = sanitizeDocId(playlistKey, prefix = "pl")
        val plDocRef = db.collection("users").document(uid).collection("playlists").document(safeKey)

        val targetRev = syncRevision.coerceAtLeast(1L)
        val safeClientTs = clientUpdatedAt.coerceAtLeast(0L)
        val safeCover = SecurityValidators.sanitizeHttpsUrl(coverUri, 500)
        val remoteTracks = tracks.filter { !it.isLocal && !it.videoId.startsWith(LOCAL_SONG_PREFIX) }

        val snapshotId = "snap_${targetRev}_${UUID.randomUUID().toString().replace("-", "").take(12)}"
        val snapRef = plDocRef.collection("snapshots").document(snapshotId)

        var committedParent = false
        try {
            snapRef.set(
                mapOf(
                    "state" to "STAGING",
                    "syncRevision" to targetRev,
                    "trackCount" to remoteTracks.size.toLong(),
                    "clientUpdatedAt" to safeClientTs,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()

            val snapTracksCol = snapRef.collection("tracks")
            remoteTracks.withIndex().chunked(SNAPSHOT_BATCH_LIMIT).forEachIndexed { chunkIdx, chunk ->
                onBeforeCommitPlaylistSnapshot?.invoke(snapshotId, chunkIdx)
                val batch = db.batch()
                for ((idx, s) in chunk) {
                    val trkId = snapshotTrackDocId(idx, s.videoId)
                    val trkFields = linkedMapOf<String, Any?>(
                        "videoId" to s.videoId.take(128),
                        "title" to s.title.trim().ifBlank { "Track" }.take(200),
                        "artists" to s.artists.trim().take(200),
                        "album" to s.album?.take(200),
                        "albumId" to s.albumId?.take(128),
                        "artistId" to s.artistId?.take(128),
                        "duration" to s.duration?.take(24),
                        "durationSecs" to s.durationSeconds.coerceAtLeast(0L),
                        "thumbnail" to SecurityValidators.sanitizeHttpsUrl(s.thumbnail, 500),
                        "explicit" to s.explicit,
                        "position" to idx,
                        "addedAt" to (s.addedAt ?: safeClientTs).coerceAtLeast(0L),
                        "deleted" to false,
                        "clientUpdatedAt" to safeClientTs,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    ).filterValues { it != null }
                    batch.set(snapTracksCol.document(trkId), trkFields)
                }
                batch.commit().await()
            }

            snapRef.update(
                mapOf(
                    "state" to "COMMITTED",
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()

            db.runTransaction { tx ->
                val existing = tx.get(plDocRef)
                if (existing.exists()) {
                    val existingDeleted = existing.getBoolean("deleted") == true
                    val existingRev = existing.getLong("syncRevision") ?: 0L
                    val existingTs = existing.getLong("clientUpdatedAt") ?: 0L
                    val stale = !existingDeleted && (
                        existingRev > targetRev ||
                            (existingRev == targetRev && existingTs > safeClientTs)
                        )
                    if (stale) {
                        throw StalePlaylistRevisionException(existingRev, targetRev)
                    }
                }

                val payload = linkedMapOf<String, Any?>(
                    "title" to title.trim().ifBlank { "Playlist" }.take(120),
                    "description" to description.take(500),
                    "deleted" to false,
                    "syncRevision" to targetRev,
                    "activeSnapshotId" to snapshotId,
                    "trackCount" to remoteTracks.size.toLong(),
                    "clientUpdatedAt" to safeClientTs,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                if (safeCover != null) {
                    payload["coverUri"] = safeCover
                } else if (existing.exists() && existing.contains("coverUri")) {
                    payload["coverUri"] = FieldValue.delete()
                }
                if (!existing.exists()) {
                    payload["createdAt"] = FieldValue.serverTimestamp()
                    tx.set(plDocRef, payload.filterValues { it != null })
                } else {
                    if (removeLegacyTracksField || existing.contains("tracks")) {
                        payload["tracks"] = FieldValue.delete()
                    }
                    tx.update(plDocRef, payload)
                }
                null
            }.await()
            committedParent = true
            knownExistingPaths.add(plDocRef.path)
        } catch (e: CancellationException) {
            if (!committedParent) {
                cleanupSnapshotBestEffort(snapRef)
            }
            throw e
        } catch (e: StalePlaylistRevisionException) {
            cleanupSnapshotBestEffort(snapRef)
            throw e
        } catch (e: Exception) {
            if (!committedParent) {
                cleanupSnapshotBestEffort(snapRef)
            }
            handleFirestoreError(e, OperationType.WRITE, plDocRef.path)
            throw e
        }

        // Best-effort post-commit garbage collection of superseded snapshots and legacy tracks
        cleanupSupersededPlaylistDataBestEffort(plDocRef, keepSnapshotId = snapshotId)
    }

    suspend fun deletePlaylist(
        playlistKey: String,
        clientUpdatedAt: Long = System.currentTimeMillis(),
        syncRevision: Long = 0L,
        clearLegacyTracksArray: Boolean = true
    ) {
        val uid = requireUserId()
        val safeKey = sanitizeDocId(playlistKey, prefix = "pl")
        val docRef = db.collection("users").document(uid).collection("playlists").document(safeKey)
        val safeTs = clientUpdatedAt.coerceAtLeast(0L)
        try {
            if (syncRevision > 0L) {
                db.runTransaction { tx ->
                    val existing = tx.get(docRef)
                    if (existing.exists()) {
                        val existingDeleted = existing.getBoolean("deleted") == true
                        val existingRev = existing.getLong("syncRevision") ?: 0L
                        if (!existingDeleted && existingRev > syncRevision) {
                            throw StalePlaylistRevisionException(existingRev, syncRevision)
                        }
                        val updates = linkedMapOf<String, Any>(
                            "deleted" to true,
                            "clientUpdatedAt" to safeTs,
                            "syncRevision" to syncRevision,
                            "trackCount" to 0L,
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                        if (existing.contains("activeSnapshotId")) {
                            updates["activeSnapshotId"] = FieldValue.delete()
                        }
                        if (clearLegacyTracksArray && existing.contains("tracks")) {
                            updates["tracks"] = FieldValue.delete()
                        }
                        tx.update(docRef, updates)
                    } else {
                        tx.set(
                            docRef,
                            mapOf(
                                "deleted" to true,
                                "clientUpdatedAt" to safeTs,
                                "syncRevision" to syncRevision,
                                "trackCount" to 0L,
                                "createdAt" to FieldValue.serverTimestamp(),
                                "updatedAt" to FieldValue.serverTimestamp()
                            )
                        )
                    }
                    null
                }.await()
                knownExistingPaths.add(docRef.path)
            } else {
                val tombFields = linkedMapOf<String, Any?>(
                    "deleted" to true,
                    "trackCount" to 0L,
                    "activeSnapshotId" to FieldValue.delete(),
                    "clientUpdatedAt" to safeTs,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                if (clearLegacyTracksArray) {
                    tombFields["tracks"] = FieldValue.delete()
                }
                setMergedWithoutPreRead(docRef, tombFields)
            }
            cleanupSupersededPlaylistDataBestEffort(docRef, keepSnapshotId = null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: StalePlaylistRevisionException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docRef.path)
            throw e
        }
    }

    private suspend fun cleanupSnapshotBestEffort(snapRef: DocumentReference) {
        try {
            deleteCollectionInBatches(snapRef.collection("tracks"))
            snapRef.delete().await()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Best effort cleanup of orphaned staging snapshots
        }
    }

    private suspend fun cleanupSupersededPlaylistDataBestEffort(
        plDocRef: DocumentReference,
        keepSnapshotId: String?
    ) {
        try {
            val snaps = plDocRef.collection("snapshots").get().await()
            for (doc in snaps.documents) {
                if (keepSnapshotId == null || doc.id != keepSnapshotId) {
                    deleteCollectionInBatches(doc.reference.collection("tracks"))
                    doc.reference.delete().await()
                }
            }
            deleteCollectionInBatches(plDocRef.collection("tracks"))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Best-effort cleanup
        }
    }

    suspend fun syncHiddenArtist(
        name: String,
        channelId: String?,
        blocked: Boolean,
        blockedAtMs: Long = System.currentTimeMillis(),
        clientUpdatedAtMs: Long = blockedAtMs
    ) {
        val uid = requireUserId()
        val cleanName = name.trim()
        if (cleanName.isBlank()) return
        val key = if (!blocked && cleanName.startsWith("blk_") && isValidFirestoreId(cleanName)) {
            cleanName
        } else {
            stableDocId("blk", cleanName.lowercase())
        }
        val docRef = db.collection("users").document(uid).collection("hidden_artists").document(key)
        try {
            if (!blocked) {
                val tombFields = mapOf(
                    "deleted" to true,
                    "clientUpdatedAt" to clientUpdatedAtMs.coerceAtLeast(0L),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                setMergedWithoutPreRead(docRef, tombFields)
                return
            }
            val mutableFields = mapOf<String, Any?>(
                "name" to cleanName.take(160),
                "channelId" to channelId?.take(128),
                "blockedAt" to blockedAtMs.coerceAtLeast(0L),
                "deleted" to false,
                "clientUpdatedAt" to clientUpdatedAtMs.coerceAtLeast(0L),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            setMergedWithoutPreRead(docRef, mutableFields)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, if (blocked) OperationType.WRITE else OperationType.DELETE, docRef.path)
            throw e
        }
    }

    /**
     * Deletes all Firestore documents owned by [userId] in batches of 400 (idempotent).
     * Never silently swallows errors; callers handle PERMISSION_DENIED vs transient network retries.
     */
    suspend fun deleteAllUserData(userId: String) {
        val uid = requireUserId()
        require(uid == userId) { "Authenticated UID does not match deletion target." }
        val userDoc = db.collection("users").document(uid)

        try {
            // 1. Nested snapshot/tracks subcollections inside each playlist
            val playlists = userDoc.collection("playlists").get().await()
            for (plDoc in playlists.documents) {
                val snaps = plDoc.reference.collection("snapshots").get().await()
                for (snapDoc in snaps.documents) {
                    deleteCollectionInBatches(snapDoc.reference.collection("tracks"))
                }
                deleteCollectionInBatches(plDoc.reference.collection("snapshots"))
                deleteCollectionInBatches(plDoc.reference.collection("tracks"))
            }

            // 2. Direct subcollections under /users/{uid}
            for (sub in listOf("playlists", "liked_songs", "taste_seeds", "hidden_artists")) {
                deleteCollectionInBatches(userDoc.collection(sub))
            }

            // 3. Root profile document
            userDoc.delete().await()
            clearSessionCache()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, userDoc.path)
            throw e
        }
    }

    private suspend fun deleteCollectionInBatches(colRef: CollectionReference) {
        while (true) {
            val snap = colRef.limit(400).get().await()
            if (snap.isEmpty) break
            val batch = db.batch()
            for (doc in snap.documents) {
                batch.delete(doc.reference)
            }
            batch.commit().await()
            if (snap.size() < 400) break
        }
    }

    /**
     * Client-side maintenance sweep removing soft-tombstone documents older than 30 days.
     */
    suspend fun purgeExpiredCloudTombstones(userId: String, olderThanEpochMs: Long) {
        val userDoc = db.collection("users").document(userId)
        for (sub in listOf("liked_songs", "playlists", "taste_seeds", "hidden_artists")) {
            while (true) {
                val expired = userDoc.collection(sub)
                    .whereEqualTo("deleted", true)
                    .whereLessThan("clientUpdatedAt", olderThanEpochMs)
                    .limit(200)
                    .get()
                    .await()
                if (expired.isEmpty) break

                if (sub == "playlists") {
                    // Firestore does not cascade deletes; remove every nested snapshot/track before its parent.
                    for (doc in expired.documents) {
                        val snaps = doc.reference.collection("snapshots").get().await()
                        for (snapDoc in snaps.documents) {
                            deleteCollectionInBatches(snapDoc.reference.collection("tracks"))
                        }
                        deleteCollectionInBatches(doc.reference.collection("snapshots"))
                        deleteCollectionInBatches(doc.reference.collection("tracks"))
                    }
                }

                expired.documents.chunked(400).forEach { docs ->
                    val batch = db.batch()
                    docs.forEach { doc -> batch.delete(doc.reference) }
                    batch.commit().await()
                }
            }
        }
    }

    private fun unwrapFirestoreError(error: Throwable): FirebaseFirestoreException? =
        generateSequence(error) { it.cause }
            .filterIsInstance<FirebaseFirestoreException>()
            .firstOrNull()

    companion object {
        const val SNAPSHOT_BATCH_LIMIT = 400

        fun snapshotTrackDocId(index: Int, videoId: String): String {
            val raw = "trk_${index.toString().padStart(5, '0')}_${videoId.trim()}"
            val cleaned = raw.replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
            return if (cleaned.length in 1..128 && !cleaned.startsWith("LOCAL")) {
                cleaned
            } else {
                stableDocId("trk_${index}", videoId)
            }
        }

        fun stableDocId(prefix: String, raw: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(raw.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
                .take(24)
            return "${prefix}_$digest".take(128)
        }

        fun resolveSeedDocId(prefix: String, seed: TasteSeed): String {
            val candidate = seed.id.trim()
            if (candidate.isNotEmpty() && isValidFirestoreId(candidate) &&
                (candidate.startsWith("art_") || candidate.startsWith("sng_") || candidate.startsWith("UC") || candidate.startsWith("seed_"))
            ) {
                return candidate
            }
            val normKey = candidate.ifBlank { seed.name.lowercase().trim() + "::" + (seed.subtitle?.lowercase()?.trim().orEmpty()) }
            return stableDocId(prefix, normKey)
        }

        fun isValidFirestoreId(id: String): Boolean =
            id.length in 1..128 && id.matches(Regex("^[a-zA-Z0-9_\\-]+$"))

        fun sanitizeDocId(raw: String, prefix: String): String {
            val clean = raw.trim()
            return if (clean.isNotEmpty() && clean.length <= 128 && clean.matches(Regex("^[a-zA-Z0-9_\\-]+$")) && !clean.startsWith("LOCAL")) {
                clean
            } else {
                stableDocId(prefix, raw)
            }
        }

        fun songFromCloudMap(map: Map<String, Any?>): SongItem? {
            val videoId = (map["videoId"] as? String)?.trim()
                ?.takeIf { it.isNotBlank() && !it.startsWith(LOCAL_SONG_PREFIX) }
                ?: return null
            val title = (map["title"] as? String)?.takeIf { it.isNotBlank() } ?: return null
            return SongItem(
                videoId = videoId,
                title = title,
                artists = (map["artists"] as? String).orEmpty(),
                album = map["album"] as? String,
                albumId = map["albumId"] as? String,
                artistId = map["artistId"] as? String,
                duration = map["duration"] as? String,
                thumbnail = SecurityValidators.sanitizeHttpsUrl(map["thumbnail"] as? String, 2048),
                explicit = (map["explicit"] as? Boolean) ?: false
            )
        }
    }
}
