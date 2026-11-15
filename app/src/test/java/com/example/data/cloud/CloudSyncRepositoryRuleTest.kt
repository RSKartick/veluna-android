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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.fail
import org.junit.Test
import java.util.UUID

class CloudSyncRepositoryRuleTest : FirestoreEmulatorTestBase() {

    @Test
    fun authenticatedOwner_canSyncProfileSeedsLikesPlaylistsAndTombstones() = runBlocking {
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
    fun localTracks_areNeverUploadedToLikedSongs() = runBlocking {
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
    fun deleteAllUserData_wipesProfileAndSubcollections() = runBlocking {
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
    fun unauthenticatedCaller_rejectedBySecurityRules() = runBlocking {
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
    fun crossUserAccess_rejectedBySecurityRules() = runBlocking {
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

    private companion object {
        const val ALICE_EMAIL = "alice_veluna@test.com"
        const val BOB_EMAIL = "bob_veluna@test.com"
        const val DEFAULT_TIMEOUT_MS = 10000L
        const val FLOW_TIMEOUT_MS = 4000L
    }
}
