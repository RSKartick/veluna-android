package com.example.data.repository

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.UserPrefsRepository
import com.example.data.local.VelunaDatabase
import com.example.data.local.VelunaUserPrefs
import com.example.data.network.InnerTubeClient
import com.example.data.security.InMemoryAesGcmVaultCipher
import com.example.data.security.SecretVault
import com.example.model.BrowseItem
import com.example.model.BrowseKind
import com.example.model.SongItem
import com.example.model.StatsRange
import com.example.model.TasteProfile
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [34])
class BackupAndCsvTest {

    private lateinit var tempDir: File
    private lateinit var db: VelunaDatabase
    private lateinit var prefsRepo: UserPrefsRepository
    private lateinit var repo: VelunaRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        tempDir = File(context.cacheDir, "backup_test_${UUID.randomUUID()}").apply { mkdirs() }
        db = Room.inMemoryDatabaseBuilder(context, VelunaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val ds = PreferenceDataStoreFactory.create(
            produceFile = { File(tempDir, "prefs.preferences_pb") }
        )
        val vault = SecretVault(File(tempDir, "vault.bin"), InMemoryAesGcmVaultCipher())
        prefsRepo = UserPrefsRepository(context, vault, ds)
        repo = VelunaRepository(
            context = context,
            dao = db.velunaDao(),
            prefsRepo = prefsRepo,
            innerTube = InnerTubeClient(OkHttpClient()),
            database = db
        )
    }

    @After
    fun tearDown() {
        db.close()
        tempDir.deleteRecursively()
    }

    @Test
    fun csvEscapeAndRoundTrip_handlesQuotesCommasAndCommasInArtists() {
        val tracks = listOf(
            SongItem(
                videoId = "trk_1",
                title = "Hello, \"World\"",
                artists = "Artist One, Artist Two",
                album = "Live at \"Studio\"",
                duration = "3:45"
            ),
            SongItem(
                videoId = "trk_2",
                title = "Simple Song",
                artists = "Solo Artist",
                album = null,
                duration = "2:05"
            )
        )

        val csv = VelunaRepository.formatPlaylistAsCsv(tracks)
        assertThat(csv).startsWith("title,artists,album,duration_secs,video_id\n")
        assertThat(csv).contains("\"Hello, \"\"World\"\"\"")

        val queries = VelunaRepository.parseCsvOrM3uQueries(csv)
        assertThat(queries).containsExactly(
            "Hello, \"World\" Artist One, Artist Two",
            "Simple Song Solo Artist"
        ).inOrder()
    }

    @Test
    fun m3uExport_outputsExtM3uAndParsesBack() {
        val tracks = listOf(
            SongItem(videoId = "abc123", title = "Song A", artists = "Artist A", duration = "3:20")
        )
        val m3u = repo.exportPlaylistAsM3u("Roadtrip", tracks)
        assertThat(m3u).contains("#EXTM3U")
        assertThat(m3u).contains("#EXTINF:200,Artist A - Song A")
        assertThat(m3u).contains("https://music.youtube.com/watch?v=abc123")

        val parsed = VelunaRepository.parseCsvOrM3uQueries(m3u)
        assertThat(parsed).containsExactly("Artist A - Song A")
    }

    @Test
    fun m3uExport_keepsMetadataOnSingleLines() {
        val m3u = repo.exportPlaylistAsM3u(
            "List\n#EXTINF:999,Injected",
            listOf(SongItem(videoId = "abc123", title = "Song\r\nhttps://evil.example", artists = "Artist\n#EXTM3U"))
        )

        assertThat(m3u.lines()).containsExactly(
            "#EXTM3U",
            "#PLAYLIST:List #EXTINF:999,Injected",
            "#EXTINF:-1,Artist #EXTM3U - Song https://evil.example",
            "https://music.youtube.com/watch?v=abc123",
            ""
        ).inOrder()
    }

