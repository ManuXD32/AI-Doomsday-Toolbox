package com.example.llamadroid.service.filestation.http

import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put

/** `/api/session`, `/api/login`, `/api/logout`. */
class SessionRoutes(private val deps: FileStationDeps, private val gate: AuthGate) : RouteModule {
    override fun routes() = listOf(
        Route(Method.GET, "/api/session", { session(it) }, public = true),
        Route(Method.POST, "/api/login", { login(it) }, public = true),
        Route(Method.POST, "/api/logout", { logout(it) }, public = true),
    )

    private fun session(req: ApiRequest): Response = HttpResponses.json(buildJsonObject {
        put("authRequired", gate.authRequired())
        put("authenticated", gate.isAuthenticated(req.header("cookie")))
        put("lang", JsonNull)
        put("version", deps.appVersionCode)
        put("tools", buildJsonObject {
            put("image", deps.tools.image != null)
            put("pdf", deps.tools.pdf != null)
            put("doc", deps.tools.doc != null)
            put("media", deps.tools.media?.available == true)
            put("qr", deps.tools.qr != null)
        })
    })

    private fun login(req: ApiRequest): Response {
        val password = req.jsonBody().string("password").orEmpty()
        return when (val result = gate.login(req.remoteIp, password)) {
            is LoginResult.Ok -> HttpResponses.ok().also {
                it.addHeader("Set-Cookie", AuthGate.cookieHeader(result.cookieValue))
            }
            LoginResult.NotRequired -> HttpResponses.ok()
            LoginResult.BadPassword ->
                throw HttpError(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "wrong password")
            is LoginResult.Locked -> throw HttpError(
                HttpStatus.TOO_MANY, "TOO_MANY_REQUESTS", "too many attempts",
                headers = mapOf("Retry-After" to result.retryAfterSeconds.toString()),
            )
        }
    }

    private fun logout(req: ApiRequest): Response {
        gate.logout(req.header("cookie"))
        return HttpResponses.ok().also { it.addHeader("Set-Cookie", AuthGate.clearedCookieHeader()) }
    }
}

/** `/api/jobs` endpoints over the injected [JobRunner]. */
class JobRoutes(private val deps: FileStationDeps) : RouteModule {
    override fun routes() = listOf(
        Route(Method.GET, "/api/jobs", { list() }),
        Route(Method.GET, "/api/jobs/", { get(it) }, prefix = true),
        Route(Method.POST, "/api/jobs/", { cancel(it) }, prefix = true),
    )

    private fun list(): Response = HttpResponses.json(buildJsonObject {
        put("jobs", buildJsonArray { deps.jobs.list().sortedByDescending { it.startedAt }.forEach { add(jobJson(it)) } })
    })

    private fun get(req: ApiRequest): Response {
        val id = req.uri.removePrefix("/api/jobs/")
        val job = deps.jobs.get(id) ?: throw HttpError(HttpStatus.NOT_FOUND, "NOT_FOUND", "unknown job")
        return HttpResponses.json(jobJson(job))
    }

    private fun cancel(req: ApiRequest): Response {
        val rest = req.uri.removePrefix("/api/jobs/")
        if (!rest.endsWith("/cancel")) throw HttpError(HttpStatus.NOT_FOUND, "NOT_FOUND", "no such endpoint")
        val ok = deps.jobs.cancel(rest.removeSuffix("/cancel"))
        return HttpResponses.json(buildJsonObject { put("ok", ok) })
    }

    companion object {
        fun jobJson(j: JobInfo): JsonObject = buildJsonObject {
            put("id", j.id)
            put("kind", j.kind)
            put("title", j.title)
            put("state", j.state.name.lowercase())
            put("progress", j.progress)
            put("message", j.message)
            put("outputs", buildJsonArray {
                j.outputs.forEach { add(buildJsonObject { put("vol", it.vol); put("path", it.path) }) }
            })
            put("errorCode", j.errorCode)
            put("startedAt", j.startedAt)
            put("finishedAt", j.finishedAt)
            j.resultJson?.let { raw ->
                runCatching { Json.parseToJsonElement(raw) }.getOrNull()?.let { put("result", it) }
            }
        }
    }
}
