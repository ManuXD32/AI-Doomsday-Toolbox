package com.example.llamadroid.service.filestation

import java.io.File
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FileStationRouterTest {
    private lateinit var h: TestServerHarness

    @Before fun start() { h = TestServerHarness() }
    @After fun stop() { h.close() }

    private fun errorCode(r: Resp) = r.json["error"]!!.jsonObject["code"]!!.jsonPrimitive.content
    private fun names(r: Resp) = r.json["entries"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }

    @Test fun `list filters hidden entries and sorts folders first`() {
        File(h.dirA, "b.txt").writeText("b")
        File(h.dirA, "a.txt").writeText("a")
        File(h.dirA, "zdir").mkdirs()
        File(h.dirA, ".attachments.a.txt").mkdirs()
        val r = h.call("GET", "/api/list?vol=a&path=")
        assertEquals(200, r.code)
        assertEquals(listOf("zdir", "a.txt", "b.txt"), names(r))
        assertEquals(listOf(".attachments.a.txt", "zdir", "a.txt", "b.txt"), names(h.call("GET", "/api/list?vol=a&hidden=1")))
        val etag = r.header("ETag")!!
        assertEquals(304, h.call("GET", "/api/list?vol=a&path=", headers = mapOf("If-None-Match" to etag)).code)
        assertEquals("NOT_FOUND", errorCode(h.call("GET", "/api/list?vol=nope")))
        assertEquals("INVALID_PATH", errorCode(h.call("GET", "/api/list?vol=a&path=..%2Fx")))
        assertEquals(3, h.call("GET", "/api/volumes").json["volumes"]!!.jsonArray.size)
    }

    @Test fun `mkdir rename move and copy across volumes`() {
        assertEquals(200, h.postJson("/api/mkdir", """{"vol":"a","path":"docs/sub"}""").code)
        assertTrue(File(h.dirA, "docs/sub").isDirectory)
        File(h.dirA, "docs/n.md").writeText("hello")
        assertEquals(200, h.postJson("/api/rename", """{"vol":"a","path":"docs/n.md","name":"m.md"}""").code)
        assertTrue(File(h.dirA, "docs/m.md").exists())

        val moved = h.postJson("/api/move", """{"items":[{"vol":"a","path":"docs/m.md"}],"dstVol":"b","dstDir":""}""")
        assertEquals(200, moved.code)
        assertEquals("hello", File(h.dirB, "m.md").readText())
        assertFalse(File(h.dirA, "docs/m.md").exists())

        val copied = h.postJson("/api/copy", """{"items":[{"vol":"b","path":"m.md"}],"dstVol":"a","dstDir":"docs"}""")
        assertEquals(200, copied.code)
        assertEquals("hello", File(h.dirA, "docs/m.md").readText())
        assertTrue(File(h.dirB, "m.md").exists())
        // Copy into the same folder renames.
        h.postJson("/api/copy", """{"items":[{"vol":"b","path":"m.md"}],"dstVol":"b","dstDir":""}""")
        assertTrue(File(h.dirB, "m (1).md").exists())
        // Folder into itself is refused.
        val self = h.postJson("/api/copy", """{"items":[{"vol":"a","path":"docs"}],"dstVol":"a","dstDir":"docs/sub"}""")
        assertEquals(400, self.code)
    }

    @Test fun `read only volume refuses every write`() {
        File(h.dirRo, "x.txt").writeText("x")
        assertEquals("READ_ONLY", errorCode(h.postJson("/api/mkdir", """{"vol":"ro","path":"d"}""")))
        assertEquals(403, h.postJson("/api/rename", """{"vol":"ro","path":"x.txt","name":"y.txt"}""").code)
        assertEquals(403, h.postJson("/api/delete", """{"items":[{"vol":"ro","path":"x.txt"}]}""").code)
        File(h.dirA, "f.txt").writeText("f")
        assertEquals(403, h.postJson("/api/copy", """{"items":[{"vol":"a","path":"f.txt"}],"dstVol":"ro","dstDir":""}""").code)
        assertEquals(403, h.postJson("/api/move", """{"items":[{"vol":"ro","path":"x.txt"}],"dstVol":"a","dstDir":""}""").code)
        assertEquals(403, h.call("PUT", "/api/upload?vol=ro&dir=&rel=u.bin&uploadId=abcdefgh&offset=0&total=1", byteArrayOf(1)).code)
        assertEquals(403, h.call("PUT", "/api/text?vol=ro&path=x.txt", "z".toByteArray(), mapOf("If-Match" to "*")).code)
        assertEquals(403, h.call("PUT", "/api/attachment?vol=ro&doc=x.txt&name=i.png", byteArrayOf(1)).code)
        assertEquals("x", File(h.dirRo, "x.txt").readText())
        // Reading still works.
        assertEquals(200, h.call("GET", "/raw/ro/x.txt").code)
    }

    @Test fun `csrf header origin and host checks`() {
        val noHeader = h.call("POST", "/api/mkdir", """{"vol":"a","path":"d"}""".toByteArray(), csrf = false)
        assertEquals(403, noHeader.code)
        assertEquals("FORBIDDEN", errorCode(noHeader))
        assertFalse(File(h.dirA, "d").exists())
        val body = """{"vol":"a","path":"d"}"""
        val foreign = h.raw(
            "POST /api/mkdir HTTP/1.1\r\nHost: 127.0.0.1:${h.port}\r\nOrigin: http://evil.example\r\n" +
                "X-FileStation: 1\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body",
        )
        assertTrue(foreign.startsWith("HTTP/1.1 403"))
        assertFalse(File(h.dirA, "d").exists())
        val same = h.raw(
            "POST /api/mkdir HTTP/1.1\r\nHost: 127.0.0.1:${h.port}\r\nOrigin: http://127.0.0.1:${h.port}\r\n" +
                "X-FileStation: 1\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body",
        )
        assertTrue(same.startsWith("HTTP/1.1 200"))
        val rebinding = h.raw("GET /api/volumes HTTP/1.1\r\nHost: evil.example\r\nConnection: close\r\n\r\n")
        assertTrue(rebinding.startsWith("HTTP/1.1 403"))
        assertNull(h.call("GET", "/api/volumes").header("Access-Control-Allow-Origin"))
    }

    @Test fun `security headers on api and static`() {
        val api = h.call("GET", "/api/volumes")
        assertTrue(api.header("Content-Security-Policy")!!.contains("default-src 'self'"))
        assertEquals("nosniff", api.header("X-Content-Type-Options"))
        assertEquals("no-referrer", api.header("Referrer-Policy"))
        assertEquals("no-store", api.header("Cache-Control"))
        val index = h.call("GET", "/")
        assertEquals(200, index.code)
        assertTrue(index.text.contains("hi"))
        assertEquals("no-cache", index.header("Cache-Control"))
        assertTrue(h.call("GET", "/?v=7").header("Cache-Control")!!.contains("immutable"))
        assertEquals(404, h.call("GET", "/css/missing.css").code)
        assertEquals(404, h.call("GET", "/secret.txt").code)
    }

    @Test fun `chunked upload resume conflict and abort`() {
        val q = "vol=a&dir=up&rel=sub%2Ffile.bin&uploadId=upload-0001&total=10&conflict=rename"
        h.postJson("/api/mkdir", """{"vol":"a","path":"up"}""")
        val first = h.call("PUT", "/api/upload?$q&offset=0", byteArrayOf(1, 2, 3, 4, 5))
        assertEquals(200, first.code)
        assertEquals(5, first.json["received"]!!.jsonPrimitive.content.toInt())
        assertFalse(first.json["done"]!!.jsonPrimitive.content.toBoolean())
        // Wrong offset: 409 and the server tells us where to resume.
        val bad = h.call("PUT", "/api/upload?$q&offset=2", byteArrayOf(9, 9, 9))
        assertEquals(409, bad.code)
        assertEquals(5, bad.json["received"]!!.jsonPrimitive.content.toInt())
        val status = h.call("GET", "/api/upload/status?vol=a&dir=up&rel=sub%2Ffile.bin&uploadId=upload-0001")
        assertEquals(5, status.json["received"]!!.jsonPrimitive.content.toInt())
        val last = h.call("PUT", "/api/upload?$q&offset=5", byteArrayOf(6, 7, 8, 9, 10))
        assertEquals(200, last.code)
        assertTrue(last.json["done"]!!.jsonPrimitive.content.toBoolean())
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10), File(h.dirA, "up/sub/file.bin").readBytes())
        assertEquals(0, File(h.dirA, "up/sub").listFiles()!!.count { it.name.contains(".fsupload-") })
        // Same name again renames.
        h.call("PUT", "/api/upload?vol=a&dir=up&rel=sub%2Ffile.bin&uploadId=upload-0002&total=1&offset=0", byteArrayOf(7))
        assertTrue(File(h.dirA, "up/sub/file (1).bin").exists())
        // Abort removes the partial.
        val q3 = "vol=a&dir=up&rel=gone.bin&uploadId=upload-0003"
        h.call("PUT", "/api/upload?$q3&total=10&offset=0", byteArrayOf(1, 2))
        assertTrue(File(h.dirA, "up").listFiles()!!.any { it.name.contains(".fsupload-") })
        assertEquals(200, h.call("DELETE", "/api/upload?$q3").code)
        assertFalse(File(h.dirA, "up").listFiles()!!.any { it.name.contains(".fsupload-") })
        assertEquals(0, h.call("GET", "/api/upload/status?$q3").json["received"]!!.jsonPrimitive.content.toInt())
        // Missing Content-Length -> 411; bad id -> 400.
        val noLength = h.raw(
            "PUT /api/upload?vol=a&dir=up&rel=n.bin&uploadId=upload-0004&total=1&offset=0 HTTP/1.1\r\n" +
                "Host: 127.0.0.1:${h.port}\r\nX-FileStation: 1\r\nConnection: close\r\n\r\n",
        )
        assertTrue(noLength.startsWith("HTTP/1.1 411"))
        assertEquals(400, h.call("PUT", "/api/upload?vol=a&dir=up&rel=n.bin&uploadId=x&total=1&offset=0", byteArrayOf(1)).code)
    }

    @Test fun `raw serving 200 206 416 304 and head`() {
        val data = ByteArray(100) { it.toByte() }
        File(h.dirA, "r.bin").writeBytes(data)
        val full = h.call("GET", "/raw/a/r.bin")
        assertEquals(200, full.code)
        assertArrayEquals(data, full.body)
        assertEquals("bytes", full.header("Accept-Ranges"))
        val etag = full.header("ETag")!!
        assertNotNull(full.header("Content-Disposition"))

        val part = h.call("GET", "/raw/a/r.bin", headers = mapOf("Range" to "bytes=10-19"))
        assertEquals(206, part.code)
        assertEquals("bytes 10-19/100", part.header("Content-Range"))
        assertArrayEquals(data.copyOfRange(10, 20), part.body)
        assertArrayEquals(data.copyOfRange(95, 100), h.call("GET", "/raw/a/r.bin", headers = mapOf("Range" to "bytes=-5")).body)

        val unsat = h.call("GET", "/raw/a/r.bin", headers = mapOf("Range" to "bytes=500-"))
        assertEquals(416, unsat.code)
        assertEquals("bytes */100", unsat.header("Content-Range"))

        assertEquals(304, h.call("GET", "/raw/a/r.bin", headers = mapOf("If-None-Match" to etag)).code)
        // If-Range with a stale tag ignores the range.
        val ifRange = h.call("GET", "/raw/a/r.bin", headers = mapOf("Range" to "bytes=0-1", "If-Range" to "\"nope\""))
        assertEquals(200, ifRange.code)
        assertEquals(100, ifRange.body.size)

        val head = h.call("HEAD", "/raw/a/r.bin")
        assertEquals(200, head.code)
        assertEquals("100", head.header("Content-Length"))
        assertEquals(0, head.body.size)
    }

    @Test fun `raw html is sandboxed and unicode names work`() {
        File(h.dirA, "p.html").writeText("<script>1</script>")
        val r = h.call("GET", "/raw/a/p.html")
        assertEquals("sandbox", r.header("Content-Security-Policy"))
        assertEquals("nosniff", r.header("X-Content-Type-Options"))
        File(h.dirA, "café ñ.txt").writeText("ok")
        val u = h.call("GET", "/raw/a/caf%C3%A9%20%C3%B1.txt?download=1")
        assertEquals(200, u.code)
        assertTrue(u.header("Content-Disposition")!!.startsWith("attachment;"))
        assertTrue(u.header("Content-Disposition")!!.contains("filename*=UTF-8''caf%C3%A9%20%C3%B1.txt"))
        assertEquals(400, h.call("GET", "/raw/a/%2e%2e/x").code)
    }

    @Test fun `text save with stale if match is refused`() {
        File(h.dirA, "t.md").writeText("one")
        val load = h.call("GET", "/api/text?vol=a&path=t.md")
        assertEquals(200, load.code)
        assertEquals("one", load.text)
        val etag = load.header("ETag")!!
        val save = h.call("PUT", "/api/text?vol=a&path=t.md", "two".toByteArray(), mapOf("If-Match" to etag))
        assertEquals(200, save.code)
        assertEquals("two", File(h.dirA, "t.md").readText())
        val newTag = save.json["etag"]!!.jsonPrimitive.content
        assertEquals(newTag, h.call("GET", "/api/text?vol=a&path=t.md").header("ETag"))
        val stale = h.call("PUT", "/api/text?vol=a&path=t.md", "three".toByteArray(), mapOf("If-Match" to etag))
        assertEquals(412, stale.code)
        assertEquals(newTag, stale.json["currentEtag"]!!.jsonPrimitive.content)
        assertEquals("two", File(h.dirA, "t.md").readText())
        assertEquals(412, h.call("PUT", "/api/text?vol=a&path=t.md", "x".toByteArray()).code)
        // New file only with create=1.
        assertEquals(404, h.call("PUT", "/api/text?vol=a&path=new.md", "n".toByteArray()).code)
        assertEquals(200, h.call("PUT", "/api/text?vol=a&path=new.md&create=1", "n".toByteArray()).code)
        assertEquals("n", File(h.dirA, "new.md").readText())
        File(h.dirA, "bin.dat").writeBytes(byteArrayOf(65, 0, 66))
        assertEquals(415, h.call("GET", "/api/text?vol=a&path=bin.dat").code)
    }

    @Test fun `attachment upload lands in the document attachments folder and follows rename`() {
        File(h.dirA, "note.md").writeText("# n")
        val png = byteArrayOf(1, 2, 3)
        val r = h.call("PUT", "/api/attachment?vol=a&doc=note.md&name=image.png", png, mapOf("Content-Type" to "image/png"))
        assertEquals(200, r.code)
        assertEquals(".attachments.note.md/image.png", r.json["path"]!!.jsonPrimitive.content)
        assertArrayEquals(png, File(h.dirA, ".attachments.note.md/image.png").readBytes())
        val second = h.call("PUT", "/api/attachment?vol=a&doc=note.md&name=image.png", png)
        assertEquals(".attachments.note.md/image (1).png", second.json["path"]!!.jsonPrimitive.content)
        assertEquals(415, h.call("PUT", "/api/attachment?vol=a&doc=note.md&name=evil.html", png).code)
        assertEquals(400, h.call("PUT", "/api/attachment?vol=a&doc=note.md&name=..%2Fx.png", png).code)

        assertEquals(200, h.postJson("/api/rename", """{"vol":"a","path":"note.md","name":"renamed.md"}""").code)
        assertTrue(File(h.dirA, ".attachments.renamed.md/image.png").exists())
        assertFalse(File(h.dirA, ".attachments.note.md").exists())
        assertEquals(200, h.call("GET", "/raw/a/.attachments.renamed.md/image.png").code)
    }

    @Test fun `delete goes to trash and can be restored or emptied`() {
        File(h.dirA, "d.txt").writeText("d")
        val del = h.postJson("/api/delete", """{"items":[{"vol":"a","path":"d.txt"}]}""")
        assertEquals(200, del.code)
        assertFalse(File(h.dirA, "d.txt").exists())
        val list = h.call("GET", "/api/trash?vol=a").json["items"]!!.jsonArray
        assertEquals(1, list.size)
        val id = list[0].jsonObject["id"]!!.jsonPrimitive.content
        assertEquals("d.txt", list[0].jsonObject["originalPath"]!!.jsonPrimitive.content)
        // The trash folder stays hidden in listings.
        assertFalse(names(h.call("GET", "/api/list?vol=a")).contains(".filestation-trash"))
        assertEquals(200, h.postJson("/api/trash/restore", """{"vol":"a","ids":["$id"]}""").code)
        assertEquals("d", File(h.dirA, "d.txt").readText())
        h.postJson("/api/delete", """{"items":[{"vol":"a","path":"d.txt"}]}""")
        assertEquals(200, h.postJson("/api/trash/empty", """{"vol":"a"}""").code)
        assertEquals(0, h.call("GET", "/api/trash?vol=a").json["items"]!!.jsonArray.size)
        File(h.dirA, "p.txt").writeText("p")
        h.postJson("/api/delete", """{"items":[{"vol":"a","path":"p.txt"}],"permanent":true}""")
        assertFalse(File(h.dirA, "p.txt").exists())
        assertEquals(0, h.call("GET", "/api/trash?vol=a").json["items"]!!.jsonArray.size)
    }

    @Test fun `search stat usage and newfile`() {
        File(h.dirA, "docs").mkdirs()
        File(h.dirA, "docs/Report.TXT").writeText("1234")
        File(h.dirB, "report2.md").writeText("12")
        val s = h.call("GET", "/api/search?q=report")
        assertEquals(2, s.json["results"]!!.jsonArray.size)
        assertEquals(1, h.call("GET", "/api/search?vol=a&q=REPORT&limit=1").json["results"]!!.jsonArray.size)
        assertEquals(4, h.call("GET", "/api/stat?vol=a&path=docs%2FReport.TXT").json["size"]!!.jsonPrimitive.content.toInt())
        val u = h.call("GET", "/api/usage?vol=a&path=").json
        assertEquals(1, u["files"]!!.jsonPrimitive.content.toInt())
        assertEquals(4, u["bytes"]!!.jsonPrimitive.content.toInt())
        val nf = h.postJson("/api/newfile", """{"vol":"a","dir":"docs","name":"n.md","kind":"md","content":"# hi"}""")
        assertEquals(200, nf.code)
        assertEquals("# hi", File(h.dirA, "docs/n.md").readText())
        assertEquals(409, h.postJson("/api/newfile", """{"vol":"a","dir":"docs","name":"n.md","kind":"md"}""").code)
        assertEquals(400, h.postJson("/api/newfile", """{"vol":"a","dir":"docs","name":"../x","kind":"md"}""").code)
    }

    @Test fun `session endpoint and unknown routes`() {
        val s = h.call("GET", "/api/session").json
        assertFalse(s["authRequired"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("7", s["version"]!!.jsonPrimitive.content)
        assertEquals(404, h.call("GET", "/api/nope").code)
        assertEquals(405, h.call("POST", "/api/list", ByteArray(0)).code)
        assertEquals(400, h.postJson("/api/mkdir", "not json").code)
    }
}
