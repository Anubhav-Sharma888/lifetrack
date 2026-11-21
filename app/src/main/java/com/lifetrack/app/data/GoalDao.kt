package com.lifetrack.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Goal Roadmap queries. Observation flows are per-goal for phases/milestones/tasks because the
 * roadmap UI always works inside one goal; goal-level and whole-table reads back the repository,
 * the export snapshot and (later) the import preview.
 */
@Dao
interface GoalDao {
    // ---- Goals ----
    @Query("SELECT * FROM goals ORDER BY sortOrder, id") fun observeGoals(): Flow<List<Goal>>
    @Query("SELECT * FROM goals WHERE id = :id") suspend fun goal(id: Long): Goal?
    @Query("SELECT * FROM goals WHERE uuid = :uuid") suspend fun goalByUuid(uuid: String): Goal?
    @Query("SELECT * FROM goals ORDER BY sortOrder, id") suspend fun allGoals(): List<Goal>
    @Insert suspend fun insertGoal(goal: Goal): Long
    @Insert suspend fun insertGoals(goals: List<Goal>)
    @Update suspend fun updateGoal(goal: Goal)
    @Query("DELETE FROM goals WHERE id = :id") suspend fun deleteGoalById(id: Long)
    @Query("DELETE FROM goals") suspend fun deleteGoals()
    @Query("UPDATE goals SET sortOrder = :position, updatedAt = :timestamp WHERE id = :id")
    suspend fun setGoalSortOrder(id: Long, position: Int, timestamp: Long)

    // ---- Phases ----
    @Query("SELECT * FROM goal_phases WHERE goalId = :goalId ORDER BY sortOrder, id")
    fun observePhasesForGoal(goalId: Long): Flow<List<GoalPhase>>
    @Query("SELECT * FROM goal_phases WHERE id = :id") suspend fun phase(id: Long): GoalPhase?
    @Query("SELECT * FROM goal_phases WHERE uuid = :uuid") suspend fun phaseByUuid(uuid: String): GoalPhase?
    @Query("SELECT * FROM goal_phases WHERE goalId = :goalId ORDER BY sortOrder, id")
    suspend fun phasesForGoal(goalId: Long): List<GoalPhase>
    @Query("SELECT * FROM goal_phases ORDER BY goalId, sortOrder, id") suspend fun allPhases(): List<GoalPhase>
    @Insert suspend fun insertPhase(phase: GoalPhase): Long
    @Insert suspend fun insertPhases(phases: List<GoalPhase>)
    @Update suspend fun updatePhase(phase: GoalPhase)
    @Query("DELETE FROM goal_phases WHERE id = :id") suspend fun deletePhaseById(id: Long)
    @Query("DELETE FROM goal_phases WHERE goalId = :goalId") suspend fun deletePhasesForGoal(goalId: Long)
    @Query("DELETE FROM goal_phases") suspend fun deletePhases()
    @Query("UPDATE goal_phases SET sortOrder = :position, updatedAt = :timestamp WHERE id = :id")
    suspend fun setPhaseSortOrder(id: Long, position: Int, timestamp: Long)
    /** Detaching precedes an explicit phase delete: one visible step, one deterministic order. */
    @Query("UPDATE goal_milestones SET phaseId = NULL, updatedAt = :timestamp WHERE phaseId = :phaseId")
    suspend fun detachMilestonesFromPhase(phaseId: Long, timestamp: Long)
    @Query("UPDATE goal_tasks SET phaseId = NULL, updatedAt = :timestamp WHERE phaseId = :phaseId")
    suspend fun detachTasksFromPhase(phaseId: Long, timestamp: Long)

    // ---- Milestones ----
    @Query("SELECT * FROM goal_milestones WHERE goalId = :goalId ORDER BY createdAt, id")
    fun observeMilestonesForGoal(goalId: Long): Flow<List<GoalMilestone>>
    @Query("SELECT * FROM goal_milestones WHERE id = :id") suspend fun milestone(id: Long): GoalMilestone?
    @Query("SELECT * FROM goal_milestones WHERE uuid = :uuid") suspend fun milestoneByUuid(uuid: String): GoalMilestone?
    @Query("SELECT * FROM goal_milestones WHERE goalId = :goalId ORDER BY createdAt, id")
    suspend fun milestonesForGoal(goalId: Long): List<GoalMilestone>
    @Query("SELECT * FROM goal_milestones ORDER BY goalId, createdAt, id") suspend fun allMilestones(): List<GoalMilestone>
    @Insert suspend fun insertMilestone(milestone: GoalMilestone): Long
    @Insert suspend fun insertMilestones(milestones: List<GoalMilestone>)
    @Update suspend fun updateMilestone(milestone: GoalMilestone)
    @Query("DELETE FROM goal_milestones WHERE id = :id") suspend fun deleteMilestoneById(id: Long)
    @Query("DELETE FROM goal_milestones WHERE goalId = :goalId") suspend fun deleteMilestonesForGoal(goalId: Long)
    @Query("DELETE FROM goal_milestones") suspend fun deleteMilestones()
    @Query("UPDATE goal_tasks SET milestoneId = NULL, updatedAt = :timestamp WHERE milestoneId = :milestoneId")
    suspend fun detachTasksFromMilestone(milestoneId: Long, timestamp: Long)

