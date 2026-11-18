package com.example.data.cloud

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.base.FirestoreEmulatorTestBase
import com.example.data.local.DownloadedTrackEntity
import com.example.data.local.FollowedArtistEntity
import com.example.data.local.ListenEntity
import com.example.data.local.ListenSongEntity
import com.example.data.local.LocalTrackEntity
import com.example.data.local.LyricsCacheEntity
import com.example.data.local.PendingScrobbleEntity
import com.example.data.local.SavedAlbumEntity
import com.example.data.local.ScrobbleServices
import com.example.data.local.SearchHistoryEntity
import com.example.data.local.ShortcutEntity
import com.example.data.local.SyncEntityTypes
import com.example.data.local.UserPrefsRepository
import com.example.data.local.VelunaDatabase
import com.example.data.network.InnerTubeClient
import com.example.data.repository.VelunaRepository
import com.example.data.security.InMemoryAesGcmVaultCipher
import com.example.data.security.SecretVault
import com.example.model.AudioQuality
import com.example.model.BrowseKind
import com.example.model.SongItem
import com.example.model.TasteProfile
import com.example.model.TasteSeed
import com.example.model.ThemeMode
import com.example.model.VelunaThemePreset
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Test
import java.io.File
import java.util.UUID

class CloudSyncCoordinatorTest : FirestoreEmulatorTestBase() {

    private val tempDirs = mutableListOf<File>()
    private val openDbs = mutableListOf<VelunaDatabase>()
    private val coordinators = mutableListOf<CloudSyncCoordinator>()

    private inner class TestDevice(val name: String) {
        val context: Context = ApplicationProvider.getApplicationContext()
        val dir: File = File(context.cacheDir, "coord_${name}_${UUID.randomUUID()}").also {
            it.mkdirs()
            tempDirs.add(it)
        }
        val db: VelunaDatabase = Room.databaseBuilder(
            context,
            VelunaDatabase::class.java,
            File(dir, "veluna.db").absolutePath
        ).allowMainThreadQueries().build().also { openDbs.add(it) }
        val dao = db.velunaDao()
        val vault = SecretVault(File(dir, "vault.bin"), InMemoryAesGcmVaultCipher())
        val ds = PreferenceDataStoreFactory.create(produceFile = { File(dir, "prefs.preferences_pb") })
        val prefsRepo = UserPrefsRepository(context, vault, ds)
        val repo = VelunaRepository(
            context = context,
            dao = dao,
            prefsRepo = prefsRepo,
            innerTube = InnerTubeClient(OkHttpClient()),
            database = db
        )

        fun createCoordinator(): CloudSyncCoordinator {
            val cloudRepo = CloudSyncRepository(firestore, auth)
            return CloudSyncCoordinator(
                context = context,
                dao = dao,
                prefsRepo = prefsRepo,
                repository = repo,
                cloudRepo = cloudRepo,
                auth = auth,
                ioDispatcher = Dispatchers.IO
            ).also { coordinators.add(it) }
        }
    }

    @After
    override fun tearDownFirebase() {
        coordinators.forEach { runCatching { it.shutdown() } }
        coordinators.clear()
        openDbs.forEach { runCatching { it.close() } }
        openDbs.clear()
        tempDirs.forEach { it.deleteRecursively() }
        tempDirs.clear()
        super.tearDownFirebase()
    }

