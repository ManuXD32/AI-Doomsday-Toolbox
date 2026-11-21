package com.example.llamadroid.training

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CuratedDatasetsTest {
    @Test
    fun idsAreUniqueAndEntriesComplete() {
        val all = CuratedDatasets.all
        assertEquals(all.size, all.map { it.id }.toSet().size)
        all.forEach {
            assertTrue("${it.id} licence", it.licence.isNotBlank())
            assertTrue("${it.id} repo", it.repo.contains('/'))
            assertTrue("${it.id} files", it.files.isNotEmpty())
            assertTrue("${it.id} cap", it.defaultCapMb > 0)
            assertTrue("${it.id} columns", it.columnMap.isNotEmpty())
            it.files.forEach { f -> assertTrue("${it.id} size", f.sizeBytes in 1..500_000_000L) }
        }
    }

    @Test
    fun everyGroupHasEntries() {
        DatasetGroup.entries.forEach { g ->
            assertTrue("$g empty", CuratedDatasets.byGroup().getValue(g).isNotEmpty())
        }
    }

    @Test
    fun columnMapRoundTrips() {
        val map = CuratedDatasets.byId("hermes-function-calling")!!.columnMap
        assertEquals(map, DatasetHelpers.parseColumnMap(DatasetHelpers.columnMapJson(map)))
    }
}
