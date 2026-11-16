package com.example.data.repository

import android.app.Application
import android.content.Context
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
    fun backupExportRedactsSecrets_andImportClampsDeduplicatesAndSupportsV1() = runBlocking {
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
        assertThat(exportedJson).contains("\"version\":2")

        // Double import of same backup must not duplicate playlists
        val firstImport = repo.importBackupJson(exportedJson).getOrThrow()
        assertThat(firstImport.sourceVersion).isEqualTo(2)
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
    fun computeStats_respectsRangeWindow() = runBlocking {
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
    fun computeStats_allRangeDailySeriesIsChronologicalAndEndsToday() = runBlocking {
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
        assertThat(allStats.dailySeries.first().secs).isEqualTo(1000L)
        assertThat(allStats.dailySeries.last().secs).isEqualTo(300L)
    }
}
