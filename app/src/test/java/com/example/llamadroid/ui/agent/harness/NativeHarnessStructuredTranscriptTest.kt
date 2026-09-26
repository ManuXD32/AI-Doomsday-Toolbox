package com.example.llamadroid.ui.agent.harness

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeHarnessStructuredTranscriptTest {
    @Test
    fun toolCallAndResultExposeStructuredStatusArgumentsAndAttachmentMetadata() {
        val rows = parseNativeHarnessStructuredTranscript(listOf(
            event(1, "tool/call") {
                put("callId", "call-1")
                put("name", "read_image")
                put("arguments", "{\"file_path\":\"image.png\"}")
            },
            event(2, "tool/result") {
                putJsonObject("message") {
                    putJsonObject("source") { put("callId", "call-1") }
                    putJsonArray("content") {
                        add(buildJsonObject {
                            put("type", "tool-result")
                            put("toolCallId", "call-1")
                            put("isError", false)
                            putJsonArray("content") {
                                add(buildJsonObject { put("type", "text"); put("text", "created") })
                                add(buildJsonObject {
                                    put("type", "image")
                                    putJsonObject("attachment") {
                                        put("attachmentId", "img-1")
                                        put("mediaType", "image/png")
                                        put("bytes", 100L)
                                        put("width", 10)
                                        put("height", 20)
                                        put("name", "image.png")
                                    }
                                })
                                add(buildJsonObject {
                                    put("type", "file")
                                    putJsonObject("attachment") {
                                        put("attachmentId", "file-1")
                                        put("name", "report.txt")
                                        put("bytes", 20L)
                                    }
                                })
                            }
                        })
                    }
                }
            },
        ))
        assertEquals(1, rows.size)
        val tool = rows.single().parts.single() as NativeHarnessStructuredTranscriptPart.Tool
        assertEquals("call-1", tool.callId)
        assertEquals("read_image", tool.name)
        assertEquals(NativeHarnessStructuredToolStatus.COMPLETED, tool.status)
        assertEquals("{\"file_path\":\"image.png\"}", tool.arguments)
        val image = tool.result.filterIsInstance<NativeHarnessStructuredTranscriptPart.Image>().single().attachment
        assertTrue(image.canOpen)
        assertEquals("img-1", image.attachmentId)
        val file = tool.result.filterIsInstance<NativeHarnessStructuredTranscriptPart.File>().single().attachment
        assertFalse(file.canOpen)
        assertEquals("report.txt", file.name)
    }

    @Test
    fun assistantMessagePreservesImageAndFileMetadataWithoutRetainingHugePreview() {
        val rows = parseNativeHarnessStructuredTranscript(listOf(
            event(4, "assistant/message") {
                putJsonObject("message") {
                    put("id", "message-1")
                    putJsonObject("source") { put("model", "deepseek-chat") }
                    putJsonArray("content") {
                        add(buildJsonObject { put("type", "text"); put("text", "hello") })
                        add(buildJsonObject {
                            put("type", "image")
                            putJsonObject("attachment") {
                                put("attachmentId", "img-2")
                                put("mediaType", "image/jpeg")
                                put("bytes", 200L)
                                put("width", 20)
                                put("height", 30)
                            }
                        })
                        add(buildJsonObject {
                            put("type", "file")
                            putJsonObject("attachment") {
                                put("attachmentId", "file-2")
                                put("name", "notes.md")
                                put("bytes", 300L)
                            }
                        })
                    }
                }
            },
        ))
        val row = rows.single()
        assertEquals(HarnessTranscriptRole.ASSISTANT, row.role)
        assertEquals("deepseek-chat", row.label)
        assertEquals("message-1", row.messageId)
        assertNotNull(row.parts.filterIsInstance<NativeHarnessStructuredTranscriptPart.Image>().single())
        assertNotNull(row.parts.filterIsInstance<NativeHarnessStructuredTranscriptPart.File>().single())
    }

    @Test
    fun incrementalToolResultCorrelatesAndIgnoresDuplicateSequence() {
        val call = event(20, "tool/call") {
            put("callId", "call-live")
            put("name", "shell")
            put("arguments", "{\"command\":\"pwd\"}")
        }
        val result = event(21, "tool/result") {
            putJsonObject("message") {
                putJsonObject("source") { put("callId", "call-live") }
                putJsonArray("content") {
                    add(buildJsonObject {
                        put("type", "tool-result")
                        put("toolCallId", "call-live")
                        putJsonArray("content") {
                            add(buildJsonObject { put("type", "text"); put("text", "/workspace") })
                        }
                    })
                }
            }
        }
        val running = mergeNativeHarnessStructuredTranscript(emptyList(), call)
        val complete = mergeNativeHarnessStructuredTranscript(running, result)
        val duplicate = mergeNativeHarnessStructuredTranscript(complete, result)
        assertEquals(1, complete.size)
        assertEquals(complete, duplicate)
        val tool = complete.single().parts.single() as NativeHarnessStructuredTranscriptPart.Tool
        assertEquals(NativeHarnessStructuredToolStatus.COMPLETED, tool.status)
        assertEquals(listOf(20L, 21L), (complete.single().detailRef as NativeHarnessTranscriptDetailRef.SessionEvents).sequences)
        assertEquals("{\"command\":\"pwd\"}", tool.arguments)
    }

    @Test
    fun parserBoundsRowsPartsAndArguments() {
        val records = (1..700).map { sequence ->
            event(sequence.toLong(), "user/message") {
                putJsonArray("content") {
                    add(buildJsonObject { put("type", "text"); put("text", "row-$sequence") })
                }
            }
        }
        val rows = parseNativeHarnessStructuredTranscript(records)
        assertEquals(NativeHarnessStructuredTranscriptLimits.MAX_ITEMS, rows.size)
        assertEquals("row-700", (rows.last().parts.single() as NativeHarnessStructuredTranscriptPart.Text).value)

        val manyParts = event(701, "user/message") {
            putJsonArray("content") {
                repeat(NativeHarnessStructuredTranscriptLimits.MAX_PARTS + 4) { index ->
                    add(buildJsonObject { put("type", "text"); put("text", "part-$index") })
                }
            }
        }
        val parts = parseNativeHarnessStructuredTranscript(listOf(manyParts)).single().parts
        assertEquals(NativeHarnessStructuredTranscriptLimits.MAX_PARTS + 1, parts.size)
        assertTrue(parts.last() is NativeHarnessStructuredTranscriptPart.Truncated)

        val longArgument = "x".repeat(NativeHarnessStructuredTranscriptLimits.MAX_ARGUMENTS + 500)
        val longRow = parseNativeHarnessStructuredTranscript(listOf(event(702, "tool/call") {
            put("callId", "long")
            put("name", "bash")
            put("arguments", longArgument)
        })).single()
        val argument = (longRow.parts.single() as NativeHarnessStructuredTranscriptPart.Tool).arguments
        assertTrue(argument.length <= NativeHarnessStructuredTranscriptLimits.MAX_ARGUMENTS)

        val wideContent = event(703, "user/message") {
            putJsonArray("content") {
                repeat(NativeHarnessStructuredTranscriptLimits.MAX_PARTS) {
                    add(buildJsonObject {
                        put("type", "text")
                        put("text", "y".repeat(NativeHarnessStructuredTranscriptLimits.MAX_TEXT))
                    })
                }
            }
        }
        val boundedParts = parseNativeHarnessStructuredTranscript(listOf(wideContent)).single().parts
        assertTrue(
            boundedParts.filterIsInstance<NativeHarnessStructuredTranscriptPart.Text>()
                .sumOf { it.value.length } <= NativeHarnessStructuredTranscriptLimits.MAX_ROW_PREVIEW_CHARS,
        )
        assertTrue(boundedParts.last() is NativeHarnessStructuredTranscriptPart.Truncated)
    }

    @Test
    fun detailViewerPagesExactArgumentsAndRejectsOversizedWireRecords() {
        val argument = "a".repeat(NativeHarnessStructuredTranscriptLimits.MAX_DETAIL_PAGE_CHARS * 2 + 17)
        val record = event(9, "tool/call") {
            put("callId", "call-9")
            put("name", "bash")
            put("arguments", argument)
        }
        val reference = NativeHarnessTranscriptDetailRef.SessionEvents(listOf(9L))
        val first = parseNativeHarnessTranscriptDetailPage(reference, listOf(record), 0)
        assertEquals(3, first.pageCount)
        assertTrue(first.lines.sumOf { it.text.length } <= NativeHarnessStructuredTranscriptLimits.MAX_DETAIL_PAGE_CHARS)
        val all = (0 until first.pageCount).joinToString("") { page ->
            parseNativeHarnessTranscriptDetailPage(reference, listOf(record), page).lines.joinToString("") { it.text }
        }
        assertEquals(argument, all)

        val tooLarge = event(10, "tool/call") {
            put("callId", "too-large")
            put("name", "bash")
            put("arguments", "z".repeat(NativeHarnessStructuredTranscriptLimits.MAX_DETAIL_WIRE_CHARS + 1))
        }
        val error = parseNativeHarnessTranscriptDetailPage(
            NativeHarnessTranscriptDetailRef.SessionEvents(listOf(10L)),
            listOf(tooLarge),
            0,
        )
        assertEquals("TRANSCRIPT_DETAIL_TOO_LARGE", error.errorCode)
    }

    @Test
    fun longPlainMessageKeepsAnExpandableBoundedPreview() {
        val row = parseNativeHarnessStructuredTranscript(listOf(event(11, "assistant/message") {
            putJsonObject("message") {
                putJsonArray("content") {
                    add(buildJsonObject {
                        put("type", "text")
                        put("text", "q".repeat(NativeHarnessStructuredTranscriptLimits.MAX_TEXT + 10))
                    })
                }
            }
        })).single()
        assertTrue(row.isExpandable)
        assertTrue(row.parts.any { it is NativeHarnessStructuredTranscriptPart.Truncated })
        assertTrue(
            row.parts.filterIsInstance<NativeHarnessStructuredTranscriptPart.Text>()
                .single().value.length <= NativeHarnessStructuredTranscriptLimits.MAX_TEXT,
        )
    }

    @Test
    fun detailViewerAcceptsCanonicalMessageDepthAndShowsErrorMetadata() {
        val record = event(12, "tool/result") {
            putJsonObject("message") {
                putJsonObject("source") { put("kind", "tool"); put("callId", "call-12") }
                putJsonArray("content") {
                    add(buildJsonObject {
                        put("type", "tool-result")
                        put("toolCallId", "call-12")
                        putJsonArray("content") {
                            add(buildJsonObject { put("type", "text"); put("text", "failed") })
                        }
                    })
                }
            }
            putJsonObject("error") {
                put("name", "ToolError")
                put("code", "NOPE")
                put("reason", "the tool stopped")
            }
            putJsonObject("meta") {
                put("attempt", 1)
                putJsonObject("nested") { put("visible", true) }
            }
        }
        val page = parseNativeHarnessTranscriptDetailPage(
            NativeHarnessTranscriptDetailRef.SessionEvents(listOf(12L)),
            listOf(record),
            0,
        )
        assertEquals(null, page.errorCode)
        val detail = page.lines.joinToString("\n") { it.text }
        assertTrue(detail.contains("the tool stopped"))
        assertTrue(detail.contains("attempt"))
    }

    @Test
    fun completedTurnTailUsesFinalAssistantSequenceAndPerTurnUsage() {
        val rows = parseNativeHarnessStructuredTranscript(listOf(
            timedEvent(1, 100, "turn/start") { put("turn", 1) },
            timedEvent(2, 110, "step/start") { put("turn", 1); put("step", 1) },
            timedEvent(3, 125, "assistant/message") {
                put("turn", 1)
                put("step", 1)
                putJsonObject("message") {
                    put("id", "answer-1")
                    putJsonObject("source") { put("provider", "test"); put("model", "test-model") }
                    putJsonArray("content") {
                        add(buildJsonObject { put("type", "text"); put("text", "answer") })
                    }
                }
                putJsonObject("usage") {
                    put("inputTokens", 10)
                    put("outputTokens", 5)
                    put("cacheReadTokens", 2)
                    put("cacheWriteTokens", 1)
                    put("reasoningTokens", 1)
                    put("totalTokens", 18)
                }
                putJsonArray("stream") {
                    add(buildJsonObject {
                        put("type", "text-chunks")
                        put("time0", 115L)
                        put("index", 0)
                        putJsonArray("dt") {}
                        putJsonArray("texts") { add(JsonPrimitive("answer")) }
                    })
                }
            },
            timedEvent(4, 140, "step/end") { put("turn", 1); put("step", 1) },
            timedEvent(5, 160, "turn/end") { put("turn", 1); putJsonObject("reason") { put("kind", "completed") } },
        ))
        val row = rows.single { it.role == HarnessTranscriptRole.ASSISTANT }
        val tail = row.turnTail
        assertNotNull(tail)
        assertEquals(1, row.turn)
        assertEquals(5L, tail?.tailSequence)
        assertEquals(3L, tail?.branchSequence)
        assertFalse(tail?.branchUnavailable == true)
        assertEquals("answer", tail?.text)
        assertEquals(10L, tail?.usage?.inputTokens)
        assertEquals(5L, tail?.usage?.outputTokens)
        assertEquals(18L, tail?.usage?.totalTokens)
        assertEquals(60L, tail?.timing?.runMs)
        assertEquals(5L, tail?.timing?.ttftMs)
        assertEquals(500.0, tail?.timing?.tokensPerSecond ?: -1.0, 0.001)
    }

    @Test
    fun turnUsageOmitsContradictoryProviderTotal() {
        val rows = parseNativeHarnessStructuredTranscript(listOf(
            event(50, "turn/start") { put("turn", 8) },
            event(51, "step/start") { put("turn", 8); put("step", 1) },
            event(52, "assistant/message") {
                put("turn", 8); put("step", 1)
                putJsonObject("message") {
                    putJsonArray("content") { add(buildJsonObject { put("type", "text"); put("text", "answer") }) }
                }
                putJsonObject("usage") {
                    put("inputTokens", 10); put("outputTokens", 5)
                    put("cacheReadTokens", 2); put("cacheWriteTokens", 1)
                    put("totalTokens", 19)
                }
            },
            event(53, "step/end") { put("turn", 8); put("step", 1) },
            event(54, "turn/end") { put("turn", 8); putJsonObject("reason") { put("kind", "completed") } },
        ))
        assertNull(rows.single { it.role == HarnessTranscriptRole.ASSISTANT }.turnTail?.usage)
    }

    @Test
    fun userMessageGetsTurnCoordinateFromOpenTurnBoundary() {
        val rows = parseNativeHarnessStructuredTranscript(listOf(
            event(60, "turn/start") { put("turn", 9) },
            event(61, "user/message") {
                putJsonObject("message") {
                    put("id", "prompt-9")
                    putJsonObject("source") { put("kind", "user") }
                    putJsonArray("content") { add(buildJsonObject { put("type", "text"); put("text", "prompt") }) }
                }
            },
        ))
        val user = rows.single { it.role == HarnessTranscriptRole.USER }
        assertEquals(9, user.turn)
        assertEquals(61L, user.sequence)
    }

    @Test
    fun userMessageWithoutTurnStartStillRetainsCopyableRowWithUnknownTurn() {
        val rows = parseNativeHarnessStructuredTranscript(listOf(
            event(62, "user/message") {
                putJsonObject("message") {
                    put("id", "prompt-without-start")
                    putJsonObject("source") { put("kind", "user") }
                    putJsonArray("content") {
                        add(buildJsonObject { put("type", "text"); put("text", "offline prompt") })
                    }
                }
            },
        ))
        val user = rows.single { it.role == HarnessTranscriptRole.USER }
        assertEquals(null, user.turn)
        assertEquals("prompt-without-start", user.messageId)
        assertEquals(62L, user.sequence)
    }

    @Test
    fun skillInvocationNamesAttachToAuthoredMessageAcrossTransparentContext() {
        val rows = parseNativeHarnessStructuredTranscript(listOf(
            event(70, "turn/start") { put("turn", 10) },
            event(71, "user/message") {
                putJsonObject("message") {
                    put("id", "authored-10")
                    putJsonObject("source") { put("kind", "user") }
                    putJsonArray("content") {
                        add(buildJsonObject { put("type", "text"); put("text", "/demo run") })
                    }
                }
            },
            event(72, "user/message") {
                putJsonObject("message") {
                    putJsonObject("source") { put("kind", "agent-instructions") }
                    putJsonArray("content") {
                        add(buildJsonObject { put("type", "text"); put("text", "rules") })
                    }
                }
            },
            event(73, "user/message") {
                putJsonObject("message") {
                    putJsonObject("source") {
                        put("kind", "skill-invocation")
                        put("name", "demo")
                    }
                    putJsonArray("content") {
                        add(buildJsonObject { put("type", "text"); put("text", "loaded") })
                    }
                }
            },
            event(74, "assistant/message") {
                put("turn", 10)
                put("step", 1)
                putJsonObject("message") {
                    putJsonArray("content") {
                        add(buildJsonObject { put("type", "text"); put("text", "answer") })
                    }
                }
            },
            event(75, "turn/end") { put("turn", 10) },
        ))
        val authored = rows.single { it.messageId == "authored-10" }
        assertEquals(setOf("demo"), authored.skillNames)
        assertEquals(emptySet<String>(), rows.single { it.sequence == 73L }.skillNames)
        val links = harnessInlineLinks(
            "/demo run",
            userReferences = true,
            skillNames = authored.skillNames,
        )
        assertTrue(links.any { it.target == HarnessInlineTarget.Skill("demo") })
    }

    @Test
    fun liveSkillInjectionReprojectsAlreadyParsedAuthoredMessage() {
        val authored = event(80, "user/message") {
            putJsonObject("message") {
                put("id", "live-authored")
                putJsonObject("source") { put("kind", "user") }
                putJsonArray("content") {
                    add(buildJsonObject { put("type", "text"); put("text", "/demo run") })
                }
            }
        }
        val instructions = event(81, "user/message") {
            putJsonObject("message") {
                putJsonObject("source") { put("kind", "agent-instructions") }
                putJsonArray("content") {
                    add(buildJsonObject { put("type", "text"); put("text", "rules") })
                }
            }
        }
        val injection = event(82, "user/message") {
            putJsonObject("message") {
                putJsonObject("source") {
                    put("kind", "skill-invocation")
                    put("name", "demo")
                }
                putJsonArray("content") {
                    add(buildJsonObject { put("type", "text"); put("text", "loaded") })
                }
            }
        }

        var rows = mergeNativeHarnessStructuredTranscript(emptyList(), authored)
        rows = mergeNativeHarnessStructuredTranscript(rows, instructions)
        rows = mergeNativeHarnessStructuredTranscript(rows, injection)

        assertEquals(setOf("demo"), rows.single { it.messageId == "live-authored" }.skillNames)
        assertEquals(emptySet<String>(), rows.single { it.sequence == 82L }.skillNames)
        assertEquals("skill-invocation", rows.single { it.sequence == 82L }.sourceKind)
        assertEquals("demo", rows.single { it.sequence == 82L }.sourceName)
    }

    @Test
    fun laterToolEvidenceDisablesTurnBranch() {
        val rows = parseNativeHarnessStructuredTranscript(listOf(
            timedEvent(1, 100, "turn/start") { put("turn", 2) },
            timedEvent(2, 110, "step/start") { put("turn", 2); put("step", 1) },
            timedEvent(3, 120, "assistant/message") {
                put("turn", 2)
                put("step", 1)
                putJsonObject("message") {
                    putJsonArray("content") { add(buildJsonObject { put("type", "text"); put("text", "before tool") }) }
                }
            },
            timedEvent(4, 125, "tool/call") {
                put("turn", 2); put("step", 1); put("callId", "tool-2"); put("name", "read"); put("arguments", "{}")
            },
            timedEvent(5, 130, "step/end") { put("turn", 2); put("step", 1) },
            timedEvent(6, 140, "turn/end") { put("turn", 2); putJsonObject("reason") { put("kind", "completed") } },
        ))
        val tail = rows.single { it.role == HarnessTranscriptRole.ASSISTANT }.turnTail
        assertNotNull(tail)
        assertTrue(tail?.branchUnavailable == true)
        assertEquals(3L, tail?.branchSequence)
    }

    @Test
    fun replacementToolResultDoesNotDisableTurnBranch() {
        val rows = parseNativeHarnessStructuredTranscript(listOf(
            timedEvent(1, 100, "turn/start") { put("turn", 6) },
            timedEvent(2, 110, "step/start") { put("turn", 6); put("step", 1) },
            timedEvent(3, 120, "assistant/message") {
                put("turn", 6)
                put("step", 1)
                putJsonObject("message") {
                    putJsonArray("content") { add(buildJsonObject { put("type", "text"); put("text", "answer") }) }
                }
            },
            timedEvent(4, 125, "tool/result", surfaceOp = "replace") {
                put("turn", 6)
                putJsonObject("message") { putJsonArray("content") {} }
            },
            timedEvent(5, 140, "turn/end") { put("turn", 6); putJsonObject("reason") { put("kind", "completed") } },
        ))
        val tail = rows.single { it.role == HarnessTranscriptRole.ASSISTANT }.turnTail
        assertNotNull(tail)
        assertFalse(tail?.branchUnavailable == true)
    }

    @Test
    fun completedOlderTurnKeepsBranchWhenALaterTurnExists() {
        val rows = parseNativeHarnessStructuredTranscript(listOf(
            event(80, "turn/start") { put("turn", 1) },
            event(81, "assistant/message") {
                put("turn", 1)
                put("step", 1)
                putJsonObject("message") {
                    putJsonArray("content") {
                        add(buildJsonObject { put("type", "text"); put("text", "old") })
                    }
                }
            },
            event(82, "turn/end") { put("turn", 1) },
            event(83, "turn/start") { put("turn", 2) },
            event(84, "assistant/message") {
                put("turn", 2)
                put("step", 1)
                putJsonObject("message") {
                    putJsonArray("content") {
                        add(buildJsonObject { put("type", "text"); put("text", "new") })
                    }
                }
            },
        ))
        val oldTail = rows.single { it.sequence == 81L }.turnTail
        assertNotNull(oldTail)
        assertFalse(oldTail?.branchUnavailable == true)
    }

    @Test
    fun retryAndCompactionRowsPreserveOfficialLifecycleStates() {
        val retryRows = parseNativeHarnessStructuredTranscript(listOf(
            event(10, "turn/start") { put("turn", 3) },
            event(11, "step/start") { put("turn", 3); put("step", 1) },
            event(12, "llm/retry") {
                put("retryId", "retry-3")
                put("turn", 3); put("step", 1); put("retry", 1); put("mode", "normal")
                put("maxRetries", 2); put("delayMs", 500L); put("provider", "test")
                putJsonObject("failure") { put("code", "TEMP"); put("message", "temporary") }
            },
            event(13, "llm/retry-started") {
                put("retryId", "retry-3"); put("turn", 3); put("step", 1); put("retry", 1)
            },
        ))
        val retry = retryRows.single { it.retry != null }.retry
        assertNotNull(retry)
        assertEquals(NativeHarnessRetryState.STARTED, retry?.current?.state)
        assertEquals(500L, retry?.current?.delayMs)
        assertEquals("TEMP", retry?.current?.failureCode)

        val compactionRows = parseNativeHarnessStructuredTranscript(listOf(
            event(20, "compaction/start") { put("compactionId", "compact-1"); put("turn", 4) },
            event(21, "compaction/summary") {
                put("compactionId", "compact-1")
                putJsonArray("summary") { add(buildJsonObject { put("type", "text"); put("text", "kept context") }) }
                putJsonArray("shadowedSeqs") { add(JsonPrimitive(1)); add(JsonPrimitive(2)) }
                put("shadowedTokenCount", 99L)
            },
            event(22, "compaction/end") { put("compactionId", "compact-1"); put("turn", 4) },
            event(23, "user/message", surfaceOp = "replace") {
                put("turn", 4)
                putJsonObject("message") {
                    putJsonObject("source") { put("kind", "plugin"); put("plugin", "compact"); put("compactionId", "compact-1") }
                    putJsonArray("content") { add(buildJsonObject { put("type", "text"); put("text", "replacement") }) }
                }
            },
        ))
        val compaction = compactionRows.single { it.compaction != null }.compaction
        assertNotNull(compaction)
        assertEquals(23L, compaction?.sequence)
        assertEquals(23L, compaction?.checkpointSequence)
        assertEquals(2, compaction?.shadowedItemCount)
        assertEquals(99L, compaction?.shadowedTokenCount)
        assertEquals("kept context", compaction?.summary)
        assertTrue(compactionRows.none { it.sequence == 23L && it.compaction == null })

        val failed = parseNativeHarnessStructuredTranscript(listOf(
            event(30, "compaction/start") { put("compactionId", "compact-failed") },
            event(31, "compaction/end") {
                put("compactionId", "compact-failed")
                put("error", "aborted")
            },
        )).single { it.compaction != null }.compaction
        assertEquals(NativeHarnessCompactionState.FAILED, failed?.state)
    }

    @Test
    fun liveMergeAddsTurnTailWhenTurnEndHasNoStructuredContent() {
        val start = event(30, "turn/start") { put("turn", 5) }
        val assistant = event(31, "assistant/message") {
            put("turn", 5)
            put("step", 1)
            putJsonObject("message") {
                putJsonArray("content") { add(buildJsonObject { put("type", "text"); put("text", "live answer") }) }
            }
        }
        val end = event(32, "turn/end") {
            put("turn", 5)
            putJsonObject("reason") { put("kind", "completed") }
        }
        var rows = mergeNativeHarnessStructuredTranscript(emptyList(), start)
        rows = mergeNativeHarnessStructuredTranscript(rows, assistant)
        rows = mergeNativeHarnessStructuredTranscript(rows, end)
        val tail = rows.single().turnTail
        assertNotNull(tail)
        assertEquals(32L, tail?.tailSequence)
        assertEquals(31L, tail?.branchSequence)
        assertFalse(tail?.branchUnavailable == true)
    }

    @Test
    fun liveMergeRetainsUsageAndTimingAtTurnEnd() {
        val start = timedEvent(90, 100, "turn/start") { put("turn", 11) }
        val stepStart = timedEvent(91, 105, "step/start") { put("turn", 11); put("step", 1) }
        val assistant = timedEvent(92, 120, "assistant/message") {
            put("turn", 11)
            put("step", 1)
            putJsonObject("message") {
                putJsonArray("content") {
                    add(buildJsonObject { put("type", "text"); put("text", "counted") })
                }
            }
            putJsonObject("usage") {
                put("inputTokens", 10)
                put("outputTokens", 5)
                put("cacheReadTokens", 2)
                put("cacheWriteTokens", 1)
                put("totalTokens", 18)
            }
            putJsonArray("stream") {
                add(buildJsonObject {
                    put("type", "text-chunks")
                    put("time0", 110L)
                    putJsonArray("dt") {}
                    putJsonArray("texts") { add(JsonPrimitive("counted")) }
                })
            }
        }
        val stepEnd = timedEvent(93, 130, "step/end") { put("turn", 11); put("step", 1) }
        val end = timedEvent(94, 140, "turn/end") { put("turn", 11) }
        var rows = mergeNativeHarnessStructuredTranscript(emptyList(), start)
        rows = mergeNativeHarnessStructuredTranscript(rows, stepStart)
        rows = mergeNativeHarnessStructuredTranscript(rows, assistant)
        rows = mergeNativeHarnessStructuredTranscript(rows, stepEnd)
        rows = mergeNativeHarnessStructuredTranscript(rows, end)
        val tail = rows.single { it.sequence == 92L }.turnTail
        assertNotNull(tail)
        assertEquals(10L, tail?.usage?.inputTokens)
        assertEquals(5L, tail?.usage?.outputTokens)
        assertEquals(18L, tail?.usage?.totalTokens)
        assertEquals(40L, tail?.timing?.runMs)
        assertEquals(5L, tail?.timing?.ttftMs)
    }

    @Test
    fun liveCompactionFailureReplacesStartedState() {
        var rows = mergeNativeHarnessStructuredTranscript(
            emptyList(),
            event(95, "compaction/start") { put("compactionId", "live-failed") },
        )
        rows = mergeNativeHarnessStructuredTranscript(
            rows,
            event(96, "compaction/end") {
                put("compactionId", "live-failed")
                put("error", "aborted")
            },
        )
        assertEquals(
            NativeHarnessCompactionState.FAILED,
            rows.single { it.compaction != null }.compaction?.state,
        )
    }

    @Test
    fun closedTurnStillProducesTailCardWhenAssistantPageIsOutsideWindow() {
        val rows = parseNativeHarnessStructuredTranscript(listOf(
            timedEvent(40, 100, "turn/start") { put("turn", 7) },
            timedEvent(41, 140, "turn/end") { put("turn", 7); putJsonObject("reason") { put("kind", "completed") } },
        ))
        val tail = rows.single().turnTail
        assertNotNull(tail)
        assertEquals(7, tail?.turn)
        assertEquals(41L, tail?.tailSequence)
        assertEquals(null, tail?.branchSequence)
        assertTrue(tail?.branchUnavailable == true)
    }

    @Test
    fun packedUsageCountsFailedAndSuccessfulAttemptsWithoutTopLevelUsage() {
        fun attempt(sequence: Long, type: String, input: Int, output: Int) = timedEvent(sequence, sequence * 10, type) {
            put("turn", 1); put("step", 1)
            if (type == "assistant/message") putJsonObject("message") {
                putJsonArray("content") { add(buildJsonObject { put("type", "text"); put("text", "answer") }) }
            }
            putJsonArray("stream") {
                add(buildJsonObject {
                    put("type", "chunk"); put("time", sequence * 10)
                    putJsonObject("chunk") {
                        put("type", "usage")
                        putJsonObject("usage") {
                            put("inputTokens", input); put("outputTokens", output)
                            put("cacheReadTokens", 0); put("cacheWriteTokens", 0)
                            put("totalTokens", input + output)
                        }
                    }
                })
            }
        }
        val rows = parseNativeHarnessStructuredTranscript(listOf(
            timedEvent(1, 10, "turn/start") { put("turn", 1) },
            timedEvent(2, 20, "step/start") { put("turn", 1); put("step", 1) },
            attempt(3, "assistant/attempt", 10, 5),
            timedEvent(4, 40, "llm/retry") { put("turn", 1); put("step", 1) },
            timedEvent(5, 50, "llm/retry-started") { put("turn", 1); put("step", 1) },
            attempt(6, "assistant/message", 20, 10),
            timedEvent(7, 70, "step/end") { put("turn", 1); put("step", 1) },
            timedEvent(8, 80, "turn/end") { put("turn", 1) },
        ))
        val usage = rows.single { it.sequence == 6L }.turnTail?.usage
        assertNotNull(usage)
        assertEquals(30L, usage?.inputTokens)
        assertEquals(15L, usage?.outputTokens)
        assertEquals(45L, usage?.total)
    }

    @Test
    fun defaultSessionTitleDoesNotInventEnglishPrefixForBareIds() {
        assertEquals("ef123456", harnessDefaultSessionTitle("abcdef123456", null))
    }

    private fun event(
        sequence: Long,
        type: String,
        surfaceOp: String? = null,
        data: JsonObjectBuilder.() -> Unit,
    ): JsonObject = buildJsonObject {
        putJsonObject("event") {
            put("seq", sequence)
            put("type", type)
            (surfaceOp ?: "append".takeIf { type in setOf("assistant/message", "user/message", "tool/result") })
                ?.let { put("surfaceOp", it) }
            put("data", buildJsonObject(data))
        }
    }

    private fun timedEvent(
        sequence: Long,
        time: Long,
        type: String,
        surfaceOp: String? = null,
        data: JsonObjectBuilder.() -> Unit,
    ): JsonObject = buildJsonObject {
        putJsonObject("event") {
            put("seq", sequence)
            put("time", time)
            put("type", type)
            (surfaceOp ?: "append".takeIf { type in setOf("assistant/message", "user/message", "tool/result") })
                ?.let { put("surfaceOp", it) }
            put("data", buildJsonObject(data))
        }
    }
}
