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
    suspend fun createSession(session: RecordingSession): Long {
        return sessionDao.insert(session)
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
    suspend fun getSessionById(id: Long): RecordingSession? {
        return sessionDao.getSessionById(id)
    }

    /** Get sessions for a specific date (for daily report) */
    suspend fun getSessionsByDate(date: String): List<RecordingSession> {
        return sessionDao.getSessionsByDate(date)
    }

    /** Update session status when recording completes */
    suspend fun completeSession(id: Long, endTime: Long, duration: Long) {
        sessionDao.updateSessionStatus(id, SessionStatus.COMPLETED.name, endTime, duration)
    }

    /** Delete a session and all associated data */
    suspend fun deleteSession(id: Long) {
        sessionDao.deleteById(id)
    }

    // ---- Transcript operations ----

    /** Save a transcript entry */
    suspend fun saveTranscript(entry: TranscriptEntry): Long {
        return transcriptDao.insert(entry)
    }

    /** Save multiple transcript entries (batch insert) */
    suspend fun saveTranscripts(entries: List<TranscriptEntry>) {
        transcriptDao.insertAll(entries)
    }

    /** Get all transcript entries for a session */
    fun getTranscriptsForSession(sessionId: Long): Flow<List<TranscriptEntry>> {
        return transcriptDao.getTranscriptsForSession(sessionId)
    }

    /** Get all transcript entries for a session (suspend, one-shot) */
    suspend fun getTranscriptsForSessionSync(sessionId: Long): List<TranscriptEntry> {
        return transcriptDao.getTranscriptsForSessionSync(sessionId)
    }

    // ---- Emotion data operations ----

    /** Save a single emotion data point */
    suspend fun saveEmotionDataPoint(point: EmotionDataPoint): Long {
        return emotionDao.insert(point)
    }

    /** Save multiple emotion data points (batch insert) */
    suspend fun saveEmotionDataPoints(points: List<EmotionDataPoint>) {
        emotionDao.insertAll(points)
    }

    /** Get all emotion data points for a session */
    fun getEmotionsForSession(sessionId: Long): Flow<List<EmotionDataPoint>> {
        return emotionDao.getEmotionsForSession(sessionId)
    }

    /** Get all emotion data points for a session (suspend, one-shot) */
    suspend fun getEmotionsForSessionSync(sessionId: Long): List<EmotionDataPoint> {
        return emotionDao.getEmotionsForSessionSync(sessionId)
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
    suspend fun saveReport(report: DailyReport): Long {
        return reportDao.insert(report)
    }

    /** Get a report for a specific date */
    suspend fun getReportByDate(date: String): DailyReport? {
        return reportDao.getReportByDate(date)
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
    suspend fun deleteReport(report: DailyReport) {
        reportDao.delete(report)
    }
}
