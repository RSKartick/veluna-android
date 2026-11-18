package com.example.data.cloud

import com.example.base.FirestoreEmulatorTestBase
import com.example.data.local.SyncEntityTypes
import com.example.data.local.SyncTombstoneEntity
import com.example.data.local.VelunaUserPrefs
import com.example.model.LOCAL_SONG_PREFIX
import com.example.model.SongItem
import com.example.model.TasteProfile
import com.example.model.TasteSeed
import com.google.common.truth.Truth.assertThat
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.FieldValue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.junit.Assert.fail
import org.junit.Test
import java.util.UUID

class CloudSyncRepositoryRuleTest : FirestoreEmulatorTestBase() {

    @Test
    fun authenticatedOwner_canSyncProfileSeedsLikesPlaylistsAndTombstones() : Unit = runBlocking {
        val uid = signInTestUser(ALICE_EMAIL)
        val repo = CloudSyncRepository(firestore, auth)

        val tasteProfile = TasteProfile(
            onboardingCompleted = true,
            selectedGenres = listOf("Electronic", "Indie"),
            seedArtists = listOf(TasteSeed(id = "UC_daft", name = "Daft Punk")),
            seedSongs = listOf(TasteSeed(id = "trk_abc", name = "Instant Crush"))
        )
        val songId = "trk_${UUID.randomUUID().toString().replace("-", "").take(8)}"
        val plKey = "pl_${UUID.randomUUID().toString().replace("-", "").take(8)}"
        val sampleSong = SongItem(
            videoId = songId,
            title = "Instant Crush",
            artists = "Daft Punk",
            duration = "5:37"
        )

        withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.upsertUserProfile(
                prefs = VelunaUserPrefs(userDisplayName = "Alice"),
                tasteProfile = tasteProfile,
                email = ALICE_EMAIL,
                photoUrl = "https://example.com/alice.png"
            )
            repo.upsertTasteSeeds(tasteProfile.seedArtists, tasteProfile.seedSongs)
            repo.syncLikedSong(sampleSong, liked = true)
            repo.upsertPlaylist(
                playlistKey = plKey,
                title = "Focus Mix",
                description = "Chill electronics",
                coverUri = null,
                tracks = listOf(sampleSong),
                clientUpdatedAt = System.currentTimeMillis()
            )
        }

        val snapshot = withTimeout(DEFAULT_TIMEOUT_MS) { repo.fetchAccountSnapshot() }
        assertThat(snapshot.profile?.userId).isEqualTo(uid)
        assertThat(snapshot.profile?.displayName).isEqualTo("Alice")
        assertThat(snapshot.likedSongs.map { it.videoId }).contains(songId)
        val syncedPlaylist = snapshot.playlists.first { it.playlist.playlistKey == plKey }
        assertThat(syncedPlaylist.playlist.tracks).isEmpty()
        assertThat(syncedPlaylist.subcollectionTracks.map { it.videoId }).containsExactly(songId)

