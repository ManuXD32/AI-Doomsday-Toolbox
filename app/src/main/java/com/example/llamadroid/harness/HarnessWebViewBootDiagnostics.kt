package com.example.llamadroid.harness

import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale

internal const val HARNESS_WEBUI_BOOT_FAILED_OVERLAY = "HARNESS_WEBUI_BOOT_FAILED_OVERLAY"
internal const val HARNESS_WEBUI_BOOT_TIMEOUT = "HARNESS_WEBUI_BOOT_TIMEOUT"
internal const val HARNESS_WEBUI_BOOT_ASSET_HTTP_FAILED = "HARNESS_WEBUI_BOOT_ASSET_HTTP_FAILED"
internal const val HARNESS_WEBUI_BOOT_ASSET_AUTH_FAILED = "HARNESS_WEBUI_BOOT_ASSET_AUTH_FAILED"
internal const val HARNESS_WEBUI_BOOT_ASSET_MIME_INVALID = "HARNESS_WEBUI_BOOT_ASSET_MIME_INVALID"
internal const val HARNESS_WEBUI_BOOT_IMPORT_FAILED = "HARNESS_WEBUI_BOOT_IMPORT_FAILED"
internal const val HARNESS_WEBUI_NAVIGATION_TIMEOUT = "HARNESS_WEBUI_NAVIGATION_TIMEOUT"
internal const val HARNESS_WEBVIEW_COOKIE_UNAVAILABLE = "HARNESS_WEBVIEW_COOKIE_UNAVAILABLE"
internal const val HARNESS_WEBVIEW_STATIC_INTERCEPT_FAILED = "HARNESS_WEBVIEW_STATIC_INTERCEPT_FAILED"
internal const val HARNESS_WEBUI_SCRIPT_ERROR = "HARNESS_WEBUI_SCRIPT_ERROR"

/**
 * The host injects this promise before the frontend module starts. Some vendor WebView builds
 * lag the Chromium feature set even when Android itself is current, so keep the injected boot
 * contract valid without changing the signed payload. The replacement is intentionally limited
 * to the exact expression used by the host webserver and does not inspect or retain page text.
 */
internal fun patchHarnessWebViewIndexForAndroid(html: String): String {
    val marker = "Promise.withResolvers()"
    if (marker !in html) return HarnessWebGatewayBootstrap.patch(html)
    val fallback = """
        (Promise.withResolvers ? Promise.withResolvers() : (() => {
          let resolve;
          let reject;
          const promise = new Promise((res, rej) => { resolve = res; reject = rej; });
          return { promise, resolve, reject };
        })())
    """.trimIndent()
    return HarnessWebGatewayBootstrap.patch(html.replace(marker, fallback))
}

/** Only static boot resources may be fetched through the authenticated Android bridge. */
internal fun isHarnessWebViewStaticPath(path: String): Boolean {
    val normalized = path.substringBefore('?').substringBefore('#')
    return normalized == "/" || normalized == "/index.html" ||
        normalized == "/manifest.webmanifest" || normalized == "/favicon.svg" ||
        normalized.startsWith("/assets/") || normalized.startsWith("/plugins/")
}

/** Metadata-only result from the pinned WebUI boot globals and DOM lifecycle. */
internal data class HarnessWebViewBootDiagnostic(
    val outcome: String,
    val code: String? = null,
    val httpStatus: Int? = null,
    val graphEntries: Int? = null,
    val graphBatches: Int? = null,
    val loaderMode: String? = null,
    val pendingRegistrations: Int? = null,
    val pluginScripts: Int? = null,
    val mountedUi: Boolean? = null,
    val bootOverlay: Boolean? = null,
    val failedOverlay: Boolean? = null,
    val bootSpinner: Boolean? = null,
    val essentialAssets: Int? = null,
    val stylesheetAssets: Int? = null,
    val moduleImportFailures: Int? = null,
    val resourceKind: String? = null,
    val mimeType: String? = null,
    /** Android WebView's bounded transport classification; never retain its description/URL. */
    val webResourceErrorCode: Int? = null,
    /** Native static-resource fetch class; only a fixed allowlist may cross the QA boundary. */
    val nativeStaticErrorKind: String? = null,
    /** Elapsed native fetch time, capped before it reaches diagnostics or test output. */
    val nativeStaticElapsedMs: Int? = null,
)

/** Metadata returned when the Android-side static resource fetch cannot produce a response. */
internal data class HarnessWebViewNativeStaticFailure(
    val errorKind: String,
    val elapsedMs: Int,
    val httpStatus: Int? = null,
)

private val HARNESS_NATIVE_STATIC_ERROR_KINDS = setOf(
    "timeout", "connect", "dns", "io", "body_too_large", "unsupported", "unknown",
)

