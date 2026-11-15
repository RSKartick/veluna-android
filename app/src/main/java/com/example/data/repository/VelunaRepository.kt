package com.example.data.repository

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import androidx.room.withTransaction
import com.example.data.local.BlockedArtistEntity
import com.example.data.local.DownloadedTrackEntity
import com.example.data.local.FollowedArtistEntity
import com.example.data.local.LikedSongEntity
import com.example.data.local.ListenEntity
import com.example.data.local.ListenSongEntity
import com.example.data.local.LocalPlaylistEntity
import com.example.data.local.LocalPlaylistTrackEntity
import com.example.data.local.LocalTrackEntity
import com.example.data.local.SavedAlbumEntity
import com.example.data.local.SearchHistoryEntity
import com.example.data.local.ShortcutEntity
import com.example.data.local.UserPrefsRepository
import com.example.data.local.VelunaDao
import com.example.data.local.VelunaDatabase
import com.example.data.local.VelunaUserPrefs
import com.example.data.network.InnerTubeClient
import com.example.model.BrowseItem
import com.example.model.BrowseKind
import com.example.model.DailyListening
import com.example.model.HistoryGroup
import com.example.model.LIKED_MUSIC_ID
import com.example.model.LOCAL_PLAYLIST_PREFIX
import com.example.model.LOCAL_SONG_PREFIX
import com.example.model.ON_REPEAT_ID
import com.example.model.PlaylistPage
import com.example.model.SongItem
import com.example.model.StatsRange
import com.example.model.StatsSummary
import com.example.model.TasteProfile
import com.example.model.TopAlbumStat
import com.example.model.TopArtistStat
import com.example.model.TopSongStat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Serializable
data class BackupSavedAlbum(
    val albumId: String,
    val title: String,
    val subtitle: String? = null,
    val thumbnail: String? = null,
    val savedAt: Long = 0L
)

@Serializable
data class BackupFollowedArtist(
    val channelId: String,
    val name: String,
    val thumbnail: String? = null,
    val subscribers: String? = null,
    val followedAt: Long = 0L
)

@Serializable
data class BackupShortcut(
    val id: String,
    val kind: String,
    val title: String,
    val subtitle: String? = null,
    val thumbnail: String? = null,
    val position: Int = 0
)

@Serializable
data class BackupSearchHistoryItem(
    val query: String,
    val searchedAt: Long = 0L
)