    @Test
    fun csvExport_makesFormulaLikeMetadataInert() {
        val csv = VelunaRepository.formatPlaylistAsCsv(
            listOf(
                SongItem(
                    videoId = "abc123",
                    title = "=HYPERLINK(\"https://example.com\")",
                    artists = "  +SUM(1,1)"
                ),
                SongItem(
                    videoId = "def456",
                    title = "\t=CMD()",
                    artists = "\r@SUM(1)"
                )
            )
        )

        assertThat(csv).contains("'=HYPERLINK")
        assertThat(csv).contains("'  +SUM")
        assertThat(csv).contains("'\t=CMD()")
        assertThat(csv).contains("'\r@SUM(1)")
    }

    @Test
    fun backupExportRedactsSecrets_andImportClampsDeduplicatesAndSupportsV1() : Unit = runBlocking {
        prefsRepo.setScrobblingConfig(
            enabled = true,
            lastFmUser = "alice",
            lastFmKey = "secret_api_key",
            lastFmSecret = "secret_api_secret",
            lastFmSession = "secret_session_key",
            listenBrainzToken = "secret_lb_token"
        )
        prefsRepo.setCornerRadius(16)
        prefsRepo.saveTasteProfile(TasteProfile(onboardingCompleted = true, selectedGenres = listOf("Jazz")))
        val plId = repo.createPlaylist("Chill Vibes", "Evening mix")
        repo.updatePlaylist(
            plId,
            "Chill Vibes",
            "Evening mix",
            "https://cdn.example.com/chill-cover.jpg"
        )
        val createdPl = db.velunaDao().getPlaylistById(plId)!!
        val song = SongItem(videoId = "yt_1", title = "Blue in Green", artists = "Miles Davis", duration = "5:27")
        repo.addTrackToPlaylist(plId, song)
        repo.toggleLike(song)
        repo.blockArtist("Noise Band")
        repo.toggleShortcut(BrowseItem(BrowseKind.PLAYLIST, "VL123", "Pinned Mix"))
        repo.recordSearchQuery("miles davis")

        val exportedJson = repo.exportBackupJson(prefsRepo.prefsFlow.first())
        assertThat(exportedJson).doesNotContain("secret_api_key")
        assertThat(exportedJson).doesNotContain("secret_api_secret")
        assertThat(exportedJson).doesNotContain("secret_session_key")
        assertThat(exportedJson).doesNotContain("secret_lb_token")
        assertThat(exportedJson).contains("\"version\":3")
        assertThat(exportedJson).contains(createdPl.cloudKey)
        assertThat(exportedJson).contains("https://cdn.example.com/chill-cover.jpg")

        // Double import of same backup must not duplicate playlists
        val firstImport = repo.importBackupJson(exportedJson).getOrThrow()
        assertThat(firstImport.sourceVersion).isEqualTo(3)
        assertThat(firstImport.restoredPlaylists).isEqualTo(0)

        // Out-of-range values are clamped on restore
        val clamped = VelunaRepository.clampBackupPrefs(
            VelunaUserPrefs(
                cornerRadiusDp = 999,
                playbackSpeed = 9.0f,
                customAccentHex = "not-a-hex;drop",
                lastFmApiKey = "leaked_in_hand_edited_file"
            )
        )
        assertThat(clamped.cornerRadiusDp).isEqualTo(24)
        assertThat(clamped.playbackSpeed).isEqualTo(2.0f)
        assertThat(clamped.customAccentHex).isNull()
        assertThat(clamped.lastFmApiKey).isEmpty()

        // v1 backup still imports cleanly and strips unsafe file:// URLs
        val v1Json = """
            {
              "version": 1,
              "exportedAt": 1700000000000,
              "prefs": {
                "cornerRadiusDp": 2,
                "playbackSpeed": 0.1
              },
              "playlists": [
                {
                  "title": "V1 Imported",
                  "description": "From v1",
                  "tracks": [
                    { "videoId": "v1_trk", "title": "Old Gold", "artists": "Classic", "thumbnail": "file:///data/local/tmp/leak.png" }
                  ]
                }
              ],
              "likedSongs": [
                { "videoId": "v1_like", "title": "V1 Like", "artists": "Artist", "thumbnail": "http://insecure.example/cover.png" }
              ],
              "blockedArtists": ["Spam Artist"]
            }
        """.trimIndent()

        val v1Result = repo.importBackupJson(v1Json).getOrThrow()
        assertThat(v1Result.sourceVersion).isEqualTo(1)
        assertThat(v1Result.restoredPlaylists).isEqualTo(1)
        val restoredPrefs = prefsRepo.prefsFlow.first()
        assertThat(restoredPrefs.cornerRadiusDp).isEqualTo(4)
        assertThat(restoredPrefs.playbackSpeed).isEqualTo(0.5f)
        val likedAfterRestore = repo.likedSongsFlow.first()
        assertThat(likedAfterRestore.find { it.videoId == "v1_like" }?.thumbnail).isNull()
    }