    @Test
    fun sameTitledPlaylistsAcrossDevices_coexistViaDistinctCloudKeys() : Unit = runBlocking {
        val email = "dup_title_${UUID.randomUUID()}@test.com"
        signInTestUser(email)

        val devA = TestDevice("A")
        val devB = TestDevice("B")

        val plAId = devA.repo.createPlaylist("Workout", "Morning lift")
        devA.repo.addTrackToPlaylist(
            plAId,
            SongItem(videoId = "trk_a1", title = "Energy", artists = "Artist A")
        )
        val coordA = devA.createCoordinator()
        withTimeout(SYNC_TIMEOUT_MS) {
            assertThat(coordA.syncNowAndAwait()).isTrue()
        }
        coordA.shutdown()

        // Device B creates its own local playlist also named "Workout" before its first sync
        val plBId = devB.repo.createPlaylist("Workout", "Evening run")
        devB.repo.addTrackToPlaylist(
            plBId,
            SongItem(videoId = "trk_b1", title = "Sprint", artists = "Artist B")
        )
        val coordB = devB.createCoordinator()
        withTimeout(SYNC_TIMEOUT_MS) {
            assertThat(coordB.syncNowAndAwait()).isTrue()
        }
        coordB.shutdown()

        // Device A syncs again and must receive Device B's distinct playlist alongside its own
        val coordA2 = devA.createCoordinator()
        withTimeout(SYNC_TIMEOUT_MS) {
            assertThat(coordA2.syncNowAndAwait()).isTrue()
        }
        coordA2.shutdown()

        val listA = devA.dao.getPlaylistsOnce()
        val listB = devB.dao.getPlaylistsOnce()
        assertThat(listA).hasSize(2)
        assertThat(listB).hasSize(2)
        assertThat(listA.map { it.cloudKey }.toSet()).isEqualTo(listB.map { it.cloudKey }.toSet())
        assertThat(listA.map { it.description }).containsExactly("Morning lift", "Evening run")
    }

    @Test
    fun playlistTrackMutationsAndReorders_incrementRevisionAndPropagateAcrossDevices() : Unit = runBlocking {
        val email = "pl_rev_${UUID.randomUUID()}@test.com"
        signInTestUser(email)

        val devA = TestDevice("A")
        val devB = TestDevice("B")

        val plId = devA.repo.createPlaylist("Mix", "Initial")
        val s1 = SongItem(videoId = "trk_1", title = "One", artists = "Artist 1")
        val s2 = SongItem(videoId = "trk_2", title = "Two", artists = "Artist 2")
        val s3 = SongItem(videoId = "trk_3", title = "Three", artists = "Artist 3")
        devA.repo.addTrackToPlaylist(plId, s1)
        devA.repo.addTrackToPlaylist(plId, s2)
        val revBeforeSync = devA.dao.getPlaylistById(plId)!!.syncRevision
        assertThat(revBeforeSync).isEqualTo(3L)

        val coordA = devA.createCoordinator()
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(coordA.syncNowAndAwait()).isTrue() }

        // Mutate playlist: add s3, reorder index 2 -> 0, remove s2, rename
        devA.repo.addTrackToPlaylist(plId, s3)
        devA.repo.reorderPlaylistTracksByIndex(plId, 2, 0)
        devA.repo.removeTrackFromPlaylist(plId, "trk_2")
        devA.repo.updatePlaylist(plId, "Mix Reordered", "Updated", null)

        val revAfterMutations = devA.dao.getPlaylistById(plId)!!.syncRevision
        assertThat(revAfterMutations).isEqualTo(7L)

        withTimeout(SYNC_TIMEOUT_MS) { assertThat(coordA.syncNowAndAwait()).isTrue() }
        coordA.shutdown()