@Serializable
data class BackupListenEntry(
    val videoId: String,
    val playedAt: Long,
    val secs: Long,
    val song: SongItem? = null
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class VelunaBackupPayload(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val version: Int = 2,
    val exportedAt: Long = System.currentTimeMillis(),
    val prefs: VelunaUserPrefs,
    val playlists: List<BackupPlaylist> = emptyList(),
    val likedSongs: List<SongItem> = emptyList(),
    val blockedArtists: List<String> = emptyList(),
    val tasteProfile: TasteProfile? = null,
    val savedAlbums: List<BackupSavedAlbum> = emptyList(),
    val followedArtists: List<BackupFollowedArtist> = emptyList(),
    val shortcuts: List<BackupShortcut> = emptyList(),
    val searchHistory: List<BackupSearchHistoryItem> = emptyList(),
    val listens: List<BackupListenEntry> = emptyList()
)

@Serializable
data class BackupPlaylist(
    val title: String,
    val description: String,
    val tracks: List<SongItem>
)

data class BackupRestoreResult(
    val restoredPlaylists: Int,
    val sourceVersion: Int
)

class VelunaRepository(
    private val context: Context,
    private val dao: VelunaDao,
    private val prefsRepo: UserPrefsRepository,
    val innerTube: InnerTubeClient,
    private val database: VelunaDatabase? = null
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun encodeSong(song: SongItem): String = json.encodeToString(song)
    fun decodeSong(raw: String): SongItem? = runCatching { json.decodeFromString<SongItem>(raw) }.getOrNull()

    val shortcutsFlow: Flow<List<BrowseItem>> = dao.observeShortcuts().map { rows ->
        rows.map {
            BrowseItem(
                kind = runCatching { BrowseKind.valueOf(it.kind) }.getOrDefault(BrowseKind.PLAYLIST),
                id = it.id,
                title = it.title,
                subtitle = it.subtitle,
                thumbnail = it.thumbnail
            )
        }
    }

    val playlistsBrowseFlow: Flow<List<BrowseItem>> = combine(
        dao.observePlaylists(),
        dao.observePlaylistTrackCounts(),
        dao.observeLikedSongs()
    ) { playlists, counts, liked ->
        val countMap = counts.associateBy { it.playlistId }
        val result = mutableListOf<BrowseItem>()
        result.add(
            BrowseItem(
                kind = BrowseKind.PLAYLIST,
                id = ON_REPEAT_ID,
                title = "On Repeat",
                subtitle = "Your most replayed tracks"
            )
        )
        result.add(
            BrowseItem(
                kind = BrowseKind.PLAYLIST,
                id = LIKED_MUSIC_ID,
                title = "Liked Music",
                subtitle = "${liked.size} songs"
            )
        )
        playlists.forEach { pl ->
            val info = countMap[pl.id]
            val thumb = pl.coverUri ?: info?.firstSongJson?.let(::decodeSong)?.thumbnail
            result.add(
                BrowseItem(
                    kind = BrowseKind.PLAYLIST,
                    id = "$LOCAL_PLAYLIST_PREFIX${pl.id}",
                    title = pl.title,
                    subtitle = "${info?.count ?: 0} tracks",
                    thumbnail = thumb
                )
            )
        }
        result
    }

    val likedSongsFlow: Flow<List<SongItem>> = dao.observeLikedSongs().map { list ->
        list.mapNotNull { decodeSong(it.songJson)?.copy(rating = com.example.model.Rating.LIKE) }
    }

    val likedVideoIdsFlow: Flow<Set<String>> = dao.observeLikedVideoIds()
        .map { it.toHashSet() }
        .distinctUntilChanged()

    val savedAlbumsFlow: Flow<List<BrowseItem>> = dao.observeSavedAlbums().map { list ->
        list.map {
            BrowseItem(
                kind = BrowseKind.ALBUM,
                id = it.albumId,
                title = it.title,
                subtitle = it.subtitle,
                thumbnail = it.thumbnail
            )
        }
    }

    val followedArtistsFlow: Flow<List<BrowseItem>> = dao.observeFollowedArtists().map { list ->
        list.map {
            BrowseItem(
                kind = BrowseKind.ARTIST,
                id = it.channelId,
                title = it.name,
                subtitle = it.subscribers,
                thumbnail = it.thumbnail
            )
        }
    }

    val localTracksFlow: Flow<List<SongItem>> = dao.observeLocalTracks().map { list ->
        list.map {
            SongItem(
                videoId = "$LOCAL_SONG_PREFIX${it.uri}",
                title = it.title,
                artists = it.artist,
                album = it.album,
                duration = formatSecs(it.durationSecs),
                thumbnail = it.coverUri,
                isLocal = true,
                localUri = it.uri
            )
        }
    }

    val downloadedTracksFlow: Flow<List<SongItem>> = dao.observeDownloads().map { list ->
        list.mapNotNull { entity ->
            decodeSong(entity.songJson)?.copy(localUri = entity.filePath)
        }
    }

    val searchHistoryFlow: Flow<List<String>> = dao.observeSearchHistory().map { list ->
        list.map { it.query }
    }

    val blockedArtistsFlow: Flow<List<BlockedArtistEntity>> = dao.observeBlockedArtists()

    fun observeIsAlbumSaved(albumId: String): Flow<Boolean> = dao.observeIsAlbumSaved(albumId)
    fun observeIsArtistFollowed(channelId: String): Flow<Boolean> = dao.observeIsArtistFollowed(channelId)

    suspend fun toggleLike(song: SongItem): Boolean {
        if (song.isLocal || song.videoId.startsWith(LOCAL_SONG_PREFIX)) return false
        val liked = dao.isSongLiked(song.videoId)
        if (liked) {
            dao.deleteLikedSong(song.videoId)
            return false
        } else {
            dao.insertLikedSong(LikedSongEntity(song.videoId, encodeSong(song)))
            return true
        }
    }

    suspend fun isLiked(videoId: String): Boolean {
        if (videoId.startsWith(LOCAL_SONG_PREFIX)) return false
        return dao.isSongLiked(videoId)
    }

    suspend fun toggleSaveAlbum(album: com.example.model.AlbumPage) {
        val saved = dao.isAlbumSaved(album.id)
        setAlbumSaved(album, !saved)
    }

    suspend fun setAlbumSaved(album: com.example.model.AlbumPage, save: Boolean) {
        if (save) {
            dao.insertSavedAlbum(
                SavedAlbumEntity(
                    albumId = album.id,
                    title = album.title,
                    subtitle = album.artist ?: album.subtitle,
                    thumbnail = album.thumbnail
                )
            )
        } else {
            dao.deleteSavedAlbum(album.id)
        }
    }

    suspend fun setArtistFollowed(artist: com.example.model.ArtistPage, follow: Boolean) {
        if (follow) {
            dao.insertFollowedArtist(
                FollowedArtistEntity(
                    channelId = artist.channelId,
                    name = artist.name,
                    thumbnail = artist.thumbnail ?: artist.avatar,
                    subscribers = artist.subscribers
                )
            )
        } else {
            dao.deleteFollowedArtist(artist.channelId)
        }
    }

    suspend fun createPlaylist(title: String, description: String = ""): Long {
        val now = System.currentTimeMillis()
        return dao.insertPlaylist(
            LocalPlaylistEntity(
                title = title.trim(),
                description = description.trim(),
                createdAt = now,
                updatedAt = now
            )
        )
    }

    suspend fun updatePlaylist(playlistId: Long, title: String, description: String, coverUri: String?) {
        dao.updatePlaylist(playlistId, title.trim(), description.trim(), coverUri, System.currentTimeMillis())
    }

    suspend fun deletePlaylist(playlistId: Long) {
        dao.clearPlaylistTracks(playlistId)
        dao.deletePlaylist(playlistId)
        dao.deleteShortcut("$LOCAL_PLAYLIST_PREFIX$playlistId")
    }

    suspend fun addTrackToPlaylist(playlistId: Long, song: SongItem, skipDuplicates: Boolean = true): Boolean {
        if (song.isLocal || song.videoId.startsWith(LOCAL_SONG_PREFIX)) return false
        val existing = dao.getPlaylistTracks(playlistId)
        if (skipDuplicates && existing.any { it.videoId == song.videoId }) return false
        val pos = dao.nextPlaylistPosition(playlistId)
        dao.insertPlaylistTrack(
            LocalPlaylistTrackEntity(
                playlistId = playlistId,
                videoId = song.videoId,
                songJson = encodeSong(song),
                position = pos
            )
        )
        return true
    }

    suspend fun removeTrackFromPlaylist(playlistId: Long, videoId: String) {
        dao.deletePlaylistTrack(playlistId, videoId)
    }

    suspend fun removeDuplicateTracksFromPlaylist(playlistId: Long): Int {
        val tracks = dao.getPlaylistTracks(playlistId)
        val seen = mutableSetOf<String>()
        var removed = 0
        tracks.forEach { row ->
            if (!seen.add(row.videoId)) {
                dao.deletePlaylistTrackByRowId(row.id)
                removed++
            }
        }
        return removed
    }

    suspend fun loadPlaylistPage(id: String): Result<PlaylistPage> = withContext(Dispatchers.IO) {
        when {
            id == ON_REPEAT_ID -> {
                val thirtyDaysAgo = System.currentTimeMillis() - 30L * 24 * 3600 * 1000
                val topRows = dao.topSongsSince(thirtyDaysAgo, 25)
                val songsMap = if (topRows.isNotEmpty()) {
                    dao.getListenSongsByIds(topRows.map { it.videoId }).associateBy { it.videoId }
                } else {
                    emptyMap()
                }
                val tracks = topRows.mapNotNull { row ->
                    songsMap[row.videoId]?.let { decodeSong(it.songJson) }
                }
                Result.success(
                    PlaylistPage(
                        id = ON_REPEAT_ID,
                        title = "On Repeat",
                        subtitle = "${tracks.size} songs • Most played in the last 30 days",
                        thumbnail = tracks.firstOrNull()?.thumbnail,
                        items = tracks,
                        owned = false,
                        isLocal = true
                    )
                )
            }
            id == LIKED_MUSIC_ID -> {
                val tracks = dao.getLikedSongsOnce()
                    .filter { !it.videoId.startsWith(LOCAL_SONG_PREFIX) }
                    .mapNotNull { decodeSong(it.songJson)?.copy(rating = com.example.model.Rating.LIKE) }
                Result.success(
                    PlaylistPage(
                        id = LIKED_MUSIC_ID,
                        title = "Liked Music",
                        subtitle = "${tracks.size} songs • Auto playlist",
                        thumbnail = tracks.firstOrNull()?.thumbnail,
                        items = tracks,
                        owned = false,
                        isLocal = true
                    )
                )
            }
            id.startsWith(LOCAL_PLAYLIST_PREFIX) -> {
                val numId = id.removePrefix(LOCAL_PLAYLIST_PREFIX).toLongOrNull()
                    ?: return@withContext Result.failure(IllegalArgumentException("Invalid playlist id"))
                val pl = dao.getPlaylistById(numId)
                    ?: return@withContext Result.failure(IllegalStateException("Playlist not found"))
                val rows = dao.getPlaylistTracks(numId)
                val tracks = rows.mapNotNull { decodeSong(it.songJson)?.copy(addedAt = it.addedAt) }
                Result.success(
                    PlaylistPage(
                        id = id,
                        title = pl.title,
                        subtitle = "${tracks.size} tracks",
                        description = pl.description,
                        thumbnail = pl.coverUri ?: tracks.firstOrNull()?.thumbnail,
                        coverUri = pl.coverUri,
                        items = tracks,
                        owned = true,
                        isLocal = true
                    )
                )
            }
            else -> innerTube.getPlaylist(id)
        }
    }

    suspend fun toggleShortcut(item: BrowseItem) {
        dao.upsertShortcut(
            ShortcutEntity(
                id = item.id,
                kind = item.kind.name,
                title = item.title,
                subtitle = item.subtitle,
                thumbnail = item.thumbnail,
                position = 0
            )
        )
    }

    suspend fun removeShortcut(id: String) = dao.deleteShortcut(id)

    suspend fun recordSearchQuery(query: String) {
        if (query.isNotBlank()) dao.upsertSearchHistory(SearchHistoryEntity(query.trim()))
    }

    suspend fun removeSearchQuery(query: String) = dao.deleteSearchHistory(query)
    suspend fun clearSearchHistory() = dao.clearSearchHistory()

    suspend fun blockArtist(name: String, channelId: String? = null) {
        if (name.isNotBlank()) dao.insertBlockedArtist(BlockedArtistEntity(name.trim(), channelId))
    }

    suspend fun unblockArtist(name: String) = dao.deleteBlockedArtist(name)

    // --- Local Audio Files (Play Policy compliant SAF picking) ---
    suspend fun importAudioUris(uris: List<Uri>): Int = withContext(Dispatchers.IO) {
        val entities = mutableListOf<LocalTrackEntity>()
        val coversDir = File(context.filesDir, "local_covers").apply { mkdirs() }

        for (uri in uris) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                    ?: uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.')
                    ?: "Audio Track"
                val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
                    ?: "Unknown Artist"
                val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                    ?: "Local Audio"
                val trackNo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
                    ?.substringBefore('/')?.toIntOrNull() ?: 0
                val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L

                val embeddedArt = retriever.embeddedPicture
                var coverUri: String? = null
                if (embeddedArt != null && embeddedArt.isNotEmpty()) {
                    val file = File(coversDir, "${uri.toString().hashCode()}.jpg")
                    file.writeBytes(embeddedArt)
                    coverUri = Uri.fromFile(file).toString()
                }

                entities.add(
                    LocalTrackEntity(
                        uri = uri.toString(),
                        title = title,
                        artist = artist,
                        album = album,
                        albumKey = "${album.lowercase()}::${artist.lowercase()}",
                        trackNo = trackNo,
                        durationSecs = durationMs / 1000L,
                        coverUri = coverUri
                    )
                )
            } catch (_: Throwable) {
                // Skip unreadable URI
            } finally {
                runCatching { retriever.release() }
            }
        }
        if (entities.isNotEmpty()) {
            dao.upsertLocalTracks(entities)
        }
        entities.size
    }

    suspend fun importFolderTree(treeUri: Uri): Int = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        val audioUris = mutableListOf<Uri>()
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri)
        )
        context.contentResolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            ),
            null,
            null,
            null
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val mimeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (cursor.moveToNext()) {
                val docId = cursor.getString(idIdx)
                val mime = cursor.getString(mimeIdx).orEmpty()
                if (mime.startsWith("audio/")) {
                    audioUris.add(DocumentsContract.buildDocumentUriUsingTree(treeUri, docId))
                }
            }
        }
        importAudioUris(audioUris)
    }

    suspend fun removeLocalTrack(uri: String) = dao.deleteLocalTrack(uri)

    // --- Playlist Import (Spotify CSV / M3U / YouTube URL) ---
    suspend fun importFromYouTubeUrl(urlOrId: String, onProgress: (String) -> Unit): Result<Long> = withContext(Dispatchers.IO) {
        val listId = extractYouTubePlaylistId(urlOrId)
            ?: return@withContext Result.failure(IllegalArgumentException("Could not find a YouTube playlist ID in that link."))
        onProgress("Fetching playlist from YouTube Music…")
        val page = innerTube.getPlaylist(listId).getOrElse { return@withContext Result.failure(it) }
        val newId = createPlaylist(page.title, page.description.orEmpty())
        page.items.forEach { song ->
            addTrackToPlaylist(newId, song, skipDuplicates = false)
        }
        Result.success(newId)
    }

    suspend fun importFromFileUri(
        uri: Uri,
        defaultTitle: String,
        onProgress: (done: Int, total: Int, status: String) -> Unit
    ): Result<Long> = withContext(Dispatchers.IO) {
        val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            ?: return@withContext Result.failure(IllegalStateException("Could not read file"))

        val queries = parseCsvOrM3uQueries(text)
        if (queries.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("No tracks found in file (supports Exportify CSV or M3U)."))
        }

        val playlistId = createPlaylist(defaultTitle, "Imported playlist")
        queries.forEachIndexed { idx, q ->
            onProgress(idx + 1, queries.size, "Matching: $q")
            val match = innerTube.searchSongs(q).getOrNull()?.firstOrNull()
            if (match != null) {
                addTrackToPlaylist(playlistId, match, skipDuplicates = true)
            }
        }
        Result.success(playlistId)
    }

    fun exportPlaylistAsM3u(title: String, tracks: List<SongItem>): String {
        val sb = StringBuilder("#EXTM3U\n#PLAYLIST:$title\n")
        tracks.forEach { s ->
            val secs = s.durationSeconds.takeIf { it > 0 } ?: -1
            sb.append("#EXTINF:$secs,${s.artists} - ${s.title}\n")
            if (s.isLocal && s.localUri != null) {
                sb.append("${s.localUri}\n")
            } else {
                sb.append("https://music.youtube.com/watch?v=${s.videoId}\n")
            }
        }
        return sb.toString()
    }

    fun exportPlaylistAsCsv(tracks: List<SongItem>): String = formatPlaylistAsCsv(tracks)

    // --- Backup & Restore (v2 with v1 compatibility) ---
    suspend fun exportBackupJson(prefs: VelunaUserPrefs): String = withContext(Dispatchers.IO) {
        val sanitizedPrefs = redactPrefsForExport(prefs)
        val playlistRows = dao.getPlaylistsOnce()
        val likedRows = dao.getLikedSongsOnce()
            .filter { !it.videoId.startsWith(LOCAL_SONG_PREFIX) }
            .mapNotNull { decodeSong(it.songJson) }
        val blockedRows = dao.getBlockedArtistsOnce().map { it.name }
        val tasteProfile = prefsRepo.tasteProfileFlow.first()
        val savedAlbums = dao.getSavedAlbumsOnce().map {
            BackupSavedAlbum(it.albumId, it.title, it.subtitle, it.thumbnail, it.savedAt)
        }
        val followedArtists = dao.getFollowedArtistsOnce().map {
            BackupFollowedArtist(it.channelId, it.name, it.thumbnail, it.subscribers, it.followedAt)
        }
        val shortcuts = dao.getShortcutsOnce().map {
            BackupShortcut(it.id, it.kind, it.title, it.subtitle, it.thumbnail, it.position)
        }
        val searchHistory = dao.getSearchHistoryOnce(100).map {
            BackupSearchHistoryItem(it.query, it.searchedAt)
        }
        val ninetyDaysAgo = System.currentTimeMillis() - 90L * 24L * 3600L * 1000L
        val recentListens = dao.getListensSince(ninetyDaysAgo)
        val listenSongsMap = if (recentListens.isNotEmpty()) {
            dao.getListenSongsByIds(recentListens.map { it.videoId }.distinct())
                .associate { it.videoId to decodeSong(it.songJson) }
        } else emptyMap()
        val listenEntries = recentListens.map {
            BackupListenEntry(
                videoId = it.videoId,
                playedAt = it.playedAt,
                secs = it.secs,
                song = listenSongsMap[it.videoId]
            )
        }

        val playlists = playlistRows.map { pl ->
            val tracks = dao.getPlaylistTracks(pl.id).mapNotNull { decodeSong(it.songJson) }
            BackupPlaylist(pl.title, pl.description, tracks)
        }

        json.encodeToString(
            VelunaBackupPayload(
                version = 2,
                exportedAt = System.currentTimeMillis(),
                prefs = sanitizedPrefs,
                playlists = playlists,
                likedSongs = likedRows,
                blockedArtists = blockedRows,
                tasteProfile = tasteProfile,
                savedAlbums = savedAlbums,
                followedArtists = followedArtists,
                shortcuts = shortcuts,
                searchHistory = searchHistory,
                listens = listenEntries
            )
        )
    }

    suspend fun importBackupJson(rawJson: String): Result<BackupRestoreResult> = withContext(Dispatchers.IO) {
        runCatching {
            val payload = json.decodeFromString<VelunaBackupPayload>(rawJson)
            val blockWrite: suspend () -> Int = {
                prefsRepo.applyBackup(clampBackupPrefs(payload.prefs))
                payload.tasteProfile?.let { prefsRepo.saveTasteProfile(it) }

                payload.likedSongs
                    .filter { !it.isLocal && !it.videoId.startsWith(LOCAL_SONG_PREFIX) }
                    .forEach { song ->
                        dao.insertLikedSong(LikedSongEntity(song.videoId, encodeSong(song)))
                    }
                payload.blockedArtists.forEach { name ->
                    if (name.isNotBlank()) {
                        dao.insertBlockedArtist(BlockedArtistEntity(name.trim()))
                    }
                }
                payload.savedAlbums.forEach { al ->
                    dao.insertSavedAlbum(SavedAlbumEntity(al.albumId, al.title, al.subtitle, al.thumbnail, al.savedAt))
                }
                payload.followedArtists.forEach { ar ->
                    dao.insertFollowedArtist(FollowedArtistEntity(ar.channelId, ar.name, ar.thumbnail, ar.subscribers, ar.followedAt))
                }
                payload.shortcuts.forEach { sc ->
                    dao.upsertShortcut(ShortcutEntity(sc.id, sc.kind, sc.title, sc.subtitle, sc.thumbnail, sc.position))
                }
                payload.searchHistory.take(100).forEach { sh ->
                    dao.upsertSearchHistory(SearchHistoryEntity(sh.query, sh.searchedAt))
                }
                payload.listens.forEach { l ->
                    l.song?.let { song ->
                        dao.upsertListenSong(ListenSongEntity(l.videoId, encodeSong(song)))
                    }
                    dao.insertListen(ListenEntity(videoId = l.videoId, playedAt = l.playedAt, secs = l.secs))
                }

                // Deduplicate playlists against existing ones using (normalizedTitle, trackSignature)
                val existingSignatures = dao.getPlaylistsOnce().map { existing ->
                    val existingTracks = dao.getPlaylistTracks(existing.id).map { it.videoId }
                    normalizePlaylistTitle(existing.title) to playlistTrackSignature(existingTracks)
                }.toMutableSet()

                var count = 0
                payload.playlists.forEach { pl ->
                    val sigKey = normalizePlaylistTitle(pl.title) to playlistTrackSignature(pl.tracks.map { it.videoId })
                    if (existingSignatures.add(sigKey)) {
                        val now = System.currentTimeMillis()
                        val id = dao.insertPlaylist(
                            LocalPlaylistEntity(
                                title = pl.title.trim().ifBlank { "Playlist" },
                                description = pl.description.trim(),
                                createdAt = now,
                                updatedAt = now
                            )
                        )
                        pl.tracks.forEachIndexed { idx, song ->
                            if (!song.isLocal && !song.videoId.startsWith(LOCAL_SONG_PREFIX)) {
                                dao.insertPlaylistTrack(
                                    LocalPlaylistTrackEntity(
                                        playlistId = id,
                                        videoId = song.videoId,
                                        songJson = encodeSong(song),
                                        position = idx
                                    )
                                )
                            }
                        }
                        count++
                    }
                }
                count
            }

            val restoredCount = if (database != null) {
                database.withTransaction { blockWrite() }
            } else {
                blockWrite()
            }
            BackupRestoreResult(restoredPlaylists = restoredCount, sourceVersion = payload.version)
        }
    }

    // --- Stats & History ---
    suspend fun computeStats(range: StatsRange): StatsSummary = withContext(Dispatchers.IO) {
        val nowMs = System.currentTimeMillis()
        val daysCount = range.days
        val sinceMs = if (daysCount != null) nowMs - daysCount.toLong() * 86_400_000L else 0L

        val inWindow = dao.getListensSince(sinceMs)
        val totals = dao.getListenTotalsSince(sinceMs)
        val prevSecs = if (daysCount != null) {
            val prevStart = sinceMs - daysCount.toLong() * 86_400_000L
            dao.sumListenSecsInRange(prevStart, sinceMs)
        } else null

        val zone = ZoneId.systemDefault()
        val dayFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)
        val labelFmt = DateTimeFormatter.ofPattern("MMM d", Locale.US)

        val dailyMap = linkedMapOf<String, Long>()
        val targetDays = daysCount ?: 30
        val today = LocalDate.now(zone)
        for (i in (targetDays - 1) downTo 0) {
            val d = today.minusDays(i.toLong())
            dailyMap[d.format(dayFmt)] = 0L
        }

        val heatmap = LongArray(7 * 24)
        inWindow.forEach { l ->
            val zdt = Instant.ofEpochMilli(l.playedAt).atZone(zone)
            val key = zdt.toLocalDate().format(dayFmt)
            if (key in dailyMap || daysCount == null) {
                dailyMap[key] = (dailyMap[key] ?: 0L) + l.secs
            }
            val dow = zdt.dayOfWeek.value % 7
            heatmap[dow * 24 + zdt.hour] += l.secs
        }

        val dailySeries = dailyMap.entries.toList().takeLast(if (targetDays > 90) 90 else targetDays).map { (key, secs) ->
            val d = runCatching { LocalDate.parse(key, dayFmt) }.getOrNull()
            DailyListening(dateKey = key, label = d?.format(labelFmt) ?: key, secs = secs)
        }

        val topAggRows = dao.topSongsSince(sinceMs, 100)
        val songMap = if (topAggRows.isNotEmpty()) {
            dao.getListenSongsByIds(topAggRows.map { it.videoId })
                .mapNotNull { row -> decodeSong(row.songJson)?.let { row.videoId to it } }
                .toMap()
        } else emptyMap()

        val topSongs = topAggRows.mapNotNull { agg ->
            val song = songMap[agg.videoId] ?: return@mapNotNull null
            TopSongStat(song = song, plays = agg.plays, secs = agg.secs)
        }

        val artistAgg = mutableMapOf<String, TopArtistStat>()
        val albumAgg = mutableMapOf<String, TopAlbumStat>()
        topSongs.forEach { st ->
            val primaryArtist = st.song.artists.split(",").firstOrNull()?.trim().orEmpty().ifBlank { "Unknown Artist" }
            val curA = artistAgg[primaryArtist]
            artistAgg[primaryArtist] = if (curA == null) {
                TopArtistStat(primaryArtist, st.song.artistId, st.song.thumbnail, st.plays, st.secs)
            } else {
                curA.copy(plays = curA.plays + st.plays, secs = curA.secs + st.secs)
            }

            val alId = st.song.albumId
            val alTitle = st.song.album
            if (!alId.isNullOrBlank() && !alTitle.isNullOrBlank()) {
                val curAl = albumAgg[alId]
                albumAgg[alId] = if (curAl == null) {
                    TopAlbumStat(alId, alTitle, primaryArtist, st.song.thumbnail, st.plays, st.secs)
                } else {
                    curAl.copy(plays = curAl.plays + st.plays, secs = curAl.secs + st.secs)
                }
            }
        }

        val allTimestamps = dao.getAllListenTimestampsAsc()
        val (curStreak, bestStreak) = computeStreaksFromTimestamps(allTimestamps, zone)

        StatsSummary(
            range = range,
            totalSecs = totals.totalSecs,
            prevSecs = prevSecs,
            totalPlays = totals.totalPlays,
            uniqueSongs = dao.countUniqueSongsSince(sinceMs),
            uniqueArtists = artistAgg.size,
            currentStreak = curStreak,
            bestStreak = bestStreak,
            dailySeries = dailySeries,
            hourlyHeatmap = heatmap.toList(),
            topSongs = topSongs.take(10),
            topArtists = artistAgg.values.sortedByDescending { it.secs }.take(8),
            topAlbums = albumAgg.values.sortedByDescending { it.secs }.take(6)
        )
    }

    fun observeHistoryGroups(): Flow<List<HistoryGroup>> = combine(
        dao.observeAllListens().distinctUntilChanged { old, new ->
            old.size == new.size &&
                old.firstOrNull()?.id == new.firstOrNull()?.id &&
                old.lastOrNull()?.id == new.lastOrNull()?.id
        },
        dao.observeListenSongs().distinctUntilChanged { old, new ->
            old.size == new.size &&
                old.firstOrNull()?.videoId == new.firstOrNull()?.videoId &&
                old.lastOrNull()?.videoId == new.lastOrNull()?.videoId
        }
    ) { listens, songs ->
        val map = songs.mapNotNull { row -> decodeSong(row.songJson)?.let { row.videoId to it } }.toMap()
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val yesterday = today.minusDays(1)
        val fmt = DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.getDefault())

        val grouped = linkedMapOf<String, MutableList<Pair<Long, SongItem>>>()
        listens.forEach { l ->
            val song = map[l.videoId] ?: return@forEach
            val date = Instant.ofEpochMilli(l.playedAt).atZone(zone).toLocalDate()
            val title = when (date) {
                today -> "Today"
                yesterday -> "Yesterday"
                else -> date.format(fmt)
            }
            grouped.getOrPut(title) { mutableListOf() }.add(l.playedAt to song)
        }
        grouped.entries.map { HistoryGroup(it.key, it.value) }
    }.flowOn(Dispatchers.Default)

    private fun computeStreaksFromTimestamps(timestamps: List<Long>, zone: ZoneId): Pair<Int, Int> {
        if (timestamps.isEmpty()) return 0 to 0
        val days = timestamps.map { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
            .distinct().sorted()
        var best = 1
        var run = 1
        for (i in 1 until days.size) {
            if (days[i] == days[i - 1].plusDays(1)) {
                run++
                if (run > best) best = run
            } else {
                run = 1
            }
        }
        val today = LocalDate.now(zone)
        var current = 0
        var cursor = if (days.last() == today || days.last() == today.minusDays(1)) days.last() else return 0 to best
        val daySet = days.toHashSet()
        while (daySet.contains(cursor)) {
            current++
            cursor = cursor.minusDays(1)
        }
        return current to best
    }

    private fun extractYouTubePlaylistId(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.startsWith("PL") || trimmed.startsWith("VL") || trimmed.startsWith("OLAK")) return trimmed
        val match = Regex("""[?&]list=([A-Za-z0-9_\-]+)""").find(trimmed)
        return match?.groupValues?.getOrNull(1)
    }

    private fun formatSecs(secs: Long): String {
        if (secs <= 0) return "0:00"
        val m = secs / 60
        val s = secs % 60
        return "%d:%02d".format(m, s)
    }

    companion object {
        fun redactPrefsForExport(prefs: VelunaUserPrefs): VelunaUserPrefs = prefs.copy(
            lastFmApiKey = "",
            lastFmApiSecret = "",
            lastFmSessionKey = "",
            listenBrainzToken = ""
        )

        fun clampBackupPrefs(prefs: VelunaUserPrefs): VelunaUserPrefs = redactPrefsForExport(
            prefs.copy(
                cornerRadiusDp = prefs.cornerRadiusDp.coerceIn(4, 24),
                playbackSpeed = prefs.playbackSpeed.coerceIn(0.5f, 2.0f)
            )
        )

        fun normalizePlaylistTitle(title: String): String =
            title.trim().lowercase().replace(Regex("\\s+"), " ")

        fun playlistTrackSignature(videoIds: List<String>): String {
            val sorted = videoIds.sorted().joinToString(",")
            val bytes = MessageDigest.getInstance("SHA-256").digest(sorted.toByteArray(Charsets.UTF_8))
            return bytes.joinToString("") { "%02x".format(it) }
        }

        fun csvEscape(s: String): String {
            return if (s.contains(',') || s.contains('"') || s.contains('\n') || s.contains('\r')) {
                "\"" + s.replace("\"", "\"\"") + "\""
            } else {
                s
            }
        }

        fun formatPlaylistAsCsv(tracks: List<SongItem>): String {
            val header = "title,artists,album,duration_secs,video_id"
            val rows = tracks.map { s ->
                listOf(
                    csvEscape(s.title),
                    csvEscape(s.artists),
                    csvEscape(s.album.orEmpty()),
                    s.durationSeconds.coerceAtLeast(0L).toString(),
                    csvEscape(s.videoId)
                ).joinToString(",")
            }
            return if (rows.isEmpty()) header else header + "\n" + rows.joinToString("\n")
        }

        fun parseCsvOrM3uQueries(text: String): List<String> {
            val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.isEmpty()) return emptyList()

            if (lines.first().startsWith("#EXTM3U")) {
                return lines.filter { it.startsWith("#EXTINF:") }
                    .mapNotNull { it.substringAfter(',', "").trim().takeIf(String::isNotBlank) }
                    .take(150)
            }

            val headerCols = splitCsvLine(lines.first()).map { it.lowercase() }
            val trackIdx = headerCols.indexOfFirst { "track" in it || "title" in it || "song" in it }.takeIf { it >= 0 } ?: 0
            val artistIdx = headerCols.indexOfFirst { "artist" in it }.takeIf { it >= 0 } ?: 1

            return lines.drop(1).mapNotNull { line ->
                val cols = splitCsvLine(line)
                val title = cols.getOrNull(trackIdx)?.trim().orEmpty()
                val artist = cols.getOrNull(artistIdx)?.trim().orEmpty()
                if (title.isBlank()) null else "$title $artist".trim()
            }.take(150)
        }

        fun splitCsvLine(line: String): List<String> {
            val out = mutableListOf<String>()
            val cur = StringBuilder()
            var inQuotes = false
            var i = 0
            while (i < line.length) {
                val ch = line[i]
                when {
                    ch == '"' -> {
                        if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
                            cur.append('"')
                            i++
                        } else {
                            inQuotes = !inQuotes
                        }
                    }
                    ch == ',' && !inQuotes -> {
                        out.add(cur.toString())
                        cur.clear()
                    }
                    else -> cur.append(ch)
                }
                i++
            }
            out.add(cur.toString())
            return out
        }
    }
}
