package com.moodecho.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Migration
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase
import com.moodecho.app.data.db.dao.DailyReportDao
import com.moodecho.app.data.db.dao.DailyReportSessionDao
import com.moodecho.app.data.db.dao.EmotionDataPointDao
import com.moodecho.app.data.db.dao.RecordingSessionDao
import com.moodecho.app.data.db.dao.TranscriptEntryDao
import com.moodecho.app.data.db.entity.DailyReport
import com.moodecho.app.data.db.entity.DailyReportSession
import com.moodecho.app.data.db.entity.EmotionDataPoint
import com.moodecho.app.data.db.entity.RecordingSession
import com.moodecho.app.data.db.entity.TranscriptEntry
import com.moodecho.app.data.db.entity.SessionStatus
import com.moodecho.app.domain.model.EmotionType

/**
 * Room database for MindEcho.
 * Provides access to all DAOs and manages database lifecycle.
 */
@Database(
    entities = [
        RecordingSession::class,
        TranscriptEntry::class,
        EmotionDataPoint::class,
        DailyReport::class,
        DailyReportSession::class
    ],
    version = 3,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun recordingSessionDao(): RecordingSessionDao
    abstract fun transcriptEntryDao(): TranscriptEntryDao
    abstract fun emotionDataPointDao(): EmotionDataPointDao
    abstract fun dailyReportDao(): DailyReportDao
    abstract fun dailyReportSessionDao(): DailyReportSessionDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * Migration from v1 to v2: Add daily_report_sessions table and migrate sessionIdList data.
         */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Create the daily_report_sessions junction table
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `daily_report_sessions` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `reportId` INTEGER NOT NULL,
                        `sessionId` INTEGER NOT NULL,
                        FOREIGN KEY (`reportId`) REFERENCES `daily_reports`(`id`) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_daily_report_sessions_reportId` ON `daily_report_sessions` (`reportId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_daily_report_sessions_sessionId` ON `daily_report_sessions` (`sessionId`)")

                // Migrate existing sessionIdList data to the new junction table
                db.execSQL(
                    """
                    INSERT INTO `daily_report_sessions` (`reportId`, `sessionId`)
                    SELECT `id`, CAST(TRIM(value) AS INTEGER)
                    FROM `daily_reports`, json_each('[' || REPLACE(`sessionIdList`, ',', '","') || '"]')
                    WHERE `sessionIdList` IS NOT NULL AND TRIM(`sessionIdList`) != ''
                    """.trimIndent()
                )
            }
        }

        /**
         * Migration from v2 to v3: Drop the sessionIdList column from daily_reports.
         */
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // SQLite doesn't support DROP COLUMN easily, so we recreate the table
                db.execSQL("CREATE TABLE IF NOT EXISTS `daily_reports_new` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`date` TEXT NOT NULL, " +
                        "`summary` TEXT NOT NULL, " +
                        "`emotionOverview` TEXT NOT NULL, " +
                        "`suggestions` TEXT NOT NULL, " +
                        "`overallMood` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL)")
                db.execSQL("INSERT INTO `daily_reports_new` (`id`, `date`, `summary`, `emotionOverview`, `suggestions`, `overallMood`, `createdAt`) " +
                        "SELECT `id`, `date`, `summary`, `emotionOverview`, `suggestions`, `overallMood`, `createdAt` FROM `daily_reports`")
                db.execSQL("DROP TABLE `daily_reports`")
                db.execSQL("ALTER TABLE `daily_reports_new` RENAME TO `daily_reports`")
            }
        }

        /**
         * Get or create the singleton database instance.
         * @param context Application context
         * @return The AppDatabase instance
         */
        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "mindecho_database"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

/**
 * Type converters for Room to handle custom types (enums, lists).
 */
class Converters {
    @androidx.room.TypeConverter
    fun fromEmotionType(value: EmotionType): String = value.storageKey

    @androidx.room.TypeConverter
    fun toEmotionType(value: String): EmotionType =
        EmotionType.entries.firstOrNull { it.storageKey == value }
            ?: EmotionType.valueOf(value.uppercase()) // fallback for old data stored as enum name

    @androidx.room.TypeConverter
    fun fromSessionStatus(value: SessionStatus): String = value.name

    @androidx.room.TypeConverter
    fun toSessionStatus(value: String): SessionStatus = SessionStatus.valueOf(value)
}