        val coordB = devB.createCoordinator()
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(coordB.syncNowAndAwait()).isTrue() }
        coordB.shutdown()

        val syncedOnB = devB.dao.getPlaylistsOnce().single()
        assertThat(syncedOnB.title).isEqualTo("Mix Reordered")
        assertThat(syncedOnB.syncRevision).isEqualTo(7L)
        assertThat(devB.dao.getPlaylistTracks(syncedOnB.id).map { it.videoId })
            .containsExactly("trk_3", "trk_1")
            .inOrder()
    }

    @Test
    fun offlinePlaylistConflict_newerClientUpdatedAtWinsAndBumpsRevisionMonotonically() : Unit = runBlocking {
        val email = "conflict_${UUID.randomUUID()}@test.com"
        signInTestUser(email)

        val devA = TestDevice("A")
        val devB = TestDevice("B")

        val plIdA = devA.repo.createPlaylist("Shared", "v1")
        devA.repo.addTrackToPlaylist(plIdA, SongItem(videoId = "t1", title = "T1", artists = "A"))
        val coordA = devA.createCoordinator()
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(coordA.syncNowAndAwait()).isTrue() }
        coordA.shutdown()

        val coordB = devB.createCoordinator()
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(coordB.syncNowAndAwait()).isTrue() }
        coordB.shutdown()

        val cloudKey = devA.dao.getPlaylistsOnce().single().cloudKey
        val plIdB = devB.dao.getPlaylistsOnce().single().id

        // Device B edits earlier while offline (and syncs once on reconnect)
        val earlier = System.currentTimeMillis() - 5_000L
        devB.dao.updatePlaylist(plIdB, "Device B Earlier", "B won rev", null, earlier)
        val coordB2 = devB.createCoordinator()
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(coordB2.syncNowAndAwait()).isTrue() }
        coordB2.shutdown()

        // Device A edits with a strictly newer timestamp while still at equal or lower revision
        val newer = earlier + 4_000L
        devA.dao.updatePlaylist(plIdA, "Device A Newer Winner", "Newer timestamp", null, newer)
        val coordA2 = devA.createCoordinator()
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(coordA2.syncNowAndAwait()).isTrue() }
        coordA2.shutdown()

        // Device B converges cleanly to Device A's newer edit
        val coordB3 = devB.createCoordinator()
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(coordB3.syncNowAndAwait()).isTrue() }
        coordB3.shutdown()

        val finalA = devA.dao.getPlaylistsOnce().single { it.cloudKey == cloudKey }
        val finalB = devB.dao.getPlaylistsOnce().single { it.cloudKey == cloudKey }
        assertThat(finalA.title).isEqualTo("Device A Newer Winner")
        assertThat(finalB.title).isEqualTo("Device A Newer Winner")
        assertThat(finalB.syncRevision).isAtLeast(finalA.syncRevision)
    }

    @Test
    fun tasteSeedTimestampPreservedAndDeletionTombstonesPropagateWithoutResurrection() : Unit = runBlocking {
        val email = "seeds_${UUID.randomUUID()}@test.com"
        signInTestUser(email)

        val devA = TestDevice("A")
        val devB = TestDevice("B")

        val realPastTs = System.currentTimeMillis() - 60_000L
        val artistSeed = TasteSeed(id = "art_daft", name = "Daft Punk", updatedAtMs = realPastTs)
        val legacyZeroSeed = TasteSeed(id = "art_zero", name = "Justice", updatedAtMs = 0L)

        devA.prefsRepo.saveTasteProfile(
            TasteProfile(
                onboardingCompleted = true,
                selectedGenres = listOf("Electronic"),
                seedArtists = listOf(artistSeed, legacyZeroSeed)
            ),
            touchProfileTimestamp = true
        )

        val coordA = devA.createCoordinator()
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(coordA.syncNowAndAwait()).isTrue() }
        coordA.shutdown()

        val localAfterStamp = devA.prefsRepo.tasteProfileFlow.first()
        val daftLocal = localAfterStamp.seedArtists.first { it.name == "Daft Punk" }
        val justiceLocal = localAfterStamp.seedArtists.first { it.name == "Justice" }
        assertThat(daftLocal.updatedAtMs).isEqualTo(realPastTs)
        assertThat(justiceLocal.updatedAtMs).isGreaterThan(0L)

        // Device B pulls seeds cleanly
        val coordB = devB.createCoordinator()
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(coordB.syncNowAndAwait()).isTrue() }
        coordB.shutdown()
        assertThat(devB.prefsRepo.tasteProfileFlow.first().seedArtists.map { it.name })
            .containsExactly("Daft Punk", "Justice")

        // Delete "Justice" locally on Device B via baseline diff (offline removal) and sync
        devB.prefsRepo.updateTasteProfile(touchProfileTimestamp = true) { profile ->
            profile.copy(seedArtists = profile.seedArtists.filterNot { it.name == "Justice" })
        }
        val coordB2 = devB.createCoordinator()
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(coordB2.syncNowAndAwait()).isTrue() }
        coordB2.shutdown()

        // Device A syncs and must drop "Justice" without resurrecting it
        val coordA2 = devA.createCoordinator()
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(coordA2.syncNowAndAwait()).isTrue() }
        coordA2.shutdown()

        assertThat(devA.prefsRepo.tasteProfileFlow.first().seedArtists.map { it.name })
            .containsExactly("Daft Punk")
    }

    @Test
    fun profilePrefsBidirectionalLww_syncsCrossDevicePrefsWhileLeavingDeviceOnlyPrefsUntouched() : Unit = runBlocking {
        val email = "prefs_lww_${UUID.randomUUID()}@test.com"
        signInTestUser(email)

        val devA = TestDevice("A")
        val devB = TestDevice("B")

        devA.prefsRepo.saveTasteProfile(TasteProfile(onboardingCompleted = true, selectedGenres = listOf("Jazz")), true)
        devA.prefsRepo.setThemeMode(ThemeMode.LIGHT)
        devA.prefsRepo.setAudioQuality(AudioQuality.LOW)
        devA.prefsRepo.setPlaybackSpeed(1.5f) // device-only

        val coordA = devA.createCoordinator()
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(coordA.syncNowAndAwait()).isTrue() }
        coordA.shutdown()

        // Device B starts with onboardingCompleted = false and localPrefsTs = 0 so it adopts initial cloud profile
        devB.prefsRepo.setPlaybackSpeed(0.75f) // device-only
        devB.prefsRepo.setClassicPlayerLayout(true) // device-only
        val coordB = devB.createCoordinator()
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(coordB.syncNowAndAwait()).isTrue() }
        coordB.shutdown()

        val bInitial = devB.prefsRepo.prefsFlow.first()
        assertThat(bInitial.themeMode).isEqualTo(ThemeMode.LIGHT)
        assertThat(bInitial.audioQuality).isEqualTo(AudioQuality.LOW)
        assertThat(bInitial.playbackSpeed).isEqualTo(0.75f)
        assertThat(bInitial.classicPlayerLayout).isTrue()

        // Device B is now established; update synced prefs on B with newer timestamp
        devB.prefsRepo.setThemePreset(VelunaThemePreset.TOKYO_NIGHT)
        devB.prefsRepo.setThemeMode(ThemeMode.DARK)
        devB.prefsRepo.setAudioQuality(AudioQuality.HIGH)
        devB.prefsRepo.setAutoplay(false)
        val coordB2 = devB.createCoordinator()
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(coordB2.syncNowAndAwait()).isTrue() }
        coordB2.shutdown()

        // Device A syncs without local changes -> adopts B's newer prefs while preserving A's playbackSpeed 1.5f
        val coordA2 = devA.createCoordinator()
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(coordA2.syncNowAndAwait()).isTrue() }
        coordA2.shutdown()

        val aFinal = devA.prefsRepo.prefsFlow.first()
        assertThat(aFinal.themePreset).isEqualTo(VelunaThemePreset.TOKYO_NIGHT)
        assertThat(aFinal.themeMode).isEqualTo(ThemeMode.DARK)
        assertThat(aFinal.audioQuality).isEqualTo(AudioQuality.HIGH)
        assertThat(aFinal.autoplay).isFalse()
        assertThat(aFinal.playbackSpeed).isEqualTo(1.5f)
        assertThat(aFinal.classicPlayerLayout).isFalse()
    }

    @Test
    fun offlineMutationsAndDeletionsSurviveRestartAndTrackUnsyncedCountAccurately() : Unit = runBlocking {
        val email = "restart_${UUID.randomUUID()}@test.com"
        val uid = signInTestUser(email)

        val dev = TestDevice("Restart")
        val plKeepId = dev.repo.createPlaylist("Keep", "")
        val plDeleteId = dev.repo.createPlaylist("Remove Offline", "")
        val songLike = SongItem(videoId = "trk_like", title = "Liked", artists = "Artist")
        dev.repo.toggleLike(songLike)
        dev.repo.blockArtist("Noisy Act")

        // Initial sync commits watermarks and baselines
        val coord1 = dev.createCoordinator()
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(coord1.syncNowAndAwait()).isTrue() }
        assertThat(coord1.countUnsyncedChangesFor(uid)).isEqualTo(0)
        coord1.shutdown()

        // Simulate app offline mutations before restart while watermarks > 0
        dev.repo.addTrackToPlaylist(plKeepId, SongItem(videoId = "trk_added", title = "Added", artists = "Artist"))
        dev.repo.deletePlaylist(plDeleteId)
        dev.repo.toggleLike(songLike) // unlike
        dev.repo.unblockArtist("Noisy Act")

        // Recreate coordinator against existing DB & DataStore (simulated cold restart)
        val coord2 = dev.createCoordinator()
        val pendingBeforeSync = coord2.countUnsyncedChangesFor(uid)
        assertThat(pendingBeforeSync).isGreaterThan(0)

        withTimeout(SYNC_TIMEOUT_MS) { assertThat(coord2.syncNowAndAwait()).isTrue() }
        assertThat(coord2.countUnsyncedChangesFor(uid)).isEqualTo(0)
        coord2.shutdown()

        // Verify cloud actually reflects offline deletions and updates
        val cloudSnap = CloudSyncRepository(firestore, auth).fetchAccountSnapshot()
        assertThat(cloudSnap.likedSongs.first { it.videoId == "trk_like" }.deleted).isTrue()
        assertThat(cloudSnap.hiddenArtists.none { !it.deleted }).isTrue()
        val deletedCloudPl = cloudSnap.playlists.firstOrNull { it.playlist.title == "Remove Offline" }
        assertThat(deletedCloudPl == null || deletedCloudPl.playlist.deleted).isTrue()
        val keptCloudPl = cloudSnap.playlists.single { !it.playlist.deleted }
        assertThat(keptCloudPl.subcollectionTracks.map { it.videoId }).containsExactly("trk_added")
    }

    @Test
    fun signOutVsAccountDeletion_preservesDeviceDataOnSignOutAndWipesCredentialsOnDeletion(): Unit = runBlocking {
        val email = "wipe_${UUID.randomUUID()}@test.com"
        val uid = signInTestUser(email)

        val dev = TestDevice("WipeCheck")
        // Seed synced tables
        val plId = dev.repo.createPlaylist("Synced List", "Should clear")
        dev.repo.addTrackToPlaylist(plId, SongItem(videoId = "trk_pl", title = "T", artists = "A"))
        dev.repo.toggleLike(SongItem(videoId = "trk_s", title = "Liked", artists = "A"))
        dev.repo.blockArtist("Blocked Band")

        // Seed device-only tables & SecretVault scrobble credentials
        dev.dao.insertListen(ListenEntity(videoId = "trk_l", playedAt = System.currentTimeMillis(), secs = 200))
        dev.dao.upsertListenSong(ListenSongEntity("trk_l", """{"videoId":"trk_l","title":"Listen","artists":"A"}"""))
        dev.dao.insertSavedAlbum(SavedAlbumEntity("alb_1", "Saved Album", "Sub", null))
        dev.dao.insertFollowedArtist(FollowedArtistEntity("art_1", "Followed Artist", null, "1M"))
        dev.dao.upsertShortcut(ShortcutEntity("sc_1", BrowseKind.ALBUM.name, "Shortcut", null, null, 0))
        dev.dao.upsertSearchHistory(SearchHistoryEntity("daft punk"))
        dev.dao.upsertDownload(
            DownloadedTrackEntity("dl_1", "/tmp/dl_1.opus", "opus", 128, 1024L, """{"videoId":"dl_1","title":"DL","artists":"A"}""")
        )
        dev.dao.upsertLocalTracks(listOf(LocalTrackEntity("content://local/1", "Local", "Artist", "Album", "alb")))
        dev.dao.upsertLyricsCache(LyricsCacheEntity("trk_l", """{"plain":"hello"}"""))
        dev.dao.insertPendingScrobble(PendingScrobbleEntity(service = ScrobbleServices.LASTFM, payloadJson = "{}", startedAtSecs = 100L))
        dev.prefsRepo.setScrobblingConfig(
            enabled = true,
            lastFmUser = "alice",
            lastFmKey = "api_key_keep",
            lastFmSecret = "api_secret_keep",
            lastFmSession = "session_keep",
            listenBrainzToken = "lb_token_keep"
        )

        val coord = dev.createCoordinator()
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(coord.syncNowAndAwait()).isTrue() }

        // Mutate locally so countUnsyncedChangesFor(uid) > 0, then signOut() flushes cleanly and wipes synced tables
        dev.repo.createPlaylist("Unsynced Draft", "")
        assertThat(coord.countUnsyncedChangesFor(uid)).isGreaterThan(0)
        coord.signOut()
        val deadline = System.currentTimeMillis() + 12_000L
        while ((coord.authState.value.isSignedIn || coord.authState.value.isAuthBusy) && System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.delay(50L)
        }
        assertThat(coord.authState.value.isSignedIn).isFalse()
        assertThat(coord.authState.value.isAuthBusy).isFalse()
        coord.shutdown()

        // Re-sign in on another device and confirm signOut() flushed "Unsynced Draft" before wiping local state
        signInTestUser(email)
        val cloudAfterSignOut = CloudSyncRepository(firestore, auth).fetchAccountSnapshot()
        assertThat(cloudAfterSignOut.playlists.map { it.playlist.title }).contains("Unsynced Draft")

        // Ordinary sign-out clears ONLY synced tables + cloud watermarks, preserving device history & scrobble secrets
        assertThat(dev.dao.getPlaylistsOnce()).isEmpty()
        assertThat(dev.dao.getLikedSongsOnce()).isEmpty()
        assertThat(dev.dao.getBlockedArtistsOnce()).isEmpty()
        assertThat(dev.dao.getAllTombstones()).isEmpty()
        assertThat(dev.dao.getAllSyncBaselinesForUser(uid)).isEmpty()

        assertThat(dev.dao.getListensSince(0L)).hasSize(1)
        assertThat(dev.dao.getSavedAlbumsOnce()).hasSize(1)
        assertThat(dev.dao.getFollowedArtistsOnce()).hasSize(1)
        assertThat(dev.dao.getShortcutsOnce()).hasSize(1)
        assertThat(dev.dao.getSearchHistoryOnce(10)).hasSize(1)
        assertThat(dev.dao.getDownload("dl_1")).isNotNull()
        assertThat(dev.dao.getLocalTracksOnce()).hasSize(1)
        assertThat(dev.dao.countPendingScrobbles()).isEqualTo(1)
        val afterSignOutPrefs = dev.prefsRepo.prefsFlow.first()
        assertThat(afterSignOutPrefs.scrobblingEnabled).isTrue()
        assertThat(afterSignOutPrefs.lastFmSessionKey).isEqualTo("session_keep")
        assertThat(afterSignOutPrefs.listenBrainzToken).isEqualTo("lb_token_keep")

        // Permanent account deletion clears all account tables & SecretVault while keeping downloads/local/lyrics_cache
        dev.dao.clearAccountData()
        dev.prefsRepo.resetUserKeys()

        assertThat(dev.dao.getListensSince(0L)).isEmpty()
        assertThat(dev.dao.getSavedAlbumsOnce()).isEmpty()
        assertThat(dev.dao.getFollowedArtistsOnce()).isEmpty()
        assertThat(dev.dao.getShortcutsOnce()).isEmpty()
        assertThat(dev.dao.getSearchHistoryOnce(10)).isEmpty()
        assertThat(dev.dao.countPendingScrobbles()).isEqualTo(0)
        val afterDeletePrefs = dev.prefsRepo.prefsFlow.first()
        assertThat(afterDeletePrefs.scrobblingEnabled).isFalse()
        assertThat(afterDeletePrefs.lastFmSessionKey).isEmpty()
        assertThat(afterDeletePrefs.listenBrainzToken).isEmpty()

        assertThat(dev.dao.getDownload("dl_1")).isNotNull()
        assertThat(dev.dao.getLocalTracksOnce()).hasSize(1)
        assertThat(dev.dao.getLyricsCache("trk_l")).isNotNull()
    }

    private companion object {
        const val SYNC_TIMEOUT_MS = 15_000L
    }
}