/** Keep native transport details to reviewed classes; never expose exception text or URLs. */
internal fun classifyHarnessNativeStaticError(error: Throwable?): String {
    val causes = generateSequence(error) { it.cause }.take(8).toList()
    return when {
        causes.any { it is SocketTimeoutException } -> "timeout"
        causes.any { it is UnknownHostException } -> "dns"
        causes.any { it is ConnectException } -> "connect"
        causes.any { it is IOException } -> "io"
        else -> "unknown"
    }
}

internal fun boundedHarnessNativeStaticErrorKind(kind: String?): String? =
    kind?.lowercase(Locale.ROOT)?.takeIf { it in HARNESS_NATIVE_STATIC_ERROR_KINDS }

internal fun boundedHarnessNativeStaticElapsedMs(elapsedMs: Long?): Int? =
    elapsedMs?.coerceIn(0L, 60_000L)?.toInt()

/** WebView error constants are negative and small; reject any unexpected/unbounded value. */
internal fun boundedHarnessWebResourceErrorCode(errorCode: Int?): Int? =
    errorCode?.takeIf { it in -100..0 }

/** Journal values are non-negative, so persist only the magnitude of the reviewed code. */
internal fun harnessWebResourceErrorMagnitude(errorCode: Int?): Int? =
    boundedHarnessWebResourceErrorCode(errorCode)?.let { -it }

internal data class HarnessWebViewBootSnapshot(
    val graphEntries: Int?,
    val graphBatches: Int?,
    val loaderMode: String?,
    val pendingRegistrations: Int?,
    val pluginScripts: Int?,
    val mountedUi: Boolean?,
    val bootOverlay: Boolean?,
    val failedOverlay: Boolean?,
    val bootSpinner: Boolean?,
    val essentialAssets: Int? = null,
    val stylesheetAssets: Int? = null,
    val moduleImportFailures: Int? = null,
)

/**
 * The pinned frontend removes its `[data-dsh-boot]` overlay only after the module graph has
 * activated and `uiRenderer.mount()` has completed. The probe reads only fixed booleans/counts;
 * it never serializes graph rows, package names, script URLs, browser text, or error messages.
 */
internal fun buildHarnessWebViewBootProbeScript(): String = """
    (function () {
      try {
        var root = document.getElementById("root");
        var overlay = root ? root.querySelector("[data-dsh-boot]") : null;
        var failedOverlay = overlay ? overlay.querySelector("[class*=\"_failed_\"]") !== null : null;
        var spinner = overlay ? overlay.querySelector("[data-dsh-boot-spinner]") !== null : null;
        var graph = window.__DSH_BOOT__;
        var entries = graph && Array.isArray(graph.entries) ? graph.entries.length : null;
        var batches = graph && Array.isArray(graph.batches) ? graph.batches.length : null;
        var loader = window.__ModuleLoader__;
        var mode = loader && typeof loader.mode === "string" ? loader.mode : null;
        var pending = loader && Array.isArray(loader.pendingQueue) ? loader.pendingQueue.length : null;
        var resources = (typeof performance !== "undefined" && typeof performance.getEntriesByType === "function")
          ? performance.getEntriesByType("resource") : [];
        var essentialAssets = Array.prototype.filter.call(resources, function (entry) {
          return typeof entry.name === "string" && (entry.name.indexOf("/assets/") >= 0 || entry.name.indexOf("/plugins/") >= 0);
        }).length;
        var stylesheets = document.querySelectorAll("link[rel=stylesheet][href]").length;
        var bootErrors = graph && (graph.errors || graph.failures || graph.importErrors);
        var moduleImportFailures = Array.isArray(bootErrors) ? bootErrors.length :
          (Number.isSafeInteger(bootErrors) ? bootErrors : 0);
        var scripts = Array.prototype.filter.call(document.scripts, function (node) {
          return typeof node.src === "string" && node.src.indexOf("/plugins/") >= 0;
        }).length;
        return JSON.stringify({ graphEntries: entries, graphBatches: batches, loaderMode: mode,
          pendingRegistrations: pending, pluginScripts: scripts,
          mountedUi: root ? (overlay === null && root.childElementCount > 0) : null,
          bootOverlay: root ? overlay !== null : null, essentialAssets: essentialAssets,
          stylesheetAssets: stylesheets, moduleImportFailures: moduleImportFailures,
          failedOverlay: failedOverlay, bootSpinner: spinner });
      } catch (_) {
        return JSON.stringify({ graphEntries: null, graphBatches: null, loaderMode: null,
          pendingRegistrations: null, pluginScripts: null, mountedUi: null,
          bootOverlay: null, essentialAssets: null, stylesheetAssets: null,
          moduleImportFailures: null, failedOverlay: null, bootSpinner: null });
      }
    })()
""".trimIndent()

