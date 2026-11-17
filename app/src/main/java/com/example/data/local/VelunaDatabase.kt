package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ListenEntity::class,
        ListenSongEntity::class,
        LocalPlaylistEntity::class,
        LocalPlaylistTrackEntity::class,
        LikedSongEntity::class,
        SavedAlbumEntity::class,
        FollowedArtistEntity::class,
        ShortcutEntity::class,
        LocalTrackEntity::class,
        DownloadedTrackEntity::class,
        SearchHistoryEntity::class,
        BlockedArtistEntity::class,
        LyricsCacheEntity::class,
        SyncTombstoneEntity::class,
        SyncBaselineEntity::class,
        PendingScrobbleEntity::class
    ],
    version = 3,
    exportSchema = true
)
abstract class VelunaDatabase : RoomDatabase() {
    abstract fun velunaDao(): VelunaDao

    companion object {
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `sync_tombstones` (
                        `entityType` TEXT NOT NULL,
                        `entityKey` TEXT NOT NULL,
                        `clientUpdatedAt` INTEGER NOT NULL,
                        `deletedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`entityType`, `entityKey`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `pending_scrobbles` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `service` TEXT NOT NULL,
                        `payloadJson` TEXT NOT NULL,
                        `startedAtSecs` INTEGER NOT NULL,
                        `attempts` INTEGER NOT NULL,
                        `nextAttemptAt` INTEGER NOT NULL,
                        `lastError` TEXT
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS `index_pending_scrobbles_nextAttemptAt`
                    ON `pending_scrobbles` (`nextAttemptAt`)
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `local_playlists` ADD COLUMN `cloudKey` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `local_playlists` ADD COLUMN `syncRevision` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE `local_playlists` SET `cloudKey` = 'pl_local_' || `id` WHERE `cloudKey` = ''")
                db.execSQL(
                    """
                    CREATE UNIQUE INDEX IF NOT EXISTS `index_local_playlists_cloudKey`
                    ON `local_playlists` (`cloudKey`)
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `sync_baselines` (
                        `userId` TEXT NOT NULL,
                        `entityType` TEXT NOT NULL,
                        `entityKey` TEXT NOT NULL,
                        `revision` INTEGER NOT NULL,
                        `signature` TEXT NOT NULL,
                        `clientUpdatedAt` INTEGER NOT NULL,
                        `deleted` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`userId`, `entityType`, `entityKey`)
                    )
                    """.trimIndent()
                )
            }
        }

        @Volatile
        private var INSTANCE: VelunaDatabase? = null

        fun getInstance(context: Context): VelunaDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    VelunaDatabase::class.java,
                    "veluna.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
