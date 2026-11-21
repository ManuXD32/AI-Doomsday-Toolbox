package com.example.llamadroid.training

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DatasetHelpersTest {
    @Test
    fun detectsFormatFromExtension() {
        assertEquals(DatasetFileKind.JSONL, DatasetHelpers.fileKind("a.JSONL"))
        assertEquals(DatasetFileKind.PARQUET, DatasetHelpers.fileKind("x/y.parquet"))
        assertEquals(DatasetFileKind.CSV, DatasetHelpers.fileKind("t.tsv"))
        assertNull(DatasetHelpers.fileKind("noext"))
        assertNull(DatasetHelpers.fileKind("a.zip"))
        assertEquals(DatasetFormat.TEXT, DatasetHelpers.detectFormat("book.txt"))
        assertEquals(DatasetFormat.AUTO, DatasetHelpers.detectFormat("chat.json"))
        assertTrue(DatasetHelpers.isSupported("x.json"))
        assertFalse(DatasetHelpers.isSupported("x.exe"))
    }

    @Test
    fun estimatesAndCaps() {
        assertEquals(0L, DatasetHelpers.estimateRows(0, DatasetFileKind.JSON, DatasetFormat.AUTO))
        assertEquals(1L, DatasetHelpers.estimateRows(10, DatasetFileKind.JSON, DatasetFormat.AUTO))
        assertEquals(1_000L, DatasetHelpers.estimateRows(1_400_000, DatasetFileKind.JSONL, DatasetFormat.CHAT))
        assertNull(DatasetHelpers.capToRows(0, DatasetFileKind.JSON, DatasetFormat.AUTO))
        assertTrue(DatasetHelpers.capApplies(10, 11L * 1024 * 1024))
        assertFalse(DatasetHelpers.capApplies(10, 5L * 1024 * 1024))
        assertFalse(DatasetHelpers.capApplies(0, 1L shl 40))
    }

    @Test
    fun columnsAndFileNames() {
        assertEquals("a=b,c=d", DatasetHelpers.columnsArg(linkedMapOf("a" to "b", "c" to "d")))
        assertEquals(mapOf("k" to "v"), DatasetHelpers.parseColumnMap("""{"k":"v","n":1}"""))
        assertTrue(DatasetHelpers.parseColumnMap("oops").isEmpty())
        assertEquals("evil.json", DatasetHelpers.safeFileName("../../evil.json"))
        assertEquals("dataset", DatasetHelpers.safeFileName("..."))
        assertEquals("a_b.txt", DatasetHelpers.safeFileName("a:b.txt"))
    }
}
