package com.example.llamadroid.service.filestation

import com.example.llamadroid.service.filestation.tools.CsvJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CsvJsonTest {
    @Test fun `parses quotes embedded newlines and doubled quotes`() {
        val rows = CsvJson.parseCsv("a,b,c\r\n\"x,1\",\"he said \"\"hi\"\"\",\"line1\nline2\"\r\n")
        assertEquals(listOf(listOf("a", "b", "c"), listOf("x,1", "he said \"hi\"", "line1\nline2")), rows)
    }

    @Test fun `skips blank lines handles missing final newline and bom`() {
        val rows = CsvJson.parseCsv("﻿a,b\n\n1,2")
        assertEquals(listOf(listOf("a", "b"), listOf("1", "2")), rows)
    }

    @Test fun `keeps empty fields and detects semicolon`() {
        assertEquals(listOf(listOf("a", "", "c")), CsvJson.parseCsv("a,,c"))
        assertEquals(';', CsvJson.detectDelimiter("a;b;c\n1;2;3"))
        assertEquals(',', CsvJson.detectDelimiter("\"a;b\",c"))
    }

    @Test fun `csv to json builds objects with unique headers and padding`() {
        val json = Json.parseToJsonElement(CsvJson.csvToJson("name,,name\nAda,1,x\nBob\nCy,1,2,3")).jsonArray
        assertEquals(3, json.size)
        val first = json[0].jsonObject
        assertEquals(listOf("name", "col2", "name_2"), first.keys.toList())
        assertEquals("Ada", first["name"]!!.jsonPrimitive.content)
        assertEquals("", json[1].jsonObject["col2"]!!.jsonPrimitive.content)
        assertEquals("3", json[2].jsonObject["col4"]!!.jsonPrimitive.content)
    }

    @Test fun `empty csv is an empty array`() {
        assertEquals("[]", CsvJson.csvToJson(""))
    }

    @Test fun `json to csv unions columns and quotes`() {
        val csv = CsvJson.jsonToCsv("""[{"a":1,"b":"x,y"},{"a":"q\"t","c":null,"d":[1,2]}]""")
        val lines = csv.split("\r\n")
        assertEquals("a,b,c,d", lines[0])
        assertEquals("1,\"x,y\",,", lines[1])
        assertEquals("\"q\"\"t\",,,\"[1,2]\"", lines[2])
    }

    @Test fun `round trip keeps awkward values`() {
        val original = "id,note\r\n1,\"multi\nline, with \"\"quotes\"\"\"\r\n2,plain\r\n"
        val back = CsvJson.jsonToCsv(CsvJson.csvToJson(original))
        assertEquals(CsvJson.parseCsv(original), CsvJson.parseCsv(back))
    }

    @Test fun `invalid json is rejected`() {
        val e = runCatching { CsvJson.jsonToCsv("{nope") }.exceptionOrNull()
        assertTrue(e is com.example.llamadroid.service.filestation.volume.FsException)
    }
}
