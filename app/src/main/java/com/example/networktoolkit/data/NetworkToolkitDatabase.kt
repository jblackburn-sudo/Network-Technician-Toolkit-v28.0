package com.example.networktoolkit.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room Database for Network Technician Toolkit local storage.
 * Stores ping diagnostic test results, field technician notes, and traceroute sessions.
 */
@Database(
    entities = [
        PingResultEntity::class,
        NetworkNoteEntity::class,
        TracerouteResultEntity::class
    ],
    version = 4,
    exportSchema = false
)
abstract class NetworkToolkitDatabase : RoomDatabase() {

    abstract fun pingResultDao(): PingResultDao
    abstract fun networkNoteDao(): NetworkNoteDao
    abstract fun tracerouteResultDao(): TracerouteResultDao

    companion object {
        @Volatile
        private var INSTANCE: NetworkToolkitDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `network_notes` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `title` TEXT NOT NULL,
                        `content` TEXT NOT NULL,
                        `category` TEXT NOT NULL,
                        `ipOrHost` TEXT,
                        `isPinned` INTEGER NOT NULL,
                        `createdTimestamp` INTEGER NOT NULL,
                        `modifiedTimestamp` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `traceroute_results` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `targetHost` TEXT NOT NULL,
                        `targetIp` TEXT NOT NULL,
                        `totalHops` INTEGER NOT NULL,
                        `isDestinationReached` INTEGER NOT NULL,
                        `avgRttMs` REAL,
                        `durationMs` INTEGER NOT NULL,
                        `hopsJson` TEXT NOT NULL,
                        `timestamp` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `network_notes` ADD COLUMN `isEncrypted` INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        fun getInstance(context: Context): NetworkToolkitDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    NetworkToolkitDatabase::class.java,
                    "network_toolkit_database"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
