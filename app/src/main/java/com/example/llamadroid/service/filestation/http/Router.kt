package com.example.llamadroid.service.filestation.http

import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response

fun interface RouteHandler { fun handle(req: ApiRequest): Response }

/**
 * One route. [prefix] routes match any URI starting with [path]. [public] routes skip the session
 * check (the Host and CSRF checks still apply). HEAD requests match GET routes.
 */
class Route(
    val method: Method,
    val path: String,
    val handler: RouteHandler,
    val prefix: Boolean = false,
    val public: Boolean = false,
)

/** Hook for other workers: pass modules to the server constructor to add routes without editing the router. */
fun interface RouteModule { fun routes(): List<Route> }

class Router(modules: List<RouteModule>) {
    private val exact = HashMap<String, Route>()
    private val prefixes = ArrayList<Route>()

    init {
        modules.flatMap { it.routes() }.forEach { r ->
            if (r.prefix) prefixes.add(r) else exact["${r.method.name} ${r.path}"] = r
        }
        prefixes.sortByDescending { it.path.length }
    }

    fun find(method: Method, uri: String): Route? {
        val m = if (method == Method.HEAD) Method.GET else method
        exact["${m.name} $uri"]?.let { return it }
        return prefixes.firstOrNull { it.method == m && uri.startsWith(it.path) }
    }

    /** True when some route exists for [uri] under another method (405 instead of 404). */
    fun knownPath(uri: String): Boolean =
        exact.values.any { it.path == uri } || prefixes.any { uri.startsWith(it.path) }
}

