package dev.autopilot.terminal.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {
    @Insert
    suspend fun insert(task: TaskEntity): Long

    @Update
    suspend fun update(task: TaskEntity)

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun byId(id: Long): TaskEntity?

    @Query("SELECT * FROM tasks WHERE status IN ('PLANNING','RUNNING','AWAIT_CONFIRM','PAUSED_LIMIT') ORDER BY id DESC LIMIT 1")
    suspend fun activeTask(): TaskEntity?

    @Query("SELECT * FROM tasks WHERE status IN ('PLANNING','RUNNING','AWAIT_CONFIRM','PAUSED_LIMIT') ORDER BY id DESC LIMIT 1")
    fun observeActive(): Flow<TaskEntity?>

    @Query("SELECT * FROM tasks ORDER BY id DESC")
    fun observeHistory(): Flow<List<TaskEntity>>
}

@Dao
interface StepDao {
    @Insert
    suspend fun insertAll(steps: List<PlanStepEntity>): List<Long>

    @Update
    suspend fun update(step: PlanStepEntity)

    @Query("SELECT * FROM plan_steps WHERE taskId = :taskId ORDER BY stepIndex ASC")
    suspend fun byTask(taskId: Long): List<PlanStepEntity>

    @Query("SELECT * FROM plan_steps WHERE taskId = :taskId ORDER BY stepIndex ASC")
    fun observeByTask(taskId: Long): Flow<List<PlanStepEntity>>

    @Query("DELETE FROM plan_steps WHERE taskId = :taskId AND status = 'PENDING'")
    suspend fun clearPendingFor(taskId: Long)
}

@Dao
interface AuditDao {
    @Insert
    suspend fun insert(entry: AuditEntryEntity): Long

    @Query("SELECT * FROM audit_entries WHERE taskId = :taskId ORDER BY timestamp ASC")
    suspend fun byTask(taskId: Long): List<AuditEntryEntity>

    @Query("SELECT * FROM audit_entries ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<AuditEntryEntity>>

    @Query("SELECT COUNT(*) FROM audit_entries WHERE command = :command AND exitCode IS NULL AND taskId = :taskId")
    suspend fun countPendingConfirm(taskId: Long, command: String): Int
}
