package com.example.llamadroid.service.filestation.http

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Password check injected by the app (wrapping AiServerAuth.verifyPassword); fakes in tests. */
fun interface PasswordVerifier {
    fun verify(password: String, credentials: PasswordCredentials): Boolean
}

sealed class LoginResult {
    /** [cookieValue] is the signed token to put in the `ADT_FS_SESSION` cookie. */
    class Ok(val cookieValue: String) : LoginResult()
    object BadPassword : LoginResult()
    class Locked(val retryAfterSeconds: Long) : LoginResult()
    /** No password configured; login is meaningless. */
    object NotRequired : LoginResult()
}

/**
 * Open mode vs password mode, signed cookie sessions (12 h sliding, max 64) and login throttling.
 * The signing secret is re-read from [AuthConfigProvider] on every call, so rotating it logs
 * everyone out.
 */
class AuthGate(
    private val config: AuthConfigProvider,
    private val verifier: PasswordVerifier,
    private val clock: Clock = Clock { System.currentTimeMillis() },
) {
    private val random = SecureRandom()
    private val sessions = LinkedHashMap<String, Long>() // token hash -> expiry millis
    private val ipState = HashMap<String, IpState>()
    private val globalAttempts = ArrayDeque<Long>()

    private class IpState(var fails: Int = 0, var lockedUntil: Long = 0, var lastLockSec: Long = 0, var touched: Long = 0)

    fun authRequired(): Boolean = config.credentials() != null

    /** Open mode accepts only IP-literal or localhost Host headers (DNS rebinding guard). */
    fun hostAllowed(hostHeader: String?): Boolean {
        if (authRequired()) return true
        val host = hostHeader?.trim().orEmpty()
        if (host.isEmpty()) return false
        val name = if (host.startsWith("[")) {
            host.substringAfter('[').substringBefore(']')
        } else if (host.count { it == ':' } == 1) {
            host.substringBefore(':')
        } else host
        if (name.equals("localhost", ignoreCase = true)) return true
        if (name.contains(':')) return name.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }
        val parts = name.split('.')
        return parts.size == 4 && parts.all { p -> p.isNotEmpty() && p.length <= 3 && p.all { it.isDigit() } && p.toInt() <= 255 }
    }

    /** True when the cookie is a valid, unexpired session (or no password is set). Slides the expiry. */
    fun isAuthenticated(cookieHeader: String?): Boolean {
        if (!authRequired()) return true
        val token = tokenFromCookie(cookieHeader) ?: return false
        val key = hash(token)
        val now = clock.nowMillis()
        synchronized(sessions) {
            val expiry = sessions[key] ?: return false
            if (expiry <= now) {
                sessions.remove(key)
                return false
            }
            sessions[key] = now + SESSION_MS
            return true
        }
    }

    fun login(ip: String, password: String): LoginResult {
        val creds = config.credentials() ?: return LoginResult.NotRequired
        val now = clock.nowMillis()
        synchronized(ipState) {
            while (globalAttempts.isNotEmpty() && now - globalAttempts.first() > 60_000) globalAttempts.removeFirst()
            val state = ipState.getOrPut(ip) { IpState() }
            state.touched = now
            if (state.lockedUntil > now) return LoginResult.Locked(secondsUntil(state.lockedUntil, now))
            if (globalAttempts.size >= GLOBAL_PER_MINUTE) {
                return LoginResult.Locked(secondsUntil(globalAttempts.first() + 60_000, now))
            }
            globalAttempts.addLast(now)
            pruneIps(now)
        }
        val ok = verifier.verify(password, creds)
        synchronized(ipState) {
            val state = ipState.getOrPut(ip) { IpState() }
            if (ok) {
                ipState.remove(ip)
            } else {
                state.fails++
                if (state.fails >= MAX_FAILS) {
                    val next = if (state.lastLockSec == 0L) 30L else minOf(state.lastLockSec * 2, 900L)
                    state.lastLockSec = next
                    state.lockedUntil = now + next * 1000
                    state.fails = 0
                    return LoginResult.Locked(next)
                }
            }
            Unit
        }
        if (!ok) return LoginResult.BadPassword
        return LoginResult.Ok(createSession(now))
    }

    fun logout(cookieHeader: String?) {
        val token = tokenFromCookie(cookieHeader) ?: return
        synchronized(sessions) { sessions.remove(hash(token)) }
    }

    /** Drops every session (e.g. "sign out everyone"; rotating the secret has the same effect). */
    fun clearSessions() = synchronized(sessions) { sessions.clear() }

    fun sessionCount(): Int = synchronized(sessions) { sessions.size }

    private fun createSession(now: Long): String {
        val raw = ByteArray(32).also { random.nextBytes(it) }
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
        synchronized(sessions) {
            sessions.entries.removeAll { it.value <= now }
            while (sessions.size >= MAX_SESSIONS) {
                val oldest = sessions.entries.minByOrNull { it.value } ?: break
                sessions.remove(oldest.key)
            }
            sessions[hash(token)] = now + SESSION_MS
        }
        return "$token.${sign(token)}"
    }

    private fun tokenFromCookie(cookieHeader: String?): String? {
        val value = cookieHeader?.split(';')
            ?.map { it.trim() }
            ?.firstOrNull { it.startsWith("$COOKIE_NAME=") }
            ?.substringAfter('=')
            ?: return null
        val token = value.substringBefore('.', "")
        val sig = value.substringAfter('.', "")
        if (token.isEmpty() || sig.isEmpty()) return null
        val expected = sign(token)
        return token.takeIf { MessageDigest.isEqual(sig.toByteArray(), expected.toByteArray()) }
    }

    private fun sign(token: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(config.sessionSecret(), "HmacSHA256"))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(token.toByteArray()))
    }

    private fun hash(token: String): String =
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(token.toByteArray()))

    private fun secondsUntil(at: Long, now: Long): Long = maxOf(1L, (at - now + 999) / 1000)

    private fun pruneIps(now: Long) {
        if (ipState.size < 1024) return
        ipState.entries.removeAll { it.value.lockedUntil <= now && now - it.value.touched > 3_600_000 }
    }

    companion object {
        const val COOKIE_NAME = "ADT_FS_SESSION"
        const val SESSION_MS = 12L * 3600 * 1000
        const val MAX_SESSIONS = 64
        const val MAX_FAILS = 5
        const val GLOBAL_PER_MINUTE = 30

        fun cookieHeader(value: String): String =
            "$COOKIE_NAME=$value; Path=/; HttpOnly; SameSite=Strict; Max-Age=${SESSION_MS / 1000}"

        fun clearedCookieHeader(): String =
            "$COOKIE_NAME=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0"
    }
}
