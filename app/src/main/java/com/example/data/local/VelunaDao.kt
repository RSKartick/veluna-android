package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

data class PlaylistTrackCountRow(
    val playlistId: Long,
    val count: Int,
    val firstSongJson: String?
)

data class TopSongAggRow(
    val videoId: String,
    val plays: Int,
    val secs: Long
)

data class ListenTotalsRow(
    val totalPlays: Int,
    val totalSecs: Long
)

@Dao
interface VelunaDao {
    // --- Listens & Stats ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertListen(listen: ListenEntity): Long

    @Query("UPDATE listens SET secs = secs + :deltaSecs WHERE id = :listenId")
    suspend fun addListenSeconds(listenId: Long, deltaSecs: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertListenSong(song: ListenSongEntity)

    @Query("SELECT * FROM listens WHERE playedAt >= :sinceMs ORDER BY playedAt DESC")
    fun observeListensSince(sinceMs: Long): Flow<List<ListenEntity>>

    @Query("SELECT * FROM listens ORDER BY playedAt DESC LIMIT 500")
    fun observeAllListens(): Flow<List<ListenEntity>>

    @Query("SELECT * FROM listens WHERE playedAt >= :sinceMs ORDER BY playedAt ASC")
    suspend fun getListensSince(sinceMs: Long): List<ListenEntity>

    @Query("SELECT playedAt FROM listens ORDER BY playedAt ASC")
    suspend fun getAllListenTimestampsAsc(): List<Long>

    @Query("SELECT * FROM listens ORDER BY playedAt ASC")
    suspend fun getAllListensAsc(): List<ListenEntity>

    @Query("SELECT COUNT(*) AS totalPlays, COALESCE(SUM(secs), 0) AS totalSecs FROM listens WHERE playedAt >= :sinceMs")
    suspend fun getListenTotalsSince(sinceMs: Long): ListenTotalsRow

    @Query("SELECT COALESCE(SUM(secs), 0) FROM listens WHERE playedAt >= :startMs AND playedAt < :endMs")
    suspend fun sumListenSecsInRange(startMs: Long, endMs: Long): Long

    @Query(
        """
        SELECT videoId, COUNT(*) AS plays, COALESCE(SUM(secs), 0) AS secs
        FROM listens
        WHERE playedAt >= :sinceMs
        GROUP BY videoId
        ORDER BY plays DESC, secs DESC
        LIMIT :limit
        """
    )
    suspend fun topSongsSince(sinceMs: Long, limit: Int): List<TopSongAggRow>

    @Query("SELECT COUNT(DISTINCT videoId) FROM listens WHERE playedAt >= :sinceMs")
    suspend fun countUniqueSongsSince(sinceMs: Long): Int

    @Query("SELECT * FROM listen_songs WHERE videoId IN (:videoIds)")
    suspend fun getListenSongsByIds(videoIds: List<String>): List<ListenSongEntity>

    @Query("SELECT * FROM listen_songs")
    fun observeListenSongs(): Flow<List<ListenSongEntity>>

    @Query("SELECT * FROM listen_songs")
    suspend fun getAllListenSongs(): List<ListenSongEntity>

    @Query("DELETE FROM listens")
    suspend fun clearListens()

    @Query("DELETE FROM listen_songs")
    suspend fun clearListenSongs()

    // --- Local Playlists ---
    @Query("SELECT * FROM local_playlists ORDER BY updatedAt DESC")
    fun observePlaylists(): Flow<List<LocalPlaylistEntity>>

    @Query("SELECT * FROM local_playlists ORDER BY updatedAt DESC")
    suspend fun getPlaylistsOnce(): List<LocalPlaylistEntity>

    @Query("SELECT * FROM local_playlists WHERE id = :id")
    suspend fun getPlaylistById(id: Long): LocalPlaylistEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylist(playlist: LocalPlaylistEntity): Long

    @Query("UPDATE local_playlists SET title = :title, description = :description, coverUri = :coverUri, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updatePlaylist(id: Long, title: String, description: String, coverUri: String?, updatedAt: Long)

    @Query("DELETE FROM local_playlists WHERE id = :id")
    suspend fun deletePlaylist(id: Long)

    @Query("DELETE FROM local_playlists")
    suspend fun clearPlaylists()

    @Query("DELETE FROM local_playlist_tracks WHERE playlistId = :playlistId")
    suspend fun clearPlaylistTracks(playlistId: Long)

    @Query("DELETE FROM local_playlist_tracks")
    suspend fun clearAllPlaylistTracks()

    @Query("SELECT * FROM local_playlist_tracks WHERE playlistId = :playlistId ORDER BY position ASC, addedAt ASC")
    fun observePlaylistTracks(playlistId: Long): Flow<List<LocalPlaylistTrackEntity>>

    @Query("SELECT * FROM local_playlist_tracks WHERE playlistId = :playlistId ORDER BY position ASC, addedAt ASC")
    suspend fun getPlaylistTracks(playlistId: Long): List<LocalPlaylistTrackEntity>

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM local_playlist_tracks WHERE playlistId = :playlistId")
    suspend fun nextPlaylistPosition(playlistId: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylistTrack(track: LocalPlaylistTrackEntity)

    @Query("DELETE FROM local_playlist_tracks WHERE id = :trackRowId")
    suspend fun deletePlaylistTrackByRowId(trackRowId: Long)

    @Query("DELETE FROM local_playlist_tracks WHERE playlistId = :playlistId AND videoId = :videoId")
    suspend fun deletePlaylistTrack(playlistId: Long, videoId: String)

    @Query("UPDATE local_playlist_tracks SET position = :position WHERE id = :rowId")
    suspend fun updatePlaylistTrackPosition(rowId: Long, position: Int)

    @Query(
        """
        SELECT playlistId, COUNT(*) AS count,
        (SELECT songJson FROM local_playlist_tracks t2 WHERE t2.playlistId = t1.playlistId ORDER BY position ASC, addedAt ASC LIMIT 1) AS firstSongJson
        FROM local_playlist_tracks t1
        GROUP BY playlistId
        """
    )
    fun observePlaylistTrackCounts(): Flow<List<PlaylistTrackCountRow>>

    // --- Liked Songs ---
    @Query("SELECT * FROM liked_songs ORDER BY likedAt DESC")
    fun observeLikedSongs(): Flow<List<LikedSongEntity>>

    @Query("SELECT videoId FROM liked_songs")
    fun observeLikedVideoIds(): Flow<List<String>>

    @Query("SELECT * FROM liked_songs ORDER BY likedAt DESC")
    suspend fun getLikedSongsOnce(): List<LikedSongEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM liked_songs WHERE videoId = :videoId)")
    suspend fun isSongLiked(videoId: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLikedSong(entity: LikedSongEntity)

    @Query("DELETE FROM liked_songs WHERE videoId = :videoId")
    suspend fun deleteLikedSong(videoId: String)

    @Query("SELECT videoId FROM liked_songs WHERE videoId LIKE 'LOCAL:%'")
    suspend fun getLocalLikedVideoIds(): List<String>

    @Query("DELETE FROM liked_songs WHERE videoId LIKE 'LOCAL:%'")
    suspend fun deleteLocalLikedSongs(): Int

    @Query("DELETE FROM liked_songs")
    suspend fun clearLikedSongs()

    // --- Saved Albums & Followed Artists ---
    @Query("SELECT * FROM saved_albums ORDER BY savedAt DESC")
    fun observeSavedAlbums(): Flow<List<SavedAlbumEntity>>

    @Query("SELECT * FROM saved_albums ORDER BY savedAt DESC")
    suspend fun getSavedAlbumsOnce(): List<SavedAlbumEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM saved_albums WHERE albumId = :albumId)")
    fun observeIsAlbumSaved(albumId: String): Flow<Boolean>

    @Query("SELECT EXISTS(SELECT 1 FROM saved_albums WHERE albumId = :albumId)")
    suspend fun isAlbumSaved(albumId: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSavedAlbum(album: SavedAlbumEntity)

    @Query("DELETE FROM saved_albums WHERE albumId = :albumId")
    suspend fun deleteSavedAlbum(albumId: String)

    @Query("DELETE FROM saved_albums")
    suspend fun clearSavedAlbums()

    @Query("SELECT * FROM followed_artists ORDER BY followedAt DESC")
    fun observeFollowedArtists(): Flow<List<FollowedArtistEntity>>

    @Query("SELECT * FROM followed_artists ORDER BY followedAt DESC")
    suspend fun getFollowedArtistsOnce(): List<FollowedArtistEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM followed_artists WHERE channelId = :channelId)")
    fun observeIsArtistFollowed(channelId: String): Flow<Boolean>

    @Query("SELECT EXISTS(SELECT 1 FROM followed_artists WHERE channelId = :channelId)")
    suspend fun isArtistFollowed(channelId: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFollowedArtist(artist: FollowedArtistEntity)

    @Query("DELETE FROM followed_artists WHERE channelId = :channelId")
    suspend fun deleteFollowedArtist(channelId: String)

    @Query("DELETE FROM followed_artists")
    suspend fun clearFollowedArtists()

    // --- Shortcuts ---
    @Query("SELECT * FROM shortcuts ORDER BY position ASC")
    fun observeShortcuts(): Flow<List<ShortcutEntity>>

    @Query("SELECT * FROM shortcuts ORDER BY position ASC")
    suspend fun getShortcutsOnce(): List<ShortcutEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertShortcut(shortcut: ShortcutEntity)

    @Query("DELETE FROM shortcuts WHERE id = :id")
    suspend fun deleteShortcut(id: String)

    @Query("DELETE FROM shortcuts")
    suspend fun clearShortcuts()

    // --- Local Audio Files ---
    @Query("SELECT * FROM local_tracks ORDER BY title ASC")
    fun observeLocalTracks(): Flow<List<LocalTrackEntity>>

    @Query("SELECT * FROM local_tracks ORDER BY title ASC")
    suspend fun getLocalTracksOnce(): List<LocalTrackEntity>

    @Query("SELECT * FROM local_tracks WHERE albumKey = :albumKey ORDER BY trackNo ASC, title ASC")
    suspend fun getLocalTracksByAlbum(albumKey: String): List<LocalTrackEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLocalTracks(tracks: List<LocalTrackEntity>)

    @Query("DELETE FROM local_tracks WHERE uri = :uri")
    suspend fun deleteLocalTrack(uri: String)

    // --- Downloads ---
    @Query("SELECT * FROM downloaded_tracks ORDER BY downloadedAt DESC")
    fun observeDownloads(): Flow<List<DownloadedTrackEntity>>

    @Query("SELECT * FROM downloaded_tracks WHERE videoId = :videoId")
    suspend fun getDownload(videoId: String): DownloadedTrackEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDownload(download: DownloadedTrackEntity)

    @Query("DELETE FROM downloaded_tracks WHERE videoId = :videoId")
    suspend fun deleteDownload(videoId: String)

    // --- Search History ---
    @Query("SELECT * FROM search_history ORDER BY searchedAt DESC LIMIT 20")
    fun observeSearchHistory(): Flow<List<SearchHistoryEntity>>

    @Query("SELECT * FROM search_history ORDER BY searchedAt DESC LIMIT :limit")
    suspend fun getSearchHistoryOnce(limit: Int = 100): List<SearchHistoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSearchHistory(entry: SearchHistoryEntity)

    @Query("DELETE FROM search_history WHERE query = :query")
    suspend fun deleteSearchHistory(query: String)

    @Query("DELETE FROM search_history")
    suspend fun clearSearchHistory()

    // --- Blocked Artists ---
    @Query("SELECT * FROM blocked_artists ORDER BY blockedAt DESC")
    fun observeBlockedArtists(): Flow<List<BlockedArtistEntity>>

    @Query("SELECT * FROM blocked_artists ORDER BY blockedAt DESC")
    suspend fun getBlockedArtistsOnce(): List<BlockedArtistEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBlockedArtist(artist: BlockedArtistEntity)

    @Query("DELETE FROM blocked_artists WHERE name = :name")
    suspend fun deleteBlockedArtist(name: String)

    @Query("DELETE FROM blocked_artists")
    suspend fun clearBlockedArtists()

    // --- Lyrics Cache ---
    @Query("SELECT * FROM lyrics_cache WHERE videoId = :videoId")
    suspend fun getLyricsCache(videoId: String): LyricsCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLyricsCache(entity: LyricsCacheEntity)

    @Query("DELETE FROM lyrics_cache WHERE pinned = 0")
    suspend fun clearUnpinnedLyricsCache()

    // --- Sync Tombstones ---
    @Query("SELECT * FROM sync_tombstones WHERE entityType = :entityType")
    suspend fun getTombstonesByType(entityType: String): List<SyncTombstoneEntity>

    @Query("SELECT * FROM sync_tombstones")
    suspend fun getAllTombstones(): List<SyncTombstoneEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTombstone(tombstone: SyncTombstoneEntity)

    @Query("DELETE FROM sync_tombstones WHERE entityType = :entityType AND entityKey = :entityKey")
    suspend fun deleteTombstone(entityType: String, entityKey: String)

    @Query("DELETE FROM sync_tombstones WHERE deletedAt < :cutoffMs")
    suspend fun purgeOldTombstones(cutoffMs: Long): Int

    @Query("DELETE FROM sync_tombstones")
    suspend fun clearTombstones()

    // --- Pending Scrobbles Retry Queue ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPendingScrobble(entity: PendingScrobbleEntity): Long

    @Query("SELECT * FROM pending_scrobbles WHERE nextAttemptAt <= :nowMs ORDER BY id ASC")
    suspend fun getDuePendingScrobbles(nowMs: Long): List<PendingScrobbleEntity>

    @Query("SELECT COUNT(*) FROM pending_scrobbles")
    fun observePendingScrobbleCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM pending_scrobbles")
    suspend fun countPendingScrobbles(): Int

    @Query("UPDATE pending_scrobbles SET attempts = :attempts, nextAttemptAt = :nextAttemptAt, lastError = :lastError WHERE id = :id")
    suspend fun updatePendingScrobbleRetry(id: Long, attempts: Int, nextAttemptAt: Long, lastError: String?)

    @Query("DELETE FROM pending_scrobbles WHERE id = :id")
    suspend fun deletePendingScrobble(id: Long)

    @Query("DELETE FROM pending_scrobbles")
    suspend fun clearPendingScrobbles()

    /**
     * Transactionally clears all account-owned tables on sign-out or account deletion.
     * Deliberately preserves local_tracks, downloaded_tracks, and lyrics_cache.
     */
    @Transaction
    suspend fun clearAccountData() {
        clearLikedSongs()
        clearAllPlaylistTracks()
        clearPlaylists()
        clearSavedAlbums()
        clearFollowedArtists()
        clearShortcuts()
        clearListens()
        clearListenSongs()
        clearSearchHistory()
        clearBlockedArtists()
        clearTombstones()
        clearPendingScrobbles()
    }
}
