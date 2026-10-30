package com.example.llamadroid.service.delegation

import org.junit.Assert.assertEquals
import org.junit.Test

class DelegationThreadIdsTest {
    @Test fun parsesAllThreadsAndCurrentWithoutDuplicates() {
        val json = """[{"id":"s1","title":"a"},{"id":"s2","title":"b"}]"""
        assertEquals(listOf("s1", "s2", "s3"), DelegationThreadIds.parse(json, "s3"))
        assertEquals(listOf("s1", "s2"), DelegationThreadIds.parse(json, "s2"))
    }

    @Test fun toleratesBadJson() {
        assertEquals(listOf("s9"), DelegationThreadIds.parse("not json", "s9"))
        assertEquals(emptyList<String>(), DelegationThreadIds.parse(null, null))
    }
}
