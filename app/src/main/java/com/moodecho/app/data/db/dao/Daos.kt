package com.moodecho.app.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.moodecho.app.data.db.entity.DailyReport
import com.moodecho.app.data.db.entity.EmotionDataPoint
import com.moodecho.app.data.db.entity.RecordingSession
import com.moodecho.app.data.db.entity.TranscriptEntry
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for RecordingSession entities.
 */
@Dao
interface RecordingSessionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: RecordingSession): Long

    @Query("SELECT * FROM recording_sessions ORDER BY startTime DESC")
    fun getAllSessions(): Flow<List<RecordingSession>>

    @Query("SELECT * FROM recording_sessions ORDER BY startTime DESC LIMIT :limit")
    fun getRecentSessions(limit: Int): Flow<List<RecordingSession>>

    @Query("SELECT * FROM recording_sessions WHERE id = :id")
    suspend fun getSessionById(id: Long): RecordingSession?

    @Query("SELECT * FROM recording_sessions WHERE startTime >= :dayStart AND startTime < :nextDayStart")
    suspend fun getSessionsByDate(dayStart: Long, nextDayStart: Long): List<RecordingSession>

    @Query("UPDATE recording_sessions SET status = :status, endTime = :endTime, duration = :duration WHERE id = :id")
    suspend fun updateSessionStatus(id: Long, status: String, endTime: Long, duration: Long)

    @Delete
    suspend fun delete(session: RecordingSession)

    @Query("DELETE FROM recording_sessions WHERE id = :id")
    suspend fun deleteById(id: Long)
}

/**
 * Data Access Object for TranscriptEntry entities.
 */
@Dao
interface TranscriptEntryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: TranscriptEntry): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<TranscriptEntry>)

    @Query("SELECT * FROM transcript_entries WHERE sessionId = :sessionId ORDER BY startTime ASC")
    fun getTranscriptsForSession(sessionId: Long): Flow<List<TranscriptEntry>>

    @Query("SELECT * FROM transcript_entries WHERE sessionId = :sessionId ORDER BY startTime ASC")
    suspend fun getTranscriptsForSessionSync(sessionId: Long): List<TranscriptEntry>

    @Query("DELETE FROM transcript_entries WHERE sessionId = :sessionId")
    suspend fun deleteForSession(sessionId: Long)
}

/**
 * Data Access Object for EmotionDataPoint entities.
 */
@Dao
interface EmotionDataPointDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(point: EmotionDataPoint): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(points: List<EmotionDataPoint>)

    @Query("SELECT * FROM emotion_data_points WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun getEmotionsForSession(sessionId: Long): Flow<List<EmotionDataPoint>>

    @Query("SELECT * FROM emotion_data_points WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    suspend fun getEmotionsForSessionSync(sessionId: Long): List<EmotionDataPoint>

    @Query("SELECT emotionType FROM emotion_data_points WHERE sessionId = :sessionId GROUP BY emotionType ORDER BY COUNT(*) DESC LIMIT 1")
    suspend fun getDominantEmotionForSession(sessionId: Long): String?

    @Query("DELETE FROM emotion_data_points WHERE sessionId = :sessionId")
    suspend fun deleteForSession(sessionId: Long)
}

/**
 * Data Access Object for DailyReport entities.
 */
@Dao
interface DailyReportDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(report: DailyReport): Long

    @Query("SELECT * FROM daily_reports WHERE date = :date")
    suspend fun getReportByDate(date: String): DailyReport?

    @Query("SELECT * FROM daily_reports ORDER BY date DESC")
    fun getAllReports(): Flow<List<DailyReport>>

    @Query("SELECT * FROM daily_reports ORDER BY date DESC LIMIT :limit")
    fun getRecentReports(limit: Int): Flow<List<DailyReport>>

    @Delete
    suspend fun delete(report: DailyReport)

    @Query("DELETE FROM daily_reports WHERE id = :id")
    suspend fun deleteById(id: Long)
}