    @Test
    fun computeStats_respectsRangeWindow() : Unit = runBlocking {
        val now = System.currentTimeMillis()
        val oldMs = now - 60L * 24L * 3600L * 1000L // 60 days ago
        val recentMs = now - 2L * 24L * 3600L * 1000L // 2 days ago

        val dao = db.velunaDao()
        dao.insertListen(com.example.data.local.ListenEntity(videoId = "old", playedAt = oldMs, secs = 1000))
        dao.insertListen(com.example.data.local.ListenEntity(videoId = "recent", playedAt = recentMs, secs = 120))

        val monthStats = repo.computeStats(StatsRange.MONTH)
        assertThat(monthStats.totalSecs).isEqualTo(120L)
        assertThat(monthStats.totalPlays).isEqualTo(1)

        val allStats = repo.computeStats(StatsRange.ALL)
        assertThat(allStats.totalSecs).isEqualTo(1120L)
        assertThat(allStats.totalPlays).isEqualTo(2)
    }

    @Test
    fun computeStats_allRangeDailySeriesIsChronologicalAndEndsToday() : Unit = runBlocking {
        val zone = ZoneId.systemDefault()
        val dayFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)
        val today = LocalDate.now(zone)
        val sixtyDaysAgo = today.minusDays(60)

        val dao = db.velunaDao()
        dao.insertListen(
            com.example.data.local.ListenEntity(
                videoId = "old60",
                playedAt = sixtyDaysAgo.atTime(12, 0).atZone(zone).toInstant().toEpochMilli(),
                secs = 1000L
            )
        )
        dao.insertListen(
            com.example.data.local.ListenEntity(
                videoId = "today1",
                playedAt = today.atTime(12, 0).atZone(zone).toInstant().toEpochMilli(),
                secs = 300L
            )
        )

        val allStats = repo.computeStats(StatsRange.ALL)
        val keys = allStats.dailySeries.map { it.dateKey }

