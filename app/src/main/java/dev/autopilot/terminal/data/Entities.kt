package dev.autopilot.terminal.data

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class TaskStatus {
    PLANNING, RUNNING, AWAIT_CONFIRM, PAUSED_LIMIT, DONE, STOPPED, FAILED
}

enum class StepStatus {
    PENDING, RUNNING, SUCCESS, FAILED, SKIPPED
}

enum class ChannelLevel {
    SANDBOX, SHELL;

    fun label(): String = when (this) {
        SANDBOX -> "沙箱级"
        SHELL -> "Shell 级"
    }
}

@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val goal: String,
    val criteriaJson: String = "[]",
    val status: TaskStatus = TaskStatus.PLANNING,
    val channelLevel: ChannelLevel = ChannelLevel.SANDBOX,
    val degraded: Boolean = false,
    val iterations: Int = 0,
    val reportSummary: String? = null,
    val changedFiles: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val finishedAt: Long? = null
)

@Entity(tableName = "plan_steps")
data class PlanStepEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: Long,
    val stepIndex: Int,
    val command: String,
    val description: String = "",
    val expect: String = "",
    val status: StepStatus = StepStatus.PENDING,
    val exitCode: Int? = null,
    val outputDigest: String? = null
)

@Entity(tableName = "audit_entries")
data class AuditEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: Long,
    val timestamp: Long = System.currentTimeMillis(),
    val channelLevel: ChannelLevel,
    val command: String,
    val exitCode: Int?
)
