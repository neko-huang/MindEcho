package com.moodecho.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.moodecho.app.data.db.dao.DailyReportDao
import com.moodecho.app.data.db.dao.EmotionDataPointDao
import com.moodecho.app.data.db.dao.RecordingSessionDao
import com.moodecho.app.data.db.dao.TranscriptEntryDao
import com.moodecho.app.data.db.entity.DailyReport
import com.moodecho.app.data.db.entity.EmotionDataPoint
import com.moodecho.app.data.db.entity.RecordingSession
import com.moodecho.app.data.db.entity.SessionStatus
import com.moodecho.app.data.db.entity.TranscriptEntry
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
        DailyReport::class
    ],
    version = 2,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun recordingSessionDao(): RecordingSessionDao
    abstract fun transcriptEntryDao(): TranscriptEntryDao
    abstract fun emotionDataPointDao(): EmotionDataPointDao
    abstract fun dailyReportDao(): DailyReportDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

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
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

/**
 * Type converters for Room to handle custom types (enums).
 */
class Converters {
    @androidx.room.TypeConverter
    fun fromEmotionType(value: EmotionType): String = value.name

    @androidx.room.TypeConverter
    fun toEmotionType(value: String): EmotionType =
        EmotionType.valueOf(value)

    @androidx.room.TypeConverter
    fun fromSessionStatus(value: SessionStatus): String = value.name

    @androidx.room.TypeConverter
    fun toSessionStatus(value: String): SessionStatus =
        SessionStatus.valueOf(value)
}