package com.example.data.local

import android.app.Application
import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
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
        val dbName = "veluna_migration_test.db"
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

        // Verify newly added v2 tables accept inserts
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
    }
}
