package com.example.llamadroid.harness

import com.example.llamadroid.harness.runtime.HarnessRuntimeLogEvent
import com.example.llamadroid.harness.runtime.HarnessRuntimeState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HarnessRuntimeJournalTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun webBootCountsSurviveRestartWithoutArbitraryFields() {
        val file = temporary.newFolder().resolve("diagnostics.json")
        HarnessRuntimeJournal(file).recordConnection("webview", "webview", "interface", "failure",
            errorCode = "HARNESS_WEBUI_BOOT_TIMEOUT",
            webBoot = mapOf("entries" to 58, "mounted" to 0, "resourceError" to 6, "private-prompt" to 17, "assets" to -1))
        val row = HarnessRuntimeJournal(file).also { it.load() }.entries.value.single()
        assertEquals(mapOf("entries" to 58, "mounted" to 0, "resourceError" to 6), row.webBoot)
        assertTrue(runtimeDiagnosticsText(listOf(row)).contains("boot.entries=58"))
        assertFalse(diskText(file).contains("private-prompt"))
    }

    @Test fun connectionFailureIncludesReviewedOperationAndStatusWithoutPrivateData() {
        val file = temporary.newFolder().resolve("diagnostics.json")
        val journal = HarnessRuntimeJournal(file)
        journal.recordConnection("rpc", "http", "settings/describe", "failure",
            durationMs = 150, httpStatus = 403, errorCode = "transport/http-403")
        journal.recordConnection("websocket", "websocket", "private prompt/sk-secret", "retrying",
            errorCode = "private credential", closeCode = 1008)
        val rows = HarnessRuntimeJournal(file).also { it.load() }.entries.value
        assertEquals(403, rows.first().httpStatus)
        assertEquals("settings/describe", rows.first().operation)
        assertEquals("transport/http-403", rows.first().errorCode)
        assertEquals("extension", rows.last().operation)
        assertEquals(1008, rows.last().closeCode)
        assertTrue(runtimeDiagnosticsText(rows).contains("operation=settings/describe outcome=failure httpStatus=403"))
        assertFalse(diskText(file).contains("private"))
        assertFalse(diskText(file).contains("sk-secret"))
    }

    @Test fun firstStartupWithoutAnyConversationSurvivesRestart() {
        val file = temporary.newFolder().resolve("diagnostics.json")
        HarnessRuntimeJournal(file) { 1234L }.record(event("phase").copy(phase = "payload_preparing"))
        HarnessRuntimeJournal(file) { 1235L }.record(event("start_failed").copy(
            errorCode = "HARNESS_EXECUTABLE_MISSING", exitCode = 127,
        ))
        val reopened = HarnessRuntimeJournal(file).also { it.load() }.entries.value
        assertEquals(listOf("phase", "start_failed"), reopened.map { it.event })
        assertEquals("payload_preparing", reopened.first().phase)
        assertEquals("HARNESS_EXECUTABLE_MISSING", reopened.last().errorCode)
        assertEquals(127, reopened.last().exitCode)
    }

    @Test fun retainedRowsAndDiskBytesStayBounded() {
        val file = temporary.newFolder().resolve("diagnostics.json")
        val journal = HarnessRuntimeJournal(file) { 500L }
        repeat(2003) { journal.record(event("start_requested")) }
        assertTrue(journal.entries.value.size in 1000..2000)
        assertEquals(2003L, journal.entries.value.last().id)
        val segments = file.parentFile.listFiles().orEmpty().flatMap { it.walkTopDown().filter { child -> child.isFile }.toList() }
        assertTrue(segments.sumOf { it.length() } <= HarnessRuntimeJournal.MAX_BYTES)
        assertEquals(journal.entries.value, HarnessRuntimeJournal(file).also { it.load() }.entries.value)
    }

    @Test fun unreviewedTextAndInvalidMetadataNeverReachDisk() {
        val file = temporary.newFolder().resolve("diagnostics.json")
        val journal = HarnessRuntimeJournal(file)
        journal.record(event("private prompt"))
        journal.record(event("start_failed").copy(
            generation = "secret-generation", phase = "private-path", errorCode = "private-tool-output",
            durationMs = -1L, exitCode = 10_000,
        ))
        val row = journal.entries.value.single()
        assertNull(row.phase)
        assertNull(row.generation)
        assertNull(row.durationMs)
        assertNull(row.exitCode)
        assertEquals("RemoteError", row.errorCode)
        val saved = diskText(file)
        assertFalse(saved.contains("private"))
        assertFalse(saved.contains("secret"))
    }

    @Test fun corruptJournalAndWriteFailureRemainRecoverable() {
        val file = temporary.newFile().also { it.writeText("invalid json") }
        val journal = HarnessRuntimeJournal(file).also { it.load() }
        assertEquals("journal_unreadable", journal.entries.value.single().event)
        journal.record(event("start_requested"))
        assertEquals(2, HarnessRuntimeJournal(file).also { it.load() }.entries.value.size)
        val parentFile = temporary.newFile()
        val unwritable = HarnessRuntimeJournal(parentFile.resolve("child"))
        unwritable.record(event("start_requested"))
        assertEquals("journal_write_failed", unwritable.entries.value.last().event)
    }

    @Test fun frequentReadSuccessIsCoalescedAndClearSurvivesReopen() {
        val file = temporary.newFolder().resolve("diagnostics.json")
        var now = 10L
        val journal = HarnessRuntimeJournal(file) { now }
        journal.record(event("started"))
        repeat(100) { journal.recordConnection("rpc", "http", "session/list", "success") }
        assertEquals(2, journal.entries.value.size)
        now += 30_000
        journal.recordConnection("rpc", "http", "session/list", "success")
        assertEquals(99, journal.entries.value.last().coalescedCount)
        repeat(3) { journal.recordConnection("rpc", "http", "session/list", "failure", errorCode = "transport/http-403") }
        assertEquals(6, journal.entries.value.size)
        journal.clear()
        assertTrue(HarnessRuntimeJournal(file).also { it.load() }.entries.value.isEmpty())
        journal.record(event("started"))
        assertEquals(1L, journal.entries.value.single().id)
    }

    @Test fun routineRotationPreservesStartupAndFailureEvidence() {
        val file = temporary.newFolder().resolve("diagnostics.json")
        var now = 10L
        val journal = HarnessRuntimeJournal(file) { now }
        journal.record(event("started"))
        journal.record(event("process_exited").copy(errorCode = "HARNESS_PROCESS_EXITED", exitCode = 137))
        repeat(8_005) {
            now += 30_000
            journal.recordConnection("rpc", "http", "session/list", "success")
        }
        val reopened = HarnessRuntimeJournal(file).also { it.load() }.entries.value
        assertTrue(reopened.any { it.event == "started" })
        assertTrue(reopened.any { it.exitCode == 137 })
        assertTrue(reopened.size <= HarnessRuntimeJournal.MAX_ENTRIES)
    }

    private fun diskText(file: java.io.File) = file.parentFile.walkTopDown().filter { it.isFile }.joinToString { it.readText() }

    private fun event(name: String) = HarnessRuntimeLogEvent(
        name, "shared", "01234567-89ab-cdef-0123-456789abcdef", HarnessRuntimeState.STARTING,
    )
}