/** WebView wraps a returned JSON string in a second JSON string; accept both forms. */
internal fun parseHarnessWebViewBootProbe(raw: String): HarnessWebViewBootSnapshot? = runCatching {
    val value = JSONTokener(raw).nextValue()
    val objectValue = when (value) {
        is String -> JSONTokener(value).nextValue() as? JSONObject
        is JSONObject -> value
        else -> null
    } ?: return@runCatching null

    fun boundedCount(name: String): Int? = objectValue.optInt(name, -1).takeIf { it in 0..100_000 }
    fun boundedBoolean(name: String): Boolean? = objectValue.opt(name) as? Boolean
    val mode = objectValue.optString("loaderMode", "").takeIf { it in setOf("queue", "live") }
    HarnessWebViewBootSnapshot(
        graphEntries = boundedCount("graphEntries"),
        graphBatches = boundedCount("graphBatches"),
        loaderMode = mode,
        pendingRegistrations = boundedCount("pendingRegistrations"),
        pluginScripts = boundedCount("pluginScripts"),
        mountedUi = boundedBoolean("mountedUi"),
        bootOverlay = boundedBoolean("bootOverlay"),
        failedOverlay = boundedBoolean("failedOverlay"),
        bootSpinner = boundedBoolean("bootSpinner"),
        essentialAssets = boundedCount("essentialAssets"),
        stylesheetAssets = boundedCount("stylesheetAssets"),
        moduleImportFailures = boundedCount("moduleImportFailures"),
    )
}.getOrNull()

internal fun HarnessWebViewBootSnapshot.toDiagnostic(
    deadlineReached: Boolean = false,
): HarnessWebViewBootDiagnostic {
    val common = {
        HarnessWebViewBootDiagnostic(
            outcome = "pending",
            code = "HARNESS_WEBUI_BOOT_PENDING",
            graphEntries = graphEntries,
            graphBatches = graphBatches,
            loaderMode = loaderMode,
            pendingRegistrations = pendingRegistrations,
            pluginScripts = pluginScripts,
            mountedUi = mountedUi,
            bootOverlay = bootOverlay,
            failedOverlay = failedOverlay,
            bootSpinner = bootSpinner,
            essentialAssets = essentialAssets,
            stylesheetAssets = stylesheetAssets,
            moduleImportFailures = moduleImportFailures,
        )
    }
    return when {
        moduleImportFailures != null && moduleImportFailures > 0 -> HarnessWebViewBootDiagnostic(
            outcome = "failure",
            code = HARNESS_WEBUI_BOOT_IMPORT_FAILED,
            graphEntries = graphEntries,
            graphBatches = graphBatches,
            loaderMode = loaderMode,
            pendingRegistrations = pendingRegistrations,
            pluginScripts = pluginScripts,
            mountedUi = mountedUi,
            bootOverlay = bootOverlay,
            failedOverlay = failedOverlay,
            bootSpinner = bootSpinner,
            essentialAssets = essentialAssets,
            stylesheetAssets = stylesheetAssets,
            moduleImportFailures = moduleImportFailures,
        )
        failedOverlay == true -> HarnessWebViewBootDiagnostic(
            outcome = "failure",
            code = HARNESS_WEBUI_BOOT_FAILED_OVERLAY,
            graphEntries = graphEntries,
            graphBatches = graphBatches,
            loaderMode = loaderMode,
            pendingRegistrations = pendingRegistrations,
            pluginScripts = pluginScripts,
            mountedUi = mountedUi,
            bootOverlay = bootOverlay,
            failedOverlay = failedOverlay,
            bootSpinner = bootSpinner,
            essentialAssets = essentialAssets,
            stylesheetAssets = stylesheetAssets,
            moduleImportFailures = moduleImportFailures,
        )
        mountedUi == true && bootOverlay == false -> HarnessWebViewBootDiagnostic(
            outcome = "ready",
            graphEntries = graphEntries,
            graphBatches = graphBatches,
            loaderMode = loaderMode,
            pendingRegistrations = pendingRegistrations,
            pluginScripts = pluginScripts,
            mountedUi = mountedUi,
            bootOverlay = bootOverlay,
            failedOverlay = failedOverlay,
            bootSpinner = bootSpinner,
            essentialAssets = essentialAssets,
            stylesheetAssets = stylesheetAssets,
            moduleImportFailures = moduleImportFailures,
        )
        deadlineReached -> HarnessWebViewBootDiagnostic(
            outcome = "failure",
            code = when {
                graphEntries == null -> "HARNESS_WEBUI_BOOT_GRAPH_MISSING"
                graphEntries == 0 -> "HARNESS_WEBUI_BOOT_GRAPH_EMPTY"
                else -> HARNESS_WEBUI_BOOT_TIMEOUT
            },
            graphEntries = graphEntries,
            graphBatches = graphBatches,
            loaderMode = loaderMode,
            pendingRegistrations = pendingRegistrations,
            pluginScripts = pluginScripts,
            mountedUi = mountedUi,
            bootOverlay = bootOverlay,
            failedOverlay = failedOverlay,
            bootSpinner = bootSpinner,
            essentialAssets = essentialAssets,
            stylesheetAssets = stylesheetAssets,
            moduleImportFailures = moduleImportFailures,
        )
        else -> common()
    }
}
