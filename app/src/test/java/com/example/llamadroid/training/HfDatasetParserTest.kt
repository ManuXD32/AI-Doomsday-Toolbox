package com.example.llamadroid.training

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HfDatasetParserTest {
    @Test
    fun searchParsesEntriesAndSkipsBadOnes() {
        val json = """[{"id":"a/b","downloads":5,"likes":2,"gated":false,"tags":["license:mit"]},
            {"downloads":1},{"id":"c/d","gated":"auto","cardData":{"license":["apache-2.0","mit"]}},"junk"]"""
        val list = HfDatasetParser.parseSearch(json)
        assertEquals(listOf("a/b", "c/d"), list.map { it.id })
        assertEquals("mit", list[0].licence)
        assertTrue(list[1].gated)
        assertEquals("apache-2.0, mit", list[1].licence)
    }

    @Test
    fun infoReadsLicenceFromCardDataThenTags() {
        val card = HfDatasetParser.parseInfo("""{"id":"x/y","gated":false,"sha":"abc","cardData":{"license":"cc-by-4.0"},"tags":["license:mit"]}""")!!
        assertEquals("cc-by-4.0", card.licence)
        assertEquals("abc", card.sha)
        val tags = HfDatasetParser.parseInfo("""{"id":"x/y","gated":"manual","tags":["license:odc-by","language:en"]}""")!!
        assertEquals("odc-by", tags.licence)
        assertTrue(tags.gated)
        assertNull(HfDatasetParser.parseInfo("""{"id":"x/y"}""")!!.licence)
    }

    @Test
    fun treeKeepsFilesOnly() {
        val files = HfDatasetParser.parseTree(
            """[{"type":"directory","path":"data"},{"type":"file","path":"data/a.parquet","size":123},{"type":"file","path":"README.md"}]"""
        )
        assertEquals(listOf("data/a.parquet", "README.md"), files.map { it.path })
        assertEquals(123L, files[0].sizeBytes)
        assertEquals(0L, files[1].sizeBytes)
    }

    @Test
    fun parquetListParses() {
        val files = HfDatasetParser.parseParquet(
            """{"parquet_files":[{"dataset":"d","config":"default","split":"train","url":"https://h/0000.parquet","filename":"0000.parquet","size":9}],"pending":[]}"""
        )
        assertEquals(1, files.size)
        assertEquals("train", files[0].split)
        assertEquals(9L, files[0].sizeBytes)
    }

    @Test
    fun badJsonNeverThrows() {
        assertTrue(HfDatasetParser.parseSearch("not json").isEmpty())
        assertTrue(HfDatasetParser.parseSearch("{}").isEmpty())
        assertNull(HfDatasetParser.parseInfo("["))
        assertTrue(HfDatasetParser.parseTree("").isEmpty())
        assertTrue(HfDatasetParser.parseParquet("""{"error":"x"}""").isEmpty())
        assertFalse(HfDatasetParser.parseInfo("""{"id":"a/b"}""")!!.gated)
    }

    @Test
    fun resolveUrlEncodesSegments() {
        assertEquals(
            "https://huggingface.co/datasets/a/b/resolve/main/data/x%20y.parquet",
            HfDatasetClient.resolveUrl("a/b", "main", "data/x y.parquet")
        )
        assertEquals(
            "https://huggingface.co/datasets/a/b/resolve/refs%2Fconvert%2Fparquet/p/0000.parquet",
            HfDatasetClient.resolveUrl("a/b", "refs/convert/parquet", "p/0000.parquet")
        )
    }
}
