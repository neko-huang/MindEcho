package com.moodecho.app.data.repository

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
import kotlinx.coroutines.flow.Flow

/**
 * Sealed class for repository operation results.
 * Provides type-safe success/error handling.
 */
sealed class RepositoryResult<out T> {
    data class Success<T>(val data: T) : RepositoryResult<T>()
    data class Error(val message: String, val exception: Throwable? = null) : RepositoryResult<Nothing>()
}

/**
 * Repository that provides a clean API for accessing recording data.
 * Mediates between the domain/data layers and encapsulates data access logic.
 */
class RecordingRepository(
    private val sessionDao: RecordingSessionDao,
    private val transcriptDao: TranscriptEntryDao,
    private val emotionDao: EmotionDataPointDao,
    private val reportDao: DailyReportDao
) {

    // ---- Session operations ----

    /** Create a new recording session and return its ID */
    suspend fun createSession(session: RecordingSession): RepositoryResult<Long> {
        return try {
            RepositoryResult.Success(sessionDao.insert(session))
        } catch (e: Exception) {
            RepositoryResult.Error("Failed to create session", e)
        }
    }

    /** Get all sessions as a Flow for reactive UI updates */
    fun getAllSessions(): Flow<List<RecordingSession>> {
        return sessionDao.getAllSessions()
    }

    /** Get the most recent sessions (for home screen) */
    fun getRecentSessions(limit: Int = 3): Flow<List<RecordingSession>> {
        return sessionDao.getRecentSessions(limit)
    }

    /** Get a single session by ID */
    suspend fun getSessionById(id: Long): RepositoryResult<RecordingSession?> {
        return try {
            RepositoryResult.Success(sessionDao.getSessionById(id))
        } catch (e: Exception) {
            RepositoryResult.Error("Failed to get session", e)
        }
    }

    /** Get sessions for a specific date range (for daily report) */
    suspend fun getSessionsByDate(dayStart: Long, nextDayStart: Long): RepositoryResult<List<RecordingSession>> {
        return try {
            RepositoryResult.Success(sessionDao.getSessionsByDate(dayStart, nextDayStart))
        } catch (e: Exception) {
            RepositoryResult.Error("Failed to get sessions by date", e)
        }
    }

    /** Update session status when recording completes */
    suspend fun completeSession(id: Long, endTime: Long, duration: Long): RepositoryResult<Unit> {
        return try {
            sessionDao.updateSessionStatus(id, SessionStatus.COMPLETED, endTime, duration)
            RepositoryResult.Success(Unit)
        } catch (e: Exception) {
            RepositoryResult.Error("Failed to complete session", e)
        }
    }

    /** Delete a session and all associated data */
    suspend fun deleteSession(id: Long): RepositoryResult<Unit> {
        return try {
            sessionDao.deleteById(id)
            RepositoryResult.Success(Unit)
        } catch (e: Exception) {
            RepositoryResult.Error("Failed to delete session", e)
        }
    }

    // ---- Transcript operations ----

    /** Save a transcript entry */
    suspend fun saveTranscript(entry: TranscriptEntry): RepositoryResult<Long> {
        return try {
            RepositoryResult.Success(transcriptDao.insert(entry))
        } catch (e: Exception) {
            RepositoryResult.Error("Failed to save transcript", e)
        }
    }

    /** Save multiple transcript entries (batch insert) */
    suspend fun saveTranscripts(entries: List<TranscriptEntry>): RepositoryResult<Unit> {
        return try {
            transcriptDao.insertAll(entries)
            RepositoryResult.Success(Unit)
        } catch (e: Exception) {
            RepositoryResult.Error("Failed to save transcripts", e)
        }
    }

    /** Get all transcript entries for a session */
    fun getTranscriptsForSession(sessionId: Long): Flow<List<TranscriptEntry>> {
        return transcriptDao.getTranscriptsForSession(sessionId)
    }

    /** Get all transcript entries for a session (suspend, one-shot) */
    suspend fun getTranscriptsForSessionSync(sessionId: Long): RepositoryResult<List<TranscriptEntry>> {
        return try {
            RepositoryResult.Success(transcriptDao.getTranscriptsForSessionSync(sessionId))
        } catch (e: Exception) {
            RepositoryResult.Error("Failed to get transcripts", e)
        }
    }

    // ---- Emotion data operations ----

    /** Save a single emotion data point */
    suspend fun saveEmotionDataPoint(point: EmotionDataPoint): RepositoryResult<Long> {
        return try {
            RepositoryResult.Success(emotionDao.insert(point))
        } catch (e: Exception) {
            RepositoryResult.Error("Failed to save emotion data point", e)
        }
    }

    /** Save multiple emotion data points (batch insert) */
    suspend fun saveEmotionDataPoints(points: List<EmotionDataPoint>): RepositoryResult<Unit> {
        return try {
            emotionDao.insertAll(points)
            RepositoryResult.Success(Unit)
        } catch (e: Exception) {
            RepositoryResult.Error("Failed to save emotion data points", e)
        }
    }

    /** Get all emotion data points for a session */
    fun getEmotionsForSession(sessionId: Long): Flow<List<EmotionDataPoint>> {
        return emotionDao.getEmotionsForSession(sessionId)
    }

    /** Get all emotion data points for a session (suspend, one-shot) */
    suspend fun getEmotionsForSessionSync(sessionId: Long): RepositoryResult<List<EmotionDataPoint>> {
        return try {
            RepositoryResult.Success(emotionDao.getEmotionsForSessionSync(sessionId))
        } catch (e: Exception) {
            RepositoryResult.Error("Failed to get emotion data points", e)
        }
    }

    /** Get the dominant emotion for a session */
    suspend fun getDominantEmotion(sessionId: Long): EmotionType? {
        val emotionName = emotionDao.getDominantEmotionForSession(sessionId) ?: return null
        return try {
            EmotionType.valueOf(emotionName)
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    // ---- Daily report operations ----

    /** Save or update a daily report */
    suspend fun saveReport(report: DailyReport): RepositoryResult<Long> {
        return try {
            RepositoryResult.Success(reportDao.insert(report))
        } catch (e: Exception) {
            RepositoryResult.Error("Failed to save report", e)
        }
    }

    /** Get a report for a specific date */
    suspend fun getReportByDate(date: String): RepositoryResult<DailyReport?> {
        return try {
            RepositoryResult.Success(reportDao.getReportByDate(date))
        } catch (e: Exception) {
            RepositoryResult.Error("Failed to get report", e)
        }
    }

    /** Get all reports as a Flow */
    fun getAllReports(): Flow<List<DailyReport>> {
        return reportDao.getAllReports()
    }

    /** Get the most recent reports */
    fun getRecentReports(limit: Int = 7): Flow<List<DailyReport>> {
        return reportDao.getRecentReports(limit)
    }

    /** Delete a daily report */
    suspend fun deleteReport(report: DailyReport): RepositoryResult<Unit> {
        return try {
            reportDao.delete(report)
            RepositoryResult.Success(Unit)
        } catch (e: Exception) {
            RepositoryResult.Error("Failed to delete report", e)
        }
    }

    /** Delete a daily report by its ID */
    suspend fun deleteReportById(id: Long): RepositoryResult<Unit> {
        return try {
            reportDao.deleteById(id)
            RepositoryResult.Success(Unit)
        } catch (e: Exception) {
            RepositoryResult.Error("Failed to delete report", e)
        }
    }
}