        // Tombstone propagation on unlike + playlist delete + empty seed tombstones
        val tombNow = System.currentTimeMillis()
        withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.syncLikedSong(sampleSong, liked = false, likedAtMs = tombNow, clientUpdatedAtMs = tombNow)
            repo.deletePlaylist(plKey, clientUpdatedAt = tombNow)
            repo.upsertTasteSeeds(
                artists = emptyList(),
                songs = emptyList(),
                tombstones = listOf(
                    SyncTombstoneEntity(SyncEntityTypes.SEED, "UC_daft", tombNow, tombNow),
                    SyncTombstoneEntity(SyncEntityTypes.SEED, CloudSyncRepository.resolveSeedDocId("sng", tasteProfile.seedSongs.first()), tombNow, tombNow)
                ),
                nowMs = tombNow
            )
        }

        val postTombstone = withTimeout(DEFAULT_TIMEOUT_MS) { repo.fetchAccountSnapshot() }
        assertThat(postTombstone.likedSongs.first { it.videoId == songId }.deleted).isTrue()
        assertThat(postTombstone.playlists.first { it.playlist.playlistKey == plKey }.playlist.deleted).isTrue()
        assertThat(postTombstone.tasteSeeds.filter { !it.deleted }).isEmpty()
    }

    @Test
    fun localTracks_areNeverUploadedToLikedSongs() : Unit = runBlocking {
        signInTestUser(ALICE_EMAIL)
        val repo = CloudSyncRepository(firestore, auth)
        val localSong = SongItem(
            videoId = "${LOCAL_SONG_PREFIX}content://media/external/audio/1",
            title = "Local Riff",
            artists = "Me",
            isLocal = true
        )
        withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.syncLikedSong(localSong, liked = true)
        }
        val snapshot = withTimeout(DEFAULT_TIMEOUT_MS) { repo.fetchAccountSnapshot() }
        assertThat(snapshot.likedSongs.none { it.videoId.startsWith(LOCAL_SONG_PREFIX) }).isTrue()
    }

    @Test
    fun deleteAllUserData_wipesProfileAndSubcollections() : Unit = runBlocking {
        val uid = signInTestUser(ALICE_EMAIL)
        val repo = CloudSyncRepository(firestore, auth)
        val songId = "trk_${UUID.randomUUID().toString().replace("-", "").take(8)}"
        val plKey = "pl_${UUID.randomUUID().toString().replace("-", "").take(8)}"
        val song = SongItem(videoId = songId, title = "Delete Me", artists = "Artist")

        withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.upsertUserProfile(VelunaUserPrefs(userDisplayName = "Alice"), TasteProfile(), ALICE_EMAIL, null)
            repo.syncLikedSong(song, liked = true)
            repo.upsertPlaylist(plKey, "Temp", "", null, listOf(song), System.currentTimeMillis())
            repo.deleteAllUserData(uid)
        }

        val afterDelete = withTimeout(DEFAULT_TIMEOUT_MS) { repo.fetchAccountSnapshot() }
        assertThat(afterDelete.profile).isNull()
        assertThat(afterDelete.likedSongs).isEmpty()
        assertThat(afterDelete.playlists).isEmpty()
    }

    @Test
    fun purgeExpiredCloudTombstones_deletesAllTracksBeforePlaylistParent() : Unit = runBlocking {
        val uid = signInTestUser(ALICE_EMAIL)
        val repo = CloudSyncRepository(firestore, auth)
        val now = System.currentTimeMillis()
        val expiredAt = now - 40L * 24L * 60L * 60L * 1000L
        val playlist = firestore.collection("users").document(uid)
            .collection("playlists").document("pl_expired")
        val tracks = playlist.collection("tracks")

        withTimeout(30_000L) {
            playlist.set(
                mapOf(
                    "deleted" to true,
                    "clientUpdatedAt" to expiredAt,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            repeat(2) { batchIndex ->
                val batch = firestore.batch()
                val start = batchIndex * 400
                val end = minOf(start + 400, 450)
                for (index in start until end) {
                    val trackId = "trk_$index"
                    batch.set(
                        tracks.document(trackId),
                        mapOf(
                            "videoId" to trackId,
                            "title" to "Track $index",
                            "artists" to "Artist",
                            "durationSecs" to 180,
                            "explicit" to false,
                            "position" to index,
                            "addedAt" to expiredAt,
                            "deleted" to false,
                            "clientUpdatedAt" to expiredAt,
                            "createdAt" to FieldValue.serverTimestamp(),
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    )
                }
                batch.commit().await()
            }

            repo.purgeExpiredCloudTombstones(uid, now - 30L * 24L * 60L * 60L * 1000L)
        }

        assertThat(tracks.get().await().documents).isEmpty()
        assertThat(playlist.get().await().exists()).isFalse()
    }

    @Test
    fun unauthenticatedCaller_rejectedBySecurityRules() : Unit = runBlocking {
        val aliceUid = signInTestUser(ALICE_EMAIL)
        auth.signOut()
        val repo = CloudSyncRepository(firestore, auth)

        try {
            withTimeout(FLOW_TIMEOUT_MS) {
                repo.observeLikedSongs(aliceUid).first()
            }
            fail("Expected PERMISSION_DENIED for unauthenticated listener")
        } catch (e: Exception) {
            val fsError = checkNotNull(e.unwrapFirestoreException()) {
                "Expected nested FirebaseFirestoreException, got $e"
            }
            assertThat(fsError.code).isEqualTo(FirebaseFirestoreException.Code.PERMISSION_DENIED)
        }
    }

    @Test
    fun crossUserAccess_rejectedBySecurityRules() : Unit = runBlocking {
        val aliceUid = signInTestUser(ALICE_EMAIL)
        val aliceRepo = CloudSyncRepository(firestore, auth)
        val songId = "trk_${UUID.randomUUID().toString().replace("-", "").take(8)}"
        withTimeout(DEFAULT_TIMEOUT_MS) {
            aliceRepo.syncLikedSong(
                SongItem(videoId = songId, title = "Secret Track", artists = "Artist"),
                liked = true
            )
        }

        signInTestUser(BOB_EMAIL)
        val bobRepo = CloudSyncRepository(firestore, auth)

        try {
            withTimeout(FLOW_TIMEOUT_MS) {
                bobRepo.observeLikedSongs(aliceUid).first()
            }
            fail("Expected PERMISSION_DENIED for cross-user read")
        } catch (e: Exception) {
            val fsError = checkNotNull(e.unwrapFirestoreException()) {
                "Expected nested FirebaseFirestoreException, got $e"
            }
            assertThat(fsError.code).isEqualTo(FirebaseFirestoreException.Code.PERMISSION_DENIED)
        }
    }

    @Test
    fun upsertPlaylist_chunksOver450TracksAtomicallyAndRestoresOrder() : Unit = runBlocking {
        val email = "chunk_${UUID.randomUUID()}@test.com"
        signInTestUser(email)
        val repo = CloudSyncRepository(firestore, auth)
        val plKey = "pl_${UUID.randomUUID().toString().replace("-", "").take(12)}"
        val tracks = (0 until 460).map { i ->
            SongItem(
                videoId = "trk_%04d".format(i),
                title = "Track $i",
                artists = "Artist ${i % 10}",
                duration = "3:10",
                addedAt = 1700000000000L + i
            )
        }

        withTimeout(60_000L) {
            repo.upsertPlaylist(
                playlistKey = plKey,
                title = "Mega Mix",
                description = "460 tracks across batches",
                coverUri = "https://example.com/mega.jpg",
                tracks = tracks,
                clientUpdatedAt = System.currentTimeMillis(),
                syncRevision = 1L
            )
        }

        val snapshot = withTimeout(30_000L) { repo.fetchAccountSnapshot() }
        val loaded = snapshot.playlists.first { it.playlist.playlistKey == plKey }
        assertThat(loaded.playlist.syncRevision).isEqualTo(1L)
        assertThat(loaded.playlist.trackCount).isEqualTo(460L)
        assertThat(loaded.playlist.activeSnapshotId).isNotNull()
        assertThat(loaded.subcollectionTracks).hasSize(460)
        val orderedIds = loaded.subcollectionTracks.sortedBy { it.position }.map { it.videoId }
        assertThat(orderedIds.first()).isEqualTo("trk_0000")
        assertThat(orderedIds.last()).isEqualTo("trk_0459")
    }

    @Test
    fun upsertPlaylist_partialChunkFailureKeepsPreviousActiveSnapshotIntact() : Unit = runBlocking {
        val email = "atomic_${UUID.randomUUID()}@test.com"
        val uid = signInTestUser(email)
        val repo = CloudSyncRepository(firestore, auth)
        val plKey = "pl_${UUID.randomUUID().toString().replace("-", "").take(12)}"

        val v1Tracks = (0 until 5).map { i ->
            SongItem(videoId = "v1_$i", title = "V1 $i", artists = "Artist")
        }
        withTimeout(20_000L) {
            repo.upsertPlaylist(
                playlistKey = plKey,
                title = "V1 Playlist",
                description = "Stable",
                coverUri = null,
                tracks = v1Tracks,
                clientUpdatedAt = System.currentTimeMillis() - 1_000L,
                syncRevision = 1L
            )
        }
        val beforeSnap = withTimeout(DEFAULT_TIMEOUT_MS) { repo.fetchAccountSnapshot() }
        val v1SnapshotId = beforeSnap.playlists.first { it.playlist.playlistKey == plKey }.playlist.activeSnapshotId
        assertThat(v1SnapshotId).isNotNull()

        val v2Tracks = (0 until 420).map { i ->
            SongItem(videoId = "v2_$i", title = "V2 $i", artists = "Artist")
        }
        repo.onBeforeCommitPlaylistSnapshot = { _, chunkIdx ->
            if (chunkIdx == 1) {
                throw IllegalStateException("Simulated network drop before chunk 1")
            }
        }

        var failed = false
        try {
            withTimeout(20_000L) {
                repo.upsertPlaylist(
                    playlistKey = plKey,
                    title = "V2 Interrupted",
                    description = "Should not publish",
                    coverUri = null,
                    tracks = v2Tracks,
                    clientUpdatedAt = System.currentTimeMillis(),
                    syncRevision = 2L
                )
            }
        } catch (_: IllegalStateException) {
            failed = true
        } finally {
            repo.onBeforeCommitPlaylistSnapshot = null
        }

        assertThat(failed).isTrue()
        val afterSnap = withTimeout(DEFAULT_TIMEOUT_MS) { repo.fetchAccountSnapshot() }
        val intact = afterSnap.playlists.first { it.playlist.playlistKey == plKey }
        assertThat(intact.playlist.syncRevision).isEqualTo(1L)
        assertThat(intact.playlist.title).isEqualTo("V1 Playlist")
        assertThat(intact.playlist.activeSnapshotId).isEqualTo(v1SnapshotId)
        assertThat(intact.subcollectionTracks.map { it.videoId })
            .containsExactly("v1_0", "v1_1", "v1_2", "v1_3", "v1_4")

        val snapDocs = firestore.collection("users").document(uid)
            .collection("playlists").document(plKey)
            .collection("snapshots").get().await().documents
        assertThat(snapDocs.map { it.id }).containsExactly(v1SnapshotId)
    }

    @Test
    fun upsertPlaylist_staleRevisionRejectedAndStagingSnapshotCleanedUp() : Unit = runBlocking {
        val email = "stale_${UUID.randomUUID()}@test.com"
        val uid = signInTestUser(email)
        val repo = CloudSyncRepository(firestore, auth)
        val plKey = "pl_${UUID.randomUUID().toString().replace("-", "").take(12)}"

        withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.upsertPlaylist(
                playlistKey = plKey,
                title = "Rev 3 Title",
                description = "Newest",
                coverUri = null,
                tracks = listOf(SongItem(videoId = "trk_new", title = "New", artists = "Artist")),
                clientUpdatedAt = System.currentTimeMillis(),
                syncRevision = 3L
            )
        }

        var threwStale = false
        try {
            withTimeout(DEFAULT_TIMEOUT_MS) {
                repo.upsertPlaylist(
                    playlistKey = plKey,
                    title = "Stale Rev 2",
                    description = "Old",
                    coverUri = null,
                    tracks = listOf(SongItem(videoId = "trk_old", title = "Old", artists = "Artist")),
                    clientUpdatedAt = System.currentTimeMillis() - 10_000L,
                    syncRevision = 2L
                )
            }
        } catch (_: StalePlaylistRevisionException) {
            threwStale = true
        }

        assertThat(threwStale).isTrue()
        val snap = withTimeout(DEFAULT_TIMEOUT_MS) { repo.fetchAccountSnapshot() }
        val playlist = snap.playlists.first { it.playlist.playlistKey == plKey }
        assertThat(playlist.playlist.syncRevision).isEqualTo(3L)
        assertThat(playlist.playlist.title).isEqualTo("Rev 3 Title")
        assertThat(playlist.subcollectionTracks.map { it.videoId }).containsExactly("trk_new")

        val snapDocs = firestore.collection("users").document(uid)
            .collection("playlists").document(plKey)
            .collection("snapshots").get().await().documents
        assertThat(snapDocs).hasSize(1)
    }

    @Test
    fun legacyPlaylistTracks_readFallbackAndMigratedOnSnapshotPublish() : Unit = runBlocking {
        val email = "legacy_pl_${UUID.randomUUID()}@test.com"
        val uid = signInTestUser(email)
        val repo = CloudSyncRepository(firestore, auth)
        val plKey = "pl_${UUID.randomUUID().toString().replace("-", "").take(12)}"
        val now = System.currentTimeMillis()
        val plRef = firestore.collection("users").document(uid).collection("playlists").document(plKey)

        withTimeout(DEFAULT_TIMEOUT_MS) {
            plRef.set(
                mapOf(
                    "title" to "Legacy Playlist",
                    "description" to "Pre-snapshot",
                    "deleted" to false,
                    "clientUpdatedAt" to now,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            plRef.collection("tracks").document("trk_leg").set(
                mapOf(
                    "videoId" to "trk_leg",
                    "title" to "Legacy Track",
                    "artists" to "Old Artist",
                    "durationSecs" to 195L,
                    "explicit" to false,
                    "position" to 0,
                    "addedAt" to now,
                    "deleted" to false,
                    "clientUpdatedAt" to now,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        }

        val initial = withTimeout(DEFAULT_TIMEOUT_MS) { repo.fetchAccountSnapshot() }
        val legacyLoaded = initial.playlists.first { it.playlist.playlistKey == plKey }
        assertThat(legacyLoaded.playlist.activeSnapshotId).isNull()
        assertThat(legacyLoaded.subcollectionTracks.map { it.videoId }).containsExactly("trk_leg")

        withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.upsertPlaylist(
                playlistKey = plKey,
                title = "Migrated Playlist",
                description = "Now snapshotted",
                coverUri = null,
                tracks = listOf(SongItem(videoId = "trk_leg", title = "Legacy Track", artists = "Old Artist")),
                clientUpdatedAt = now + 1_000L,
                syncRevision = 1L,
                removeLegacyTracksField = true
            )
        }

        assertThat(plRef.collection("tracks").get().await().documents).isEmpty()
        val migrated = withTimeout(DEFAULT_TIMEOUT_MS) { repo.fetchAccountSnapshot() }
        val migratedPl = migrated.playlists.first { it.playlist.playlistKey == plKey }
        assertThat(migratedPl.playlist.activeSnapshotId).isNotNull()
        assertThat(migratedPl.subcollectionTracks.map { it.videoId }).containsExactly("trk_leg")
    }

    @Test
    fun upsertUserProfile_persistsPrefsUpdatedAtAndPurgesExpiredSnapshotTracks() : Unit = runBlocking {
        val email = "prefs_ts_${UUID.randomUUID()}@test.com"
        val uid = signInTestUser(email)
        val repo = CloudSyncRepository(firestore, auth)
        val prefsTs = System.currentTimeMillis() - 5_000L

        withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.upsertUserProfile(
                prefs = VelunaUserPrefs(userDisplayName = "Casey", cornerRadiusDp = 20),
                tasteProfile = TasteProfile(onboardingCompleted = true, selectedGenres = listOf("Soul")),
                email = email,
                photoUrl = null,
                prefsUpdatedAtMs = prefsTs
            )
        }
        val profile = withTimeout(DEFAULT_TIMEOUT_MS) { repo.getUserProfile() }
        assertThat(profile?.prefsUpdatedAt).isEqualTo(prefsTs)
        assertThat(profile?.cornerRadiusDp).isEqualTo(20)

        // Seed an expired deleted playlist with a snapshot/tracks subtree and verify purge removes everything
        val plRef = firestore.collection("users").document(uid).collection("playlists").document("pl_exp_snap")
        val snapRef = plRef.collection("snapshots").document("snap_1_abc")
        val expiredAt = System.currentTimeMillis() - 40L * 24L * 60L * 60L * 1000L
        withTimeout(DEFAULT_TIMEOUT_MS) {
            plRef.set(
                mapOf(
                    "deleted" to true,
                    "clientUpdatedAt" to expiredAt,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            snapRef.set(
                mapOf(
                    "state" to "COMMITTED",
                    "syncRevision" to 1L,
                    "trackCount" to 1L,
                    "clientUpdatedAt" to expiredAt,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            snapRef.collection("tracks").document("0000_trk_1").set(
                mapOf(
                    "videoId" to "trk_1",
                    "title" to "Old",
                    "artists" to "Artist",
                    "durationSecs" to 100L,
                    "explicit" to false,
                    "position" to 0,
                    "addedAt" to expiredAt,
                    "deleted" to false,
                    "clientUpdatedAt" to expiredAt,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()

            repo.purgeExpiredCloudTombstones(uid, System.currentTimeMillis() - 30L * 24L * 60L * 60L * 1000L)
        }

        assertThat(snapRef.collection("tracks").get().await().documents).isEmpty()
        assertThat(plRef.collection("snapshots").get().await().documents).isEmpty()
        assertThat(plRef.get().await().exists()).isFalse()
    }

    private companion object {
        const val ALICE_EMAIL = "alice_veluna@test.com"
        const val BOB_EMAIL = "bob_veluna@test.com"
        const val DEFAULT_TIMEOUT_MS = 10000L
        const val FLOW_TIMEOUT_MS = 4000L
    }
}
