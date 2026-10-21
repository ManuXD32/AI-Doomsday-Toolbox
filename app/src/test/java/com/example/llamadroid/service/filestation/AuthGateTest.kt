package com.example.llamadroid.service.filestation

import com.example.llamadroid.service.filestation.http.AuthGate
import com.example.llamadroid.service.filestation.http.Clock
import com.example.llamadroid.service.filestation.http.LoginResult
import com.example.llamadroid.service.filestation.http.PasswordCredentials
import com.example.llamadroid.service.filestation.http.PasswordVerifier
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthGateTest {
    private var harness: TestServerHarness? = null

    @After fun tearDown() { harness?.close() }

    private fun protectedServer(): TestServerHarness =
        TestServerHarness(FakeAuth(PasswordCredentials("salt", "secret"))).also { harness = it }

    private fun cookieOf(r: Resp): String = r.header("Set-Cookie")!!.substringBefore(';')

    @Test fun `open mode needs no cookie`() {
        val h = TestServerHarness().also { harness = it }
        assertEquals(200, h.call("GET", "/api/volumes").code)
        assertFalse(h.call("GET", "/api/session").json["authRequired"].toString().toBoolean())
    }

    @Test fun `password mode returns 401 without a cookie and static stays public`() {
        val h = protectedServer()
        val r = h.call("GET", "/api/volumes")
        assertEquals(401, r.code)
        assertTrue(r.text.contains("UNAUTHORIZED"))
        assertEquals(200, h.call("GET", "/").code)
        assertEquals(200, h.call("GET", "/api/session").code)
        assertEquals(401, h.call("GET", "/raw/a/x").code)
        // Any Host is accepted when a password protects the server.
        val host = h.raw("GET /api/session HTTP/1.1\r\nHost: files.example.org\r\nConnection: close\r\n\r\n")
        assertTrue(host.startsWith("HTTP/1.1 200"))
    }

    @Test fun `login sets a hardened cookie that unlocks the api and logout clears it`() {
        val h = protectedServer()
        assertEquals(401, h.postJson("/api/login", """{"password":"wrong"}""").code)
        val ok = h.postJson("/api/login", """{"password":"secret"}""")
        assertEquals(200, ok.code)
        val setCookie = ok.header("Set-Cookie")!!
        assertTrue(setCookie.startsWith("ADT_FS_SESSION="))
        assertTrue(setCookie.contains("HttpOnly"))
        assertTrue(setCookie.contains("SameSite=Strict"))
        assertTrue(setCookie.contains("Path=/"))
        val cookie = cookieOf(ok)
        assertEquals(200, h.call("GET", "/api/volumes", cookie = cookie).code)
        assertTrue(h.call("GET", "/api/session", cookie = cookie).json["authenticated"].toString().toBoolean())
        // CSRF still applies with a valid session.
        assertEquals(403, h.call("POST", "/api/mkdir", "{}".toByteArray(), csrf = false, cookie = cookie).code)
        val out = h.call("POST", "/api/logout", ByteArray(0), cookie = cookie)
        assertEquals(200, out.code)
        assertTrue(out.header("Set-Cookie")!!.contains("Max-Age=0"))
        assertEquals(401, h.call("GET", "/api/volumes", cookie = cookie).code)
    }

    @Test fun `forged signature and tampered token are rejected`() {
        val h = protectedServer()
        val cookie = cookieOf(h.postJson("/api/login", """{"password":"secret"}"""))
        val value = cookie.substringAfter('=')
        val token = value.substringBefore('.')
        val sig = value.substringAfter('.')
        assertEquals(401, h.call("GET", "/api/volumes", cookie = "ADT_FS_SESSION=$token.${sig.reversed()}").code)
        assertEquals(401, h.call("GET", "/api/volumes", cookie = "ADT_FS_SESSION=$token").code)
        assertEquals(401, h.call("GET", "/api/volumes", cookie = "ADT_FS_SESSION=forged.forged").code)
        assertEquals(200, h.call("GET", "/api/volumes", cookie = "other=1; $cookie").code)
    }

    @Test fun `rotating the secret signs everyone out`() {
        val h = protectedServer()
        val cookie = cookieOf(h.postJson("/api/login", """{"password":"secret"}"""))
        assertEquals(200, h.call("GET", "/api/volumes", cookie = cookie).code)
        h.auth.secret = ByteArray(32) { (it + 99).toByte() }
        assertEquals(401, h.call("GET", "/api/volumes", cookie = cookie).code)
    }

    @Test fun `five failures lock the ip with retry after`() {
        val h = protectedServer()
        val codes = (1..5).map { h.postJson("/api/login", """{"password":"bad$it"}""") }
        assertEquals(listOf(401, 401, 401, 401, 429), codes.map { it.code })
        assertEquals("30", codes.last().header("Retry-After"))
        val locked = h.postJson("/api/login", """{"password":"secret"}""")
        assertEquals(429, locked.code)
        assertNotNull(locked.header("Retry-After"))
    }

    // ---- AuthGate unit tests with a fake clock ----

    private class TimeSource(var now: Long = 1_000_000L) : Clock {
        override fun nowMillis() = now
    }

    private fun gate(time: TimeSource, auth: FakeAuth = FakeAuth(PasswordCredentials("s", "pw"))) =
        AuthGate(auth, PasswordVerifier { p, c -> p == c.hash }, time) to auth

    @Test fun `lockout doubles up to fifteen minutes and clears on success`() {
        val time = TimeSource()
        val (g, _) = gate(time)
        fun failFive(): LoginResult {
            var last: LoginResult = LoginResult.BadPassword
            repeat(5) { last = g.login("1.1.1.1", "no") }
            return last
        }
        assertEquals(30L, (failFive() as LoginResult.Locked).retryAfterSeconds)
        assertTrue(g.login("1.1.1.1", "pw") is LoginResult.Locked)
        time.now += 31_000
        assertEquals(60L, (failFive() as LoginResult.Locked).retryAfterSeconds)
        var expected = 60L
        repeat(6) {
            time.now += expected * 1000 + 1000
            expected = minOf(expected * 2, 900L)
            assertEquals(expected, (failFive() as LoginResult.Locked).retryAfterSeconds)
        }
        assertEquals(900L, expected)
        // Other addresses are unaffected; the right password after the lock expires works.
        assertTrue(g.login("2.2.2.2", "pw") is LoginResult.Ok)
        time.now += 901_000
        assertTrue(g.login("1.1.1.1", "pw") is LoginResult.Ok)
    }

    @Test fun `global limit is thirty attempts per minute`() {
        val time = TimeSource()
        val (g, _) = gate(time)
        repeat(30) { i -> assertFalse(g.login("10.0.0.$i", "no") is LoginResult.Locked) }
        val blocked = g.login("10.0.1.1", "pw")
        assertTrue(blocked is LoginResult.Locked)
        time.now += 61_000
        assertTrue(g.login("10.0.1.1", "pw") is LoginResult.Ok)
    }

    @Test fun `sessions slide for twelve hours and are capped at sixty four`() {
        val time = TimeSource()
        val (g, _) = gate(time)
        val first = (g.login("a", "pw") as LoginResult.Ok).cookieValue
        val cookie = "ADT_FS_SESSION=$first"
        time.now += 11 * 3600_000L
        assertTrue(g.isAuthenticated(cookie))
        time.now += 11 * 3600_000L
        assertTrue(g.isAuthenticated(cookie))
        time.now += 13 * 3600_000L
        assertFalse(g.isAuthenticated(cookie))

        repeat(70) {
            time.now += 3_000
            assertTrue(g.login("b", "pw") is LoginResult.Ok)
        }
        assertTrue(g.sessionCount() <= AuthGate.MAX_SESSIONS)
    }

    @Test fun `open mode host rules`() {
        val (g, auth) = gate(TimeSource(), FakeAuth(null))
        assertTrue(g.hostAllowed("192.168.1.20:9111"))
        assertTrue(g.hostAllowed("localhost:9111"))
        assertTrue(g.hostAllowed("[::1]:9111"))
        assertTrue(g.hostAllowed("10.0.0.1"))
        assertFalse(g.hostAllowed("evil.example:9111"))
        assertFalse(g.hostAllowed("999.1.1.1"))
        assertFalse(g.hostAllowed(null))
        auth.creds = PasswordCredentials("s", "pw")
        assertTrue(g.hostAllowed("files.example.org"))
    }
}