        assertThat(keys).hasSize(61)
        assertThat(keys.zipWithNext().all { (a, b) -> a < b }).isTrue()
        assertThat(keys.first()).isEqualTo(sixtyDaysAgo.format(dayFmt))
        assertThat(keys.last()).isEqualTo(today.format(dayFmt))
        assertThat(keysLastCheck(allStats)).isTrue()
    }

    private fun keysLastCheck(allStats: com.example.model.StatsSummary): Boolean {
        return allStats.dailySeries.first().secs == 1000L && allStats.dailySeries.last().secs == 300L
    }

    @Test
    fun parseCsvOrM3uQueries_stripsUtf8BomForM3uAndCsv() {
        val bomM3u = "\uFEFF#EXTM3U\n#EXTINF:210,Daft Punk - Digital Love\nhttps://music.youtube.com/watch?v=x1\n"
        assertThat(VelunaRepository.parseCsvOrM3uQueries(bomM3u))
            .containsExactly("Daft Punk - Digital Love")

        val bomCsv = "\uFEFFTrack Name,Artist Name(s)\nMidnight City,M83\n"
        assertThat(VelunaRepository.parseCsvOrM3uQueries(bomCsv))
            .containsExactly("Midnight City M83")
    }

    @Test
    fun removeTrackFromPlaylist_deletesTargetAndKeepsOthers(): Unit = runBlocking {
        val plId = repo.createPlaylist("Mix", "Desc")
        val songA = SongItem(videoId = "vidA", title = "Track A", artists = "Artist A", duration = "3:00")
        val songB = SongItem(videoId = "vidB", title = "Track B", artists = "Artist B", duration = "3:15")
        repo.addTrackToPlaylist(plId, songA)
        repo.addTrackToPlaylist(plId, songB)

        repo.removeTrackFromPlaylist(plId, "vidA")

        val loaded = repo.loadPlaylistPage("${com.example.model.LOCAL_PLAYLIST_PREFIX}$plId").getOrThrow()
        assertThat(loaded.items.map { it.videoId }).containsExactly("vidB")
    }

    @Test
    fun backupRestore_v2CompatibilityAndCoverUriSanitization() : Unit = runBlocking {
        val v2Json = """
            {
              "version": 2,
              "exportedAt": 1700000000000,
              "prefs": { "cornerRadiusDp": 18 },
              "playlists": [
                {
                  "title": "V2 Safe Cover",
                  "description": "Keeps https cover",
                  "coverUri": "https://images.example.com/v2-art.png",
                  "tracks": [
                    { "videoId": "v2_trk1", "title": "Song 1", "artists": "Artist 1" }
                  ]
                },
                {
                  "title": "V2 Unsafe Cover",
                  "description": "Strips file cover",
                  "coverUri": "file:///data/data/secret.png",
                  "tracks": [
                    { "videoId": "v2_trk2", "title": "Song 2", "artists": "Artist 2" }
                  ]
                }
              ],
              "likedSongs": [],
              "blockedArtists": []
            }
        """.trimIndent()

        val result = repo.importBackupJson(v2Json).getOrThrow()
        assertThat(result.sourceVersion).isEqualTo(2)
        assertThat(result.restoredPlaylists).isEqualTo(2)

        val playlists = db.velunaDao().getPlaylistsOnce()
        val safe = playlists.first { it.title == "V2 Safe Cover" }
        val unsafe = playlists.first { it.title == "V2 Unsafe Cover" }
        assertThat(safe.cloudKey).startsWith("pl_")
        assertThat(unsafe.cloudKey).startsWith("pl_")
        assertThat(safe.cloudKey).isNotEqualTo(unsafe.cloudKey)
        assertThat(safe.coverUri).isEqualTo("https://images.example.com/v2-art.png")
        assertThat(unsafe.coverUri).isNull()

        // Re-importing same v2 backup deduplicates by (normalizedTitle, trackSignature)
        val second = repo.importBackupJson(v2Json).getOrThrow()
        assertThat(second.restoredPlaylists).isEqualTo(0)
    }

    @Test
    fun backupRestore_deduplicatesByCloudKeyEvenIfPlaylistRenamed() : Unit = runBlocking {
        val plId = repo.createPlaylist("Original Title", "Desc")
        val song = SongItem(videoId = "yt_dedupe", title = "Song", artists = "Artist")
        repo.addTrackToPlaylist(plId, song)
        val exported = repo.exportBackupJson(prefsRepo.prefsFlow.first())

        repo.updatePlaylist(plId, "Renamed Locally", "New desc", null)
        val reimport = repo.importBackupJson(exported).getOrThrow()
        assertThat(reimport.restoredPlaylists).isEqualTo(0)
        assertThat(db.velunaDao().getPlaylistsOnce()).hasSize(1)
        assertThat(db.velunaDao().getPlaylistsOnce().first().title).isEqualTo("Renamed Locally")
    }

    @Test
    fun backupRestore_malformedJsonFailsWithoutModifyingDatabase() : Unit = runBlocking {
        repo.createPlaylist("Keep Me", "Safe")
        val result = repo.importBackupJson("{ not valid json !!!")
        assertThat(result.isFailure).isTrue()
        assertThat(db.velunaDao().getPlaylistsOnce().map { it.title }).containsExactly("Keep Me")
    }

    @Test
    fun backupRestore_rollsBackRoomPlaylistsWhenDataStoreWriteFails() : Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val realDs = PreferenceDataStoreFactory.create(
            produceFile = { File(tempDir, "failing_prefs.preferences_pb") }
        )
        var shouldFailWrite = false
        val failingDs = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = realDs.data
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
                if (shouldFailWrite) throw IOException("Simulated DataStore write failure")
                return realDs.updateData(transform)
            }
        }
        val failingVault = SecretVault(File(tempDir, "failing_vault.bin"), InMemoryAesGcmVaultCipher())
        val failingPrefsRepo = UserPrefsRepository(context, failingVault, failingDs)
        failingPrefsRepo.setCornerRadius(14)

        val localRepo = VelunaRepository(
            context = context,
            dao = db.velunaDao(),
            prefsRepo = failingPrefsRepo,
            innerTube = InnerTubeClient(OkHttpClient()),
            database = db
        )
        val existingId = localRepo.createPlaylist("Preexisting", "Stay intact")
        localRepo.addTrackToPlaylist(existingId, SongItem(videoId = "vid_keep", title = "Keep", artists = "Artist"))

        val backupJson = """
            {
              "version": 3,
              "exportedAt": 1700000000000,
              "prefs": { "cornerRadiusDp": 22 },
              "playlists": [
                {
                  "cloudKey": "pl_rollback_test",
                  "title": "Should Rollback",
                  "description": "Should not survive DataStore failure",
                  "tracks": [
                    { "videoId": "vid_tmp", "title": "Temp", "artists": "Artist" }
                  ]
                }
              ],
              "likedSongs": [],
              "blockedArtists": []
            }
        """.trimIndent()

        shouldFailWrite = true
        val importResult = localRepo.importBackupJson(backupJson)
        assertThat(importResult.isFailure).isTrue()

        val playlistsAfter = db.velunaDao().getPlaylistsOnce()
        assertThat(playlistsAfter.map { it.title }).containsExactly("Preexisting")
        assertThat(db.velunaDao().getPlaylistTracks(existingId).map { it.videoId }).containsExactly("vid_keep")
        assertThat(failingPrefsRepo.prefsFlow.first().cornerRadiusDp).isEqualTo(14)
    }

    @Test
    fun backupRestore_rejectsInvalidPayloadUpfrontWithoutMutatingExistingData() : Unit = runBlocking {
        val plId = repo.createPlaylist("Safe List", "Intact")
        val existingSong = SongItem(videoId = "yt_safe_1", title = "Keep", artists = "Artist")
        repo.addTrackToPlaylist(plId, existingSong)
        repo.toggleLike(existingSong)
        prefsRepo.setCornerRadius(14)

        val badVersion = """{ "version": 99, "prefs": {}, "playlists": [], "likedSongs": [], "blockedArtists": [] }"""
        assertThat(repo.importBackupJson(badVersion).isFailure).isTrue()

        val badCloudKey = """
            {
              "version": 3,
              "prefs": { "cornerRadiusDp": 20 },
              "playlists": [
                { "cloudKey": "LOCAL_bad", "title": "Bad", "description": "", "tracks": [] }
              ],
              "likedSongs": [],
              "blockedArtists": []
            }
        """.trimIndent()
        assertThat(repo.importBackupJson(badCloudKey).isFailure).isTrue()

        val badCover = """
            {
              "version": 3,
              "prefs": { "cornerRadiusDp": 20 },
              "playlists": [
                {
                  "cloudKey": "pl_bad_cover",
                  "title": "Bad Cover",
                  "description": "",
                  "coverImageBase64": "${android.util.Base64.encodeToString("NOT_AN_IMAGE".toByteArray(), android.util.Base64.NO_WRAP)}",
                  "tracks": []
                }
              ],
              "likedSongs": [],
              "blockedArtists": []
            }
        """.trimIndent()
        assertThat(repo.importBackupJson(badCover).isFailure).isTrue()

        assertThat(db.velunaDao().getPlaylistsOnce().map { it.title }).containsExactly("Safe List")
        assertThat(prefsRepo.prefsFlow.first().cornerRadiusDp).isEqualTo(14)
    }

    @Test
    fun backupRestore_rollsBackAllTenRoomTablesAndDeletesCreatedCoverFilesOnFailure() : Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val coversDir = File(context.filesDir, "playlist_covers")
        coversDir.deleteRecursively()
        val dao = db.velunaDao()

        // Populate all 10 backed-up Room tables + pre-existing rows that backup will overwrite
        val origPlId = repo.createPlaylist("Orig Pl", "Orig desc")
        dao.insertPlaylistTrack(
            com.example.data.local.LocalPlaylistTrackEntity(
                playlistId = origPlId,
                videoId = "orig_vid",
                songJson = repo.encodeSong(SongItem(videoId = "orig_vid", title = "Orig Track", artists = "Orig Artist")),
                position = 0,
                addedAt = 1700000000000L
            )
        )
        dao.insertLikedSong(
            com.example.data.local.LikedSongEntity(
                videoId = "orig_like",
                songJson = repo.encodeSong(SongItem(videoId = "orig_like", title = "Orig Like", artists = "Artist")),
                likedAt = 1700000000000L
            )
        )
        dao.insertBlockedArtist(com.example.data.local.BlockedArtistEntity("Orig Blocked"))
        dao.insertSavedAlbum(com.example.data.local.SavedAlbumEntity("alb_orig", "Orig Album", "Sub", null, 100L))
        dao.insertFollowedArtist(com.example.data.local.FollowedArtistEntity("art_orig", "Orig Artist", null, "10K", 100L))
        dao.upsertShortcut(com.example.data.local.ShortcutEntity("sc_orig", BrowseKind.ALBUM.name, "Orig Shortcut", null, null, 0))
        dao.upsertSearchHistory(com.example.data.local.SearchHistoryEntity("orig query", 100L))
        dao.upsertListenSong(
            com.example.data.local.ListenSongEntity(
                "orig_listen",
                repo.encodeSong(SongItem(videoId = "orig_listen", title = "Orig Listen", artists = "Artist"))
            )
        )
        dao.insertListen(com.example.data.local.ListenEntity(videoId = "orig_listen", playedAt = 1000L, secs = 90L))
        prefsRepo.setCornerRadius(12)

        val pngBytes = valid1x1PngBytes()
        val b64 = android.util.Base64.encodeToString(pngBytes, android.util.Base64.NO_WRAP)
        val backupWithNewData = """
            {
              "version": 3,
              "exportedAt": 1700000005000,
              "prefs": { "cornerRadiusDp": 24 },
              "playlists": [
                {
                  "cloudKey": "pl_new_import",
                  "title": "New Imported Pl",
                  "description": "Will fail before prefs commit",
                  "coverImageBase64": "$b64",
                  "tracks": [
                    { "videoId": "new_vid", "title": "New Track", "artists": "New Artist" }
                  ]
                }
              ],
              "likedSongs": [
                { "videoId": "orig_like", "title": "Overwritten Title", "artists": "New Artist" },
                { "videoId": "new_like", "title": "Brand New Like", "artists": "New Artist" }
              ],
              "blockedArtists": ["New Blocked"],
              "savedAlbums": [
                { "albumId": "alb_orig", "title": "Overwritten Album" },
                { "albumId": "alb_new", "title": "New Album" }
              ],
              "followedArtists": [
                { "channelId": "art_new", "name": "New Followed" }
              ],
              "shortcuts": [
                { "id": "sc_new", "kind": "ALBUM", "title": "New Shortcut" }
              ],
              "searchHistory": [
                { "query": "new query", "searchedAt": 2000 }
              ],
              "listens": [
                {
                  "videoId": "new_listen",
                  "playedAt": 3000,
                  "secs": 120,
                  "song": { "videoId": "new_listen", "title": "New Listen", "artists": "New Artist" }
                }
              ]
            }
        """.trimIndent()

        repo.onBeforeDataStoreRestore = {
            throw java.io.IOException("Simulated crash right after Room writes")
        }
        val res = try {
            repo.importBackupJson(backupWithNewData)
        } finally {
            repo.onBeforeDataStoreRestore = null
        }

        assertThat(res.isFailure).isTrue()
        assertThat(dao.getPlaylistsOnce().map { it.title }).containsExactly("Orig Pl")
        assertThat(dao.getPlaylistTracks(origPlId).map { it.videoId }).containsExactly("orig_vid")
        assertThat(dao.getLikedSongsOnce().map { it.videoId }).containsExactly("orig_like")
        assertThat(repo.decodeSong(dao.getLikedSongsOnce().single().songJson)?.title).isEqualTo("Orig Like")
        assertThat(dao.getBlockedArtistsOnce().map { it.name }).containsExactly("Orig Blocked")
        assertThat(dao.getSavedAlbumsOnce().single().title).isEqualTo("Orig Album")
        assertThat(dao.getFollowedArtistsOnce().map { it.channelId }).containsExactly("art_orig")
        assertThat(dao.getShortcutsOnce().map { it.id }).containsExactly("sc_orig")
        assertThat(dao.getSearchHistoryOnce(50).map { it.query }).containsExactly("orig query")
        assertThat(dao.getListenSongsByIds(listOf("orig_listen", "new_listen")).map { it.videoId })
            .containsExactly("orig_listen")
        assertThat(dao.getListensSince(0L).map { it.videoId }).containsExactly("orig_listen")
        assertThat(prefsRepo.prefsFlow.first().cornerRadiusDp).isEqualTo(12)
        // Newly materialized cover file must be rolled back / cleaned up
        assertThat(coversDir.listFiles()?.toList().orEmpty()).isEmpty()
    }

    @Test
    fun portableCoverExportAndImportRoundtrip_andRejectInvalidManagedCover() : Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val coversDir = File(context.filesDir, "playlist_covers").apply { mkdirs() }
        coversDir.listFiles()?.forEach { it.delete() }

        val plId = repo.createPlaylist("Portable Art", "Local cover backup")
        val pngBytes = valid1x1PngBytes()
        val managedFile = File(coversDir, "seed_cover.png").apply { writeBytes(pngBytes) }

        repo.updatePlaylist(plId, "Portable Art", "Local cover backup", android.net.Uri.fromFile(managedFile).toString())
        val updated = db.velunaDao().getPlaylistById(plId)!!
        assertThat(updated.coverUri).isEqualTo(android.net.Uri.fromFile(managedFile).toString())

        val exportedJson = repo.exportBackupJson(prefsRepo.prefsFlow.first())
        assertThat(exportedJson).contains("coverImageBase64")

        // Attempting to attach an external /etc/passwd or corrupt file URI must reject rather than persist it
        val externalPath = File(tempDir, "outside.png").apply { writeBytes(pngBytes) }
        repo.updatePlaylist(plId, "Portable Art", "Bad file uri", android.net.Uri.fromFile(externalPath).toString())
        assertThat(db.velunaDao().getPlaylistById(plId)!!.coverUri).isNull()

        // Wipe local DB and managed cover, then restore from backup JSON -> cover file is recreated atomically
        db.velunaDao().clearSyncedAccountData()
        coversDir.listFiles()?.forEach { it.delete() }

        val restored = repo.importBackupJson(exportedJson).getOrThrow()
        assertThat(restored.restoredPlaylists).isEqualTo(1)
        val restoredPl = db.velunaDao().getPlaylistsOnce().single()
        assertThat(restoredPl.coverUri).startsWith("file://")
        val restoredCoverFile = File(android.net.Uri.parse(restoredPl.coverUri!!).path!!)
        assertThat(restoredCoverFile.exists()).isTrue()
        assertThat(restoredCoverFile.readBytes()).isEqualTo(pngBytes)
    }

    private fun valid1x1PngBytes(): ByteArray {
        val bmp = android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888)
        val baos = java.io.ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, baos)
        bmp.recycle()
        return baos.toByteArray()
    }
}

