package com.example.llamadroid.service.filestation.http

import com.example.llamadroid.service.filestation.api.EditorApi
import com.example.llamadroid.service.filestation.api.FsReadApi
import com.example.llamadroid.service.filestation.api.FsWriteApi
import com.example.llamadroid.service.filestation.api.UploadApi
import com.example.llamadroid.service.filestation.volume.FsException
import com.example.llamadroid.service.filestation.volume.TrashStore
import com.example.llamadroid.service.filestation.volume.VolumeRegistry
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import java.io.File
import java.net.URI

/**
 * File Station HTTP server. Pure NanoHTTPD, no Android imports. Other workers add routes through
 * [extraRoutes] (thumbs, zip, tools). Call [startServer] (not start) so the bounded runner, temp
 * dir and socket timeout are configured.
 */
class FileStationHttpServer(
    hostname: String?,
    port: Int,
    private val deps: FileStationDeps,
    verifier: PasswordVerifier,
    extraRoutes: List<RouteModule> = emptyList(),
    registry: VolumeRegistry = deps.volumes as? VolumeRegistry
        ?: throw IllegalArgumentException("deps.volumes must be a VolumeRegistry"),
    trash: TrashStore = TrashStore(registry, deps.clock),
) : NanoHTTPD(hostname, port) {

    val gate = AuthGate(deps.auth, verifier, deps.clock)
    private val statics = StaticAssets(deps.assets)
    private val tmpDir = File(deps.cacheDir, "file_station_tmp")
    private val router = Router(
        listOf(
            SessionRoutes(deps, gate),
            JobRoutes(deps),
            FsReadApi(deps),
            FsWriteApi(deps, registry, trash),
            UploadApi(deps),
            EditorApi(deps),
        ) + extraRoutes,
    )

    fun startServer() {
        wipeTmp()
        tmpDir.mkdirs()
        setTempFileManagerFactory(DirTempFileManagerFactory(tmpDir))
        setAsyncRunner(BoundedAsyncRunner())
        start(SOCKET_TIMEOUT_MS, true)
    }

    override fun stop() {
        super.stop()
        wipeTmp()
    }

    private fun wipeTmp() {
        tmpDir.listFiles()?.forEach { runCatching { it.deleteRecursively() } }
    }

    override fun useGzipWhenAccepted(r: Response): Boolean = HttpResponses.isGzipAllowed(r)

    override fun serve(session: IHTTPSession): Response {
        val req = ApiRequest(session, deps)
        val response = try {
            handle(req)
        } catch (e: HttpError) {
            HttpResponses.fromHttpError(e)
        } catch (e: FsException) {
            HttpResponses.fromFs(e)
        } catch (e: Throwable) {
            deps.logger.log("E", TAG, "${req.method} ${req.uri} failed: ${e.javaClass.simpleName}")
            HttpResponses.error(HttpStatus.INTERNAL, "IO", "internal error")
        }
        return finish(req, response)
    }

    private fun handle(req: ApiRequest): Response {
        if (!gate.hostAllowed(req.header("host"))) {
            throw HttpError(HttpStatus.FORBIDDEN, "FORBIDDEN", "host not allowed without a password")
        }
        val route = router.find(req.method, req.uri)
        val isRead = req.method == Method.GET || req.method == Method.HEAD
        if (route == null) {
            if (isRead && !req.uri.startsWith("/api/") && !req.uri.startsWith("/raw/") &&
                !req.uri.startsWith("/thumb/")
            ) {
                return statics.serve(req)
            }
            if (router.knownPath(req.uri)) {
                throw HttpError(HttpStatus.METHOD_NOT_ALLOWED, "BAD_REQUEST", "method not allowed")
            }
            throw HttpError(HttpStatus.NOT_FOUND, "NOT_FOUND", "no such endpoint")
        }
        if (!isRead) checkCsrf(req)
        if (!route.public && !gate.isAuthenticated(req.header("cookie"))) {
            throw HttpError(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "sign in required")
        }
        return route.handler.handle(req)
    }

    private fun checkCsrf(req: ApiRequest) {
        if (req.header("x-filestation") != "1") {
            throw HttpError(HttpStatus.FORBIDDEN, "FORBIDDEN", "missing X-FileStation header")
        }
        val host = req.header("host")?.trim()?.lowercase().orEmpty()
        for (name in listOf("origin", "referer")) {
            val value = req.header(name) ?: continue
            val authority = try {
                URI(value.trim()).authority?.lowercase()
            } catch (e: Exception) {
                null
            }
            if (authority == null || authority != host) {
                throw HttpError(HttpStatus.FORBIDDEN, "FORBIDDEN", "cross-origin request refused")
            }
        }
    }

    private fun finish(req: ApiRequest, r: Response): Response {
        if (r.getHeader("Content-Security-Policy") == null) r.addHeader("Content-Security-Policy", CSP)
        r.addHeader("X-Content-Type-Options", "nosniff")
        r.addHeader("Referrer-Policy", "no-referrer")
        if (req.uri.startsWith("/api/") && r.getHeader("Cache-Control") == null) {
            r.addHeader("Cache-Control", "no-store")
        }
        val bodyLen = req.contentLength ?: 0L
        val ok = r.status.requestStatus in 200..299
        if (bodyLen > 0 && (!ok || !req.bodyTouched)) r.closeConnection(true)
        return r
    }

    companion object {
        const val SOCKET_TIMEOUT_MS = 30_000
        private const val TAG = "FileStation"
        const val CSP = "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' blob: data:; " +
            "media-src 'self' blob:; object-src 'self'; frame-ancestors 'self'"
    }
}
