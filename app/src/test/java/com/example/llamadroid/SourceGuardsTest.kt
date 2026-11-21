package com.example.llamadroid

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source-level guards for bug classes that compile but crash at runtime.
 *
 * `val x by flow.collectAsState(initial = null)` without an explicit type can be inferred as
 * `Nothing?` (1.00015: ClassCastException LlamaChatEntity -> Void), so the type must be declared.
 */
class SourceGuardsTest {
    private val untypedNullInitial = Regex(
        """\b(?:val|var)\s+[A-Za-z_][A-Za-z0-9_]*\s+by\s+.*collectAsState\(\s*initial\s*=\s*null\s*\)"""
    )

    @Test
    fun collectAsStateWithNullInitialDeclaresExplicitType() {
        val srcDir = listOf("src/main/java", "app/src/main/java").map(::File).first { it.isDirectory }
        val offenders = srcDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    if (untypedNullInitial.containsMatchIn(line)) "${file.path}:${index + 1}: ${line.trim()}" else null
                }
            }
            .toList()
        assertTrue(
            "collectAsState(initial = null) needs an explicit nullable type:\n" + offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }

    private val earlyComposableReturn = Regex(
        """return@(Column|Row|Box|BoxWithConstraints|FlowRow|LazyColumn|LazyRow|Scaffold|Card|ElevatedCard|OutlinedCard|Surface|AppSectionCard)\b"""
    )

    /** Early returns from composable content lambdas unbalance Compose groups on recomposition (Branchy chart crash). */
    @Test
    fun composableContentLambdasDoNotReturnEarly() {
        val srcDir = listOf("src/main/java", "app/src/main/java").map(::File).first { it.isDirectory }
        val uiDir = File(srcDir, "com/example/llamadroid/ui")
        val offenders = uiDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    if (earlyComposableReturn.containsMatchIn(line)) "${file.path}:${index + 1}: ${line.trim()}" else null
                }
            }
            .toList()
        assertTrue(
            "Use if/else instead of return@<composable> in composable lambdas:\n" + offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }
}
