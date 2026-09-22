package com.example.llamadroid.harness

import androidx.room.withTransaction
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.HarnessRuntimeEntity
import com.example.llamadroid.harness.runtime.HarnessRuntimeRecord
import com.example.llamadroid.harness.runtime.HarnessRuntimeState
import com.example.llamadroid.harness.runtime.HarnessRuntimeStore

/** One durable owner, with transactions preventing stale process callbacks replacing a new run. */
class RoomHarnessRuntimeStore(private val database: AppDatabase) : HarnessRuntimeStore {
    private val dao = database.harnessDao()

    override suspend fun current(): HarnessRuntimeRecord? = dao.runtime()?.toRecord()

    override suspend fun beginStart(record: HarnessRuntimeRecord): Boolean = database.withTransaction {
        val current = dao.runtime()
        if (current != null && current.state in ACTIVE_STATES) return@withTransaction false
        dao.saveRuntime(record.toEntity())
        true
    }

    override suspend fun compareAndSet(
        expectedStates: Set<HarnessRuntimeState>,
        record: HarnessRuntimeRecord
    ): Boolean = database.withTransaction {
        val current = dao.runtime() ?: return@withTransaction false
        if (current.generation != record.generation || expectedStates.none { it.name == current.state }) {
            return@withTransaction false
        }
        dao.saveRuntime(record.toEntity())
        true
    }

    override suspend fun replace(record: HarnessRuntimeRecord) {
        database.withTransaction { dao.saveRuntime(record.toEntity()) }
    }

    private fun HarnessRuntimeEntity.toRecord() = HarnessRuntimeRecord(
        environmentId = environmentId,
        state = runCatching { HarnessRuntimeState.valueOf(state) }.getOrDefault(HarnessRuntimeState.INTERRUPTED),
        generation = generation ?: "uninitialized",
        brokerPid = brokerPid?.toInt(), processGroupId = processGroupId?.toInt(),
        processStartTicks = processStartTicks, childPid = childPid, childStartTicks = childStartTicks,
        nodePid = nodePid, nodeStartTicks = nodeStartTicks,
        port = port, runtimeVersion = runtimeVersion, startedAt = startedAt,
        stopRequestedAt = stopRequestedAt, forceStopRequestedAt = forceStopRequestedAt,
        endedAt = endedAt, updatedAt = updatedAt, errorCode = errorCode
    )

    private fun HarnessRuntimeRecord.toEntity() = HarnessRuntimeEntity(
        environmentId = environmentId, state = state.name, generation = generation,
        brokerPid = brokerPid?.toLong(), processGroupId = processGroupId?.toLong(),
        processStartTicks = processStartTicks, childPid = childPid, childStartTicks = childStartTicks,
        nodePid = nodePid, nodeStartTicks = nodeStartTicks,
        port = port, runtimeVersion = runtimeVersion ?: "0.1.6-alpha.2", startedAt = startedAt,
        stopRequestedAt = stopRequestedAt, forceStopRequestedAt = forceStopRequestedAt,
        endedAt = endedAt, errorCode = errorCode, updatedAt = updatedAt
    )

    private companion object {
        val ACTIVE_STATES = setOf("STARTING", "RUNNING", "STOP_REQUESTED", "FORCE_STOPPING")
    }
}
