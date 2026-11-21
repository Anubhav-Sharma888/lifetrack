package com.lifetrack.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Session queries: observation flows for the UI, snapshot reads for backup/export, and bounded
 * upcoming reads for the reminder scheduler (which never scans completed history).
 */
@Dao
interface SessionDao {
    @Query("SELECT * FROM goal_sessions ORDER BY plannedStartDate, plannedStartTime, id")
    fun observeSessions(): Flow<List<GoalSession>>

    @Query("SELECT * FROM goal_sessions WHERE id = :id") suspend fun session(id: Long): GoalSession?
    @Query("SELECT * FROM goal_sessions WHERE uuid = :uuid") suspend fun sessionByUuid(uuid: String): GoalSession?
    @Query("SELECT * FROM goal_sessions WHERE uuid = :uuid") fun observeSessionByUuid(uuid: String): Flow<GoalSession?>

    /** Sessions on or after the given day, still not canceled/completed — the upcoming list and
     *  the reminder scheduler's input. The scheduler reads from yesterday (device-local): a
     *  session planned late in a zone behind the device can resolve to a future instant while
     *  carrying yesterday's date. Bounded by the caller against MAX_UPCOMING_SESSIONS. */
    @Query(
        "SELECT * FROM goal_sessions WHERE status IN ('PLANNED', 'IN_PROGRESS') AND plannedStartDate >= :fromDay " +
            "ORDER BY plannedStartDate, plannedStartTime, id"
    )
    suspend fun upcomingSessions(fromDay: Long): List<GoalSession>

    @Query("SELECT * FROM goal_sessions WHERE status IN ('PLANNED', 'IN_PROGRESS') AND plannedStartDate >= :fromDay ORDER BY plannedStartDate, plannedStartTime, id")
    fun observeUpcomingSessions(fromDay: Long): Flow<List<GoalSession>>

    @Query("SELECT * FROM goal_sessions WHERE goalId = :goalId ORDER BY plannedStartDate, plannedStartTime, id")
    fun observeSessionsForGoal(goalId: Long): Flow<List<GoalSession>>

    @Query("SELECT * FROM goal_sessions WHERE taskId = :taskId ORDER BY plannedStartDate, plannedStartTime, id")
    suspend fun sessionsForTask(taskId: Long): List<GoalSession>

    @Query("SELECT * FROM goal_sessions WHERE status = 'IN_PROGRESS'") suspend fun sessionsInProgress(): List<GoalSession>

    @Query("SELECT * FROM goal_sessions ORDER BY plannedStartDate DESC, plannedStartTime DESC, id DESC LIMIT :limit")
    suspend fun recentSessions(limit: Int): List<GoalSession>

    @Query("SELECT * FROM goal_sessions ORDER BY plannedStartDate DESC, plannedStartTime DESC, id DESC")
    suspend fun allSessions(): List<GoalSession>

    @Query("SELECT COUNT(*) FROM goal_sessions") suspend fun sessionCount(): Int

    @Insert suspend fun insertSession(session: GoalSession): Long
    @Insert suspend fun insertSessions(sessions: List<GoalSession>)
    @Update suspend fun updateSession(session: GoalSession)
    @Query("DELETE FROM goal_sessions") suspend fun deleteSessions()
    @Query("DELETE FROM goal_sessions WHERE id = :id") suspend fun deleteSessionById(id: Long)
}
