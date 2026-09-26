package com.example.llamadroid.ui.agent.harness

import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeHarnessCommandHistoryTest {
    @Test
    fun liveCommandFilterIncludesNestedToolDispatches() {
        assertTrue(isHarnessCommandEvent("tool/call"))
        assertTrue(isHarnessCommandEvent("tool/result"))
        assertTrue(isHarnessCommandEvent("tool/ptc-dispatch-start"))
        assertTrue(isHarnessCommandEvent("tool/ptc-dispatch"))
        assertFalse(isHarnessCommandEvent("turn/end"))
        assertFalse(isHarnessCommandEvent(null))
    }

    @Test
    fun joinsShellToolCallAndResultIntoBoundedTimedRun() {
        val runs = mergeNativeHarnessCommandRuns(
            emptyList(),
            listOf(
                shellCall(10, 1_700_000_000_000, "call-a", "pwd"),
                shellResult(11, 1_700_000_000_450, "call-a", " /workspace\n "),
            ),
        )

        assertEquals(1, runs.size)
        assertEquals("pwd", runs.single().command)
        assertEquals(" /workspace\n ", runs.single().output)
        assertEquals(HarnessCommandRunStatus.COMPLETED, runs.single().status)
        assertEquals(1_700_000_000_000L, runs.single().timestampMs)
        assertEquals(450L, runs.single().durationMs)
    }

    @Test
    fun failedShellResultKeepsErrorOutputAndNonShellCallsAreIgnored() {
        val runs = mergeNativeHarnessCommandRuns(
            emptyList(),
            listOf(
                toolCall(1, "read", "read-a", "secret.txt"),
                shellCall(2, 2_000, "shell-b", "make test"),
                shellResult(3, 2_300, "shell-b", "compile error", error = true),
            ),
        )

        assertEquals(1, runs.size)
        assertEquals("make test", runs.single().command)
        assertEquals("compile error", runs.single().output)
        assertEquals(HarnessCommandRunStatus.FAILED, runs.single().status)
        assertEquals(300L, runs.single().durationMs)
    }

    @Test
    fun nestedRunCodeBashDispatchIsTrackedWithoutShowingOuterProgram() {
        val runs = mergeNativeHarnessCommandRuns(
            emptyList(),
            listOf(
                toolCall(1, "run_code", "outer", "ignored"),
                ptcCall(2, 1_700_000_000_000, "outer:ptc:1", "bash", "printf hello"),
                ptcResult(3, 1_700_000_000_600, "outer:ptc:1", "hello"),
            ),
        )

        assertEquals(1, runs.size)
        assertEquals("outer:ptc:1", runs.single().id)
        assertEquals("printf hello", runs.single().command)
        assertEquals("hello", runs.single().output)
        assertEquals(HarnessCommandRunStatus.COMPLETED, runs.single().status)
        assertEquals(600L, runs.single().durationMs)
    }

    @Test
    fun failedNestedRunCodeBashDispatchAndLaterJobOutputUpdateTheSameRows() {
        val calls = linkedMapOf<String, String>()
        val outputs = linkedMapOf<String, HarnessCommandJobOutputProjection>()
        val first = mergeNativeHarnessCommandRuns(
            emptyList(),
            listOf(
                ptcCall(1, 2_000, "outer:ptc:1", "bash", "sleep 30"),
                ptcResult(2, 2_100, "outer:ptc:1", "started background job job-ptc"),
                ptcCall(3, 2_200, "outer:ptc:2", "bash", "false"),
                ptcResult(4, 2_400, "outer:ptc:2", "failed", error = true),
            ),
            calls,
            outputs,
        )
        val updated = mergeNativeHarnessCommandRuns(
            first,
            listOf(
                ptcCall(5, 2_500, "outer:ptc:3", "job_output", "job-ptc"),
                ptcResult(6, 4_000, "outer:ptc:3", "done\n[status: completed]"),
            ),
            calls,
            outputs,
        )

        assertEquals(2, updated.size)
        assertEquals(HarnessCommandRunStatus.COMPLETED, updated.first().status)
        assertEquals("done\n[status: completed]", updated.first().output)
        assertEquals(HarnessCommandRunStatus.FAILED, updated.last().status)
    }

    @Test
    fun nonzeroShellExitCodeMarksRunFailedWithoutIsErrorFlag() {
        val runs = mergeNativeHarnessCommandRuns(
            emptyList(),
            listOf(
                shellCall(1, 2_000, "shell-exit", "false"),
                shellResult(2, 2_300, "shell-exit", "", exitCode = 2),
            ),
        )

        assertEquals(HarnessCommandRunStatus.FAILED, runs.single().status)
    }

    @Test
    fun renderedNonzeroExitMarkerMarksRunFailed() {
        val output = "command output\n[exit code: 2]"
        val runs = mergeNativeHarnessCommandRuns(
            emptyList(),
            listOf(
                shellCall(1, 2_000, "shell-marker", "false"),
                shellResult(2, 2_300, "shell-marker", output),
            ),
        )

        assertEquals(HarnessCommandRunStatus.FAILED, runs.single().status)
        assertEquals(output, runs.single().output)
    }

    @Test
    fun liveOutputAppearsBeforeExitAndFinalResultRemainsAuthoritative() {
        val offsets = linkedMapOf<HarnessCommandOutputCursorKey, Long>()
        val running = mergeNativeHarnessCommandRuns(
            emptyList(),
            listOf(shellCall(1, 2_000, "streamed", "printf live")),
        )
        val streamed = appendNativeHarnessCommandLiveOutput(
            running,
            callId = "streamed",
            stream = "stdout",
            offset = 0L,
            text = "live output\n",
            offsets = offsets,
        )

        assertEquals(HarnessCommandRunStatus.RUNNING, streamed.single().status)
        assertEquals("live output\n", streamed.single().output)

        val duplicate = appendNativeHarnessCommandLiveOutput(
            streamed,
            callId = "streamed",
            stream = "stdout",
            offset = 0L,
            text = "live output\n",
            offsets = offsets,
        )
        assertEquals("live output\n", duplicate.single().output)

        val completed = mergeNativeHarnessCommandRuns(
            streamed,
            listOf(shellResult(2, 2_450, "streamed", "final authoritative output")),
        )
        assertEquals(HarnessCommandRunStatus.COMPLETED, completed.single().status)
        assertEquals("final authoritative output", completed.single().output)

        val lateDelta = appendNativeHarnessCommandLiveOutput(
            completed,
            callId = "streamed",
            stream = "stdout",
            offset = 12L,
            text = "late preview",
            offsets = offsets,
        )
        assertEquals("final authoritative output", lateDelta.single().output)
    }

    @Test
    fun liveOutputDeduplicatesEachStreamAndMarksOffsetGaps() {
        val offsets = linkedMapOf<HarnessCommandOutputCursorKey, Long>()
        val running = mergeNativeHarnessCommandRuns(
            emptyList(),
            listOf(shellCall(1, 2_000, "two-streams", "printf output")),
        )
        val stdout = appendNativeHarnessCommandLiveOutput(
            running, "two-streams", "stdout", 0L, "out", offsets,
        )
        val stderr = appendNativeHarnessCommandLiveOutput(
            stdout, "two-streams", "stderr", 0L, "err", offsets,
        )
        val duplicate = appendNativeHarnessCommandLiveOutput(
            stderr, "two-streams", "stderr", 0L, "err", offsets,
        )
        val gap = appendNativeHarnessCommandLiveOutput(
            duplicate, "two-streams", "stdout", 12L, "tail", offsets,
        )

        assertEquals("outerr", duplicate.single().output)
        assertEquals("outerrtail", gap.single().output)
        assertTrue(gap.single().outputTruncated)
    }

    @Test
    fun renderedBackgroundLaunchMarkerKeepsJobIdAndDetachedStatus() {
        val runs = mergeNativeHarnessCommandRuns(
            emptyList(),
            listOf(
                shellCall(1, 2_000, "background-marker", "sleep 30"),
                shellResult(2, 2_300, "background-marker", "started background job job-42"),
            ),
        )

        assertEquals("job-42", runs.single().jobId)
        assertEquals(HarnessCommandRunStatus.BACKGROUND, runs.single().status)
    }

    @Test
    fun jobOutputResultCorrelatesToBackgroundCommandAcrossFollowEvents() {
        val jobOutputCalls = linkedMapOf<String, String>()
        val jobOutputById = linkedMapOf<String, HarnessCommandJobOutputProjection>()
        val launched = mergeNativeHarnessCommandRuns(
            emptyList(),
            listOf(
                shellCall(1, 2_000, "background-shell", "sleep 30"),
                shellResult(2, 2_300, "background-shell", "started background job job-42"),
            ),
            jobOutputCalls,
            jobOutputById,
        )
        val updated = mergeNativeHarnessCommandRuns(
            launched,
            listOf(
                jobOutputCall(3, "output-call", "job-42"),
                shellResult(4, 5_000, "output-call", "finished output\n[status: completed]"),
            ),
            jobOutputCalls,
            jobOutputById,
        )

        assertEquals(1, updated.size)
        assertEquals("job-42", updated.single().jobId)
        assertEquals("finished output\n[status: completed]", updated.single().output)
        assertEquals(HarnessCommandRunStatus.COMPLETED, updated.single().status)
        assertEquals(3_000L, updated.single().durationMs)
    }

    @Test
    fun jobOutputResultIsCachedUntilOlderLaunchPageArrives() {
        val jobOutputCalls = linkedMapOf<String, String>()
        val jobOutputById = linkedMapOf<String, HarnessCommandJobOutputProjection>()
        val recent = mergeNativeHarnessCommandRuns(
            emptyList(),
            listOf(
                jobOutputCall(10, "output-call", "job-later"),
                shellResult(11, 4_000, "output-call", "finished\n[status: failed]"),
            ),
            jobOutputCalls,
            jobOutputById,
        )
        val merged = mergeNativeHarnessCommandRuns(
            recent,
            listOf(
                shellCall(1, 2_000, "background-shell", "sleep 30"),
                shellResult(2, 2_300, "background-shell", "started background job job-later"),
            ),
            jobOutputCalls,
            jobOutputById,
        )

        assertEquals(1, merged.size)
        assertEquals(HarnessCommandRunStatus.FAILED, merged.single().status)
        assertEquals("finished\n[status: failed]", merged.single().output)
    }

    @Test
    fun historyIsOrderedAndCappedAndOutputPreviewIsBounded() {
        val records = (1L..(HARNESS_COMMAND_HISTORY_LIMIT + 5).toLong()).map { sequence ->
            shellCall(sequence, sequence * 100, "call-$sequence", "echo $sequence")
        }
        val bounded = mergeNativeHarnessCommandRuns(emptyList(), records)
        assertEquals(HARNESS_COMMAND_HISTORY_LIMIT, bounded.size)
        assertEquals("call-6", bounded.first().id)
        assertEquals("call-${HARNESS_COMMAND_HISTORY_LIMIT + 5}", bounded.last().id)

        val output = "x".repeat(HARNESS_COMMAND_OUTPUT_LIMIT + 5)
        val run = mergeNativeHarnessCommandRuns(
            emptyList(),
            listOf(shellCall(200, 20_000, "long", "cat file"), shellResult(201, 21_000, "long", output)),
        ).single()
        assertEquals(HARNESS_COMMAND_OUTPUT_LIMIT, run.output.length)
        assertTrue(run.outputTruncated)
    }

    @Test
    fun pageMergeRetainsOlderRunsWithoutDuplicatingCalls() {
        val latest = mergeNativeHarnessCommandRuns(emptyList(), listOf(shellCall(20, 200, "new", "new")))
        val merged = mergeNativeHarnessCommandRuns(
            latest,
            listOf(shellCall(10, 100, "old", "old"), shellResult(21, 210, "new", "ok")),
        )

        assertEquals(listOf("old", "new"), merged.map { it.id })
        assertEquals("ok", merged.last().output)
        assertEquals(2, mergeNativeHarnessCommandRuns(merged, listOf(shellCall(20, 200, "new", "new"))).size)
    }

    private fun shellCall(sequence: Long, time: Long, callId: String, command: String) = buildJsonObject {
        putJsonObject("event") {
            put("seq", sequence)
            put("time", time)
            put("type", "tool/call")
            putJsonObject("data") {
                put("callId", callId)
                put("name", "shell")
                put("arguments", "{\"command\":\"$command\"}")
            }
        }
    }

    private fun shellResult(
        sequence: Long,
        time: Long,
        callId: String,
        output: String,
        error: Boolean = false,
        exitCode: Int? = null,
    ) = buildJsonObject {
        putJsonObject("event") {
            put("seq", sequence)
            put("time", time)
            put("type", "tool/result")
            putJsonObject("data") {
                if (error) put("isError", true)
                putJsonObject("message") {
                    putJsonObject("source") { put("callId", callId) }
                    putJsonArray("content") {
                        add(buildJsonObject {
                            put("type", "tool-result")
                            put("toolCallId", callId)
                            if (exitCode != null) put("exitCode", exitCode)
                            putJsonArray("content") {
                                add(buildJsonObject { put("type", "text"); put("text", output) })
                            }
                        })
                    }
                }
            }
        }
    }

    private fun toolCall(sequence: Long, name: String, callId: String, command: String) = buildJsonObject {
        putJsonObject("event") {
            put("seq", sequence)
            put("type", "tool/call")
            putJsonObject("data") {
                put("callId", callId)
                put("name", name)
                put("arguments", "{\"command\":\"$command\"}")
            }
        }
    }

    private fun jobOutputCall(sequence: Long, callId: String, jobId: String) = buildJsonObject {
        putJsonObject("event") {
            put("seq", sequence)
            put("type", "tool/call")
            putJsonObject("data") {
                put("callId", callId)
                put("name", "job_output")
                put("arguments", """{"jobId":"$jobId"}""")
            }
        }
    }

    private fun ptcCall(sequence: Long, time: Long, callId: String, name: String, value: String) = buildJsonObject {
        putJsonObject("event") {
            put("seq", sequence)
            put("time", time)
            put("type", "tool/ptc-dispatch-start")
            putJsonObject("data") {
                put("subCallId", callId)
                put("name", name)
                putJsonObject("arguments") {
                    put(if (name == "job_output") "jobId" else "command", value)
                }
            }
        }
    }

    private fun ptcResult(sequence: Long, time: Long, callId: String, output: String, error: Boolean = false) =
        buildJsonObject {
            putJsonObject("event") {
                put("seq", sequence)
                put("time", time)
                put("type", "tool/ptc-dispatch")
                putJsonObject("data") {
                    put("subCallId", callId)
                    if (error) put("isError", true)
                    putJsonArray("content") {
                        add(buildJsonObject { put("type", "text"); put("text", output) })
                    }
                }
            }
        }
}
