package com.example.data.local

import android.app.Application
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class VelunaDatabaseMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        VelunaDatabase::class.java
    )

    @Test
    fun migrate1To2_preservesExistingDataAndCreatesNewTables() {
        val dbName = "veluna_migration_1_2.db"
        helper.createDatabase(dbName, 1).use { db ->
            db.execSQL(
                "INSERT INTO liked_songs (videoId, songJson, likedAt) VALUES ('trk_v1', '{\"videoId\":\"trk_v1\",\"title\":\"V1 Track\"}', 1700000000000)"
            )
            db.execSQL(
                "INSERT INTO local_playlists (id, title, description, coverUri, createdAt, updatedAt) VALUES (1, 'My V1 List', 'Desc', NULL, 1700000000000, 1700000000000)"
            )
            db.execSQL(
                "INSERT INTO local_playlist_tracks (id, playlistId, videoId, songJson, position, addedAt) VALUES (10, 1, 'trk_v1', '{}', 0, 1700000000000)"
            )
        }

        val migratedDb = helper.runMigrationsAndValidate(
            dbName,
            2,
            true,
            VelunaDatabase.MIGRATION_1_2
        )

        migratedDb.query("SELECT videoId FROM liked_songs").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getString(0)).isEqualTo("trk_v1")
        }
        migratedDb.query("SELECT title FROM local_playlists WHERE id = 1").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getString(0)).isEqualTo("My V1 List")
        }

        migratedDb.execSQL(
            "INSERT INTO sync_tombstones (entityType, entityKey, clientUpdatedAt, deletedAt) VALUES ('LIKED', 'trk_del', 1700000001000, 1700000001000)"
        )
        migratedDb.execSQL(
            "INSERT INTO pending_scrobbles (service, payloadJson, startedAtSecs, attempts, nextAttemptAt, lastError) VALUES ('LASTFM', '{}', 1700000000, 0, 0, NULL)"
        )
        migratedDb.query("SELECT COUNT(*) FROM sync_tombstones").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getInt(0)).isEqualTo(1)
        }
        migratedDb.query("SELECT COUNT(*) FROM pending_scrobbles").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getInt(0)).isEqualTo(1)
        }
        migratedDb.close()
    }

    @Test
    fun migrate2To3_assignsLegacyCloudKeysAndCreatesSyncBaselines() {
        val dbName = "veluna_migration_2_3.db"
        helper.createDatabase(dbName, 2).use { db ->
            db.execSQL(
                "INSERT INTO local_playlists (id, title, description, coverUri, createdAt, updatedAt) VALUES (7, 'Late Night', 'Synth', NULL, 1700000000000, 1700000000500)"
            )
            db.execSQL(
                "INSERT INTO local_playlists (id, title, description, coverUri, createdAt, updatedAt) VALUES (12, 'Workout', '', NULL, 1700000001000, 1700000001000)"
            )
        }

        val migratedDb = helper.runMigrationsAndValidate(
            dbName,
            3,
            true,
            VelunaDatabase.MIGRATION_2_3
        )

        migratedDb.query("SELECT id, cloudKey, syncRevision FROM local_playlists ORDER BY id ASC").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getLong(0)).isEqualTo(7L)
            assertThat(c.getString(1)).isEqualTo("pl_local_7")
            assertThat(c.getLong(2)).isEqualTo(0L)

            assertThat(c.moveToNext()).isTrue()
            assertThat(c.getLong(0)).isEqualTo(12L)
            assertThat(c.getString(1)).isEqualTo("pl_local_12")
            assertThat(c.getLong(2)).isEqualTo(0L)
        }

        // Verify unique index on cloudKey rejects duplicates
        try {
            migratedDb.execSQL(
                "INSERT INTO local_playlists (cloudKey, title, description, coverUri, createdAt, updatedAt, syncRevision) VALUES ('pl_local_7', 'Dup', '', NULL, 1, 1, 1)"
            )
            fail("Expected SQLiteConstraintException on duplicate cloudKey")
        } catch (_: SQLiteConstraintException) {
            // Expected
        }

        // Verify sync_baselines table accepts inserts
        migratedDb.execSQL(
            "INSERT INTO sync_baselines (userId, entityType, entityKey, revision, signature, clientUpdatedAt, deleted, updatedAt) VALUES ('uid_1', 'PLAYLIST', 'pl_local_7', 1, 'sig_abc', 1700000000500, 0, 1700000000500)"
        )
        migratedDb.query("SELECT signature, revision FROM sync_baselines WHERE userId = 'uid_1'").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getString(0)).isEqualTo("sig_abc")
            assertThat(c.getLong(1)).isEqualTo(1L)
        }
        migratedDb.close()
    }

    @Test
    fun migrate1To3_preservesAllDataEndToEnd() {
        val dbName = "veluna_migration_1_3.db"
        helper.createDatabase(dbName, 1).use { db ->
            db.execSQL(
                "INSERT INTO liked_songs (videoId, songJson, likedAt) VALUES ('trk_v1_e2e', '{\"videoId\":\"trk_v1_e2e\",\"title\":\"E2E\"}', 1700000000000)"
            )
            db.execSQL(
                "INSERT INTO local_playlists (id, title, description, coverUri, createdAt, updatedAt) VALUES (3, 'Road Trip', 'Highway', NULL, 1700000000000, 1700000002000)"
            )
        }

        val migratedDb = helper.runMigrationsAndValidate(
            dbName,
            3,
            true,
            VelunaDatabase.MIGRATION_1_2,
            VelunaDatabase.MIGRATION_2_3
        )

        migratedDb.query("SELECT cloudKey, title, syncRevision FROM local_playlists WHERE id = 3").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getString(0)).isEqualTo("pl_local_3")
            assertThat(c.getString(1)).isEqualTo("Road Trip")
            assertThat(c.getLong(2)).isEqualTo(0L)
        }
        migratedDb.query("SELECT videoId FROM liked_songs").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getString(0)).isEqualTo("trk_v1_e2e")
        }
        migratedDb.close()
    }

    @Test
    fun defaultCloudKey_isUniqueUuidPerPlaylist_andRejectsBlank(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(context, VelunaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = db.velunaDao()
            val p1 = LocalPlaylistEntity(title = "Same Title")
            val p2 = LocalPlaylistEntity(title = "Same Title")
            assertThat(p1.cloudKey).startsWith("pl_")
            assertThat(p2.cloudKey).startsWith("pl_")
            assertThat(p1.cloudKey).isNotEqualTo(p2.cloudKey)

            dao.insertPlaylist(p1)
            dao.insertPlaylist(p2)
            assertThat(dao.getPlaylistsOnce().map { it.cloudKey }).containsExactly(p1.cloudKey, p2.cloudKey)

            try {
                LocalPlaylistEntity(cloudKey = "   ", title = "Invalid")
                fail("Expected IllegalArgumentException for blank cloudKey")
            } catch (_: IllegalArgumentException) {
                // Expected
            }
        } finally {
            db.close()
        }
    }
}