    // ---- Tasks ----
    @Query("SELECT * FROM goal_tasks WHERE goalId = :goalId ORDER BY sortOrder, id")
    fun observeTasksForGoal(goalId: Long): Flow<List<GoalTask>>
    // Hub summaries need one query for every goal, never per-goal N+1 reads.
    @Query("SELECT * FROM goal_tasks ORDER BY goalId, sortOrder, id") fun observeAllTasks(): Flow<List<GoalTask>>
    @Query("SELECT * FROM goal_phases ORDER BY goalId, sortOrder, id") fun observeAllPhases(): Flow<List<GoalPhase>>
    @Query("SELECT * FROM goal_tasks WHERE id = :id") suspend fun task(id: Long): GoalTask?
    @Query("SELECT * FROM goal_tasks WHERE uuid = :uuid") suspend fun taskByUuid(uuid: String): GoalTask?
    @Query("SELECT * FROM goal_tasks WHERE goalId = :goalId ORDER BY sortOrder, id")
    suspend fun tasksForGoal(goalId: Long): List<GoalTask>
    @Query("SELECT * FROM goal_tasks ORDER BY goalId, sortOrder, id") suspend fun allTasks(): List<GoalTask>
    @Insert suspend fun insertTask(task: GoalTask): Long
    @Insert suspend fun insertTasks(tasks: List<GoalTask>)
    @Update suspend fun updateTask(task: GoalTask)
    @Query("DELETE FROM goal_tasks WHERE id = :id") suspend fun deleteTaskById(id: Long)
    @Query("DELETE FROM goal_tasks WHERE goalId = :goalId") suspend fun deleteTasksForGoal(goalId: Long)
    @Query("DELETE FROM goal_tasks") suspend fun deleteTasks()
    @Query("UPDATE goal_tasks SET sortOrder = :position, updatedAt = :timestamp WHERE id = :id")
    suspend fun setTaskSortOrder(id: Long, position: Int, timestamp: Long)

    // ---- Dependencies ----
    @Query(
        "SELECT d.* FROM goal_task_dependencies d INNER JOIN goal_tasks t ON d.dependentTaskId = t.id " +
            "WHERE t.goalId = :goalId ORDER BY d.dependentTaskId, d.prerequisiteTaskId"
    )
    fun observeDependenciesForGoal(goalId: Long): Flow<List<GoalTaskDependency>>
    @Query("SELECT * FROM goal_task_dependencies WHERE dependentTaskId = :taskId")
    suspend fun prerequisitesOf(taskId: Long): List<GoalTaskDependency>
    @Query("SELECT * FROM goal_task_dependencies WHERE prerequisiteTaskId = :taskId")
    suspend fun dependentsOf(taskId: Long): List<GoalTaskDependency>
    @Query("SELECT * FROM goal_task_dependencies ORDER BY dependentTaskId, prerequisiteTaskId")
    suspend fun allDependencies(): List<GoalTaskDependency>
    @Insert suspend fun insertDependency(dependency: GoalTaskDependency)
    @Insert suspend fun insertDependencies(dependencies: List<GoalTaskDependency>)
    @Query(
        "DELETE FROM goal_task_dependencies WHERE dependentTaskId = :dependentTaskId AND prerequisiteTaskId = :prerequisiteTaskId"
    )
    suspend fun deleteDependency(dependentTaskId: Long, prerequisiteTaskId: Long)
    @Query("DELETE FROM goal_task_dependencies WHERE dependentTaskId = :taskId OR prerequisiteTaskId = :taskId")
    suspend fun deleteDependenciesForTask(taskId: Long)
    @Query(
        "DELETE FROM goal_task_dependencies WHERE dependentTaskId IN " +
            "(SELECT id FROM goal_tasks WHERE goalId = :goalId) " +
            "OR prerequisiteTaskId IN (SELECT id FROM goal_tasks WHERE goalId = :goalId)"
    )
    suspend fun deleteDependenciesForGoal(goalId: Long)
    @Query("DELETE FROM goal_task_dependencies") suspend fun deleteDependencies()

    // ---- Schedule revisions ----
    @Query(
        "SELECT * FROM goal_schedule_revisions WHERE entityUuid IN (SELECT uuid FROM goal_tasks WHERE goalId = :goalId) " +
            "OR entityUuid IN (SELECT uuid FROM goal_milestones WHERE goalId = :goalId) " +
            "OR entityUuid IN (SELECT uuid FROM goal_phases WHERE goalId = :goalId) " +
            "OR entityUuid IN (SELECT uuid FROM goals WHERE id = :goalId) ORDER BY changedAt, id"
    )
    fun observeRevisionsForGoal(goalId: Long): Flow<List<GoalScheduleRevision>>
    @Query("SELECT * FROM goal_schedule_revisions WHERE entityUuid = :entityUuid AND field = :field ORDER BY changedAt, id")
    suspend fun revisionsForEntityField(entityUuid: String, field: String): List<GoalScheduleRevision>
    @Query("SELECT * FROM goal_schedule_revisions ORDER BY changedAt, id") suspend fun allScheduleRevisions(): List<GoalScheduleRevision>
    @Insert suspend fun insertScheduleRevisions(revisions: List<GoalScheduleRevision>)
    @Query("DELETE FROM goal_schedule_revisions WHERE entityUuid = :entityUuid")
    suspend fun deleteScheduleRevisionsForEntity(entityUuid: String)
    @Query(
        "DELETE FROM goal_schedule_revisions WHERE entityUuid IN (SELECT uuid FROM goal_tasks WHERE goalId = :goalId) " +
            "OR entityUuid IN (SELECT uuid FROM goal_milestones WHERE goalId = :goalId) " +
            "OR entityUuid IN (SELECT uuid FROM goal_phases WHERE goalId = :goalId) " +
            "OR entityUuid IN (SELECT uuid FROM goals WHERE id = :goalId)"
    )
    suspend fun deleteScheduleRevisionsForGoal(goalId: Long)
    @Query("DELETE FROM goal_schedule_revisions") suspend fun deleteScheduleRevisions()
}
