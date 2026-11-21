package com.example.llamadroid.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.harness.HarnessAppWebSearch
import com.example.llamadroid.service.search.SearxngBackend
import com.example.llamadroid.service.search.SearxngLocalInstance
import com.example.llamadroid.service.search.SearxngReport
import com.example.llamadroid.service.search.WebSearchBackendKind
import com.example.llamadroid.service.search.WebSearchBackendSettings
import com.example.llamadroid.service.search.WebSearchBackends
import com.example.llamadroid.service.search.normalizeSearxngBaseUrl
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.coroutines.cancellation.CancellationException

/** Global web-search backend selector (DuckDuckGo or SearXNG) with a "Test search" action. */
@Composable
fun WebSearchSettingsCard() {
    val context = LocalContext.current
    val resources = androidx.compose.ui.platform.LocalResources.current
    val settings = remember { WebSearchBackendSettings(context) }
    val scope = rememberCoroutineScope()
    var backend by remember { mutableStateOf(settings.backend) }
    var url by remember { mutableStateOf(settings.searxngUrl) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    val urlInvalid = url.isNotBlank() && normalizeSearxngBaseUrl(url) == null

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.web_search_backend_title),
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                stringResource(R.string.web_search_backend_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            listOf(
                WebSearchBackendKind.DUCKDUCKGO to R.string.web_search_backend_duckduckgo,
                WebSearchBackendKind.SEARXNG to R.string.web_search_backend_searxng
            ).forEach { (kind, label) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(selected = backend == kind, role = Role.RadioButton) {
                            backend = kind
                            settings.backend = kind
                            testResult = null
                        },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = backend == kind, onClick = null)
                    Text(
                        stringResource(label),
                        modifier = Modifier.padding(start = 12.dp),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (backend == WebSearchBackendKind.SEARXNG) {
                OutlinedTextField(
                    value = url,
                    onValueChange = {
                        url = it
                        settings.searxngUrl = it
                        testResult = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.web_search_backend_url_label)) },
                    placeholder = { Text(stringResource(R.string.web_search_backend_url_hint)) },
                    singleLine = true,
                    isError = urlInvalid,
                    supportingText = {
                        Text(
                            stringResource(
                                if (urlInvalid) R.string.web_search_backend_url_invalid
                                else R.string.web_search_backend_url_help
                            )
                        )
                    }
                )
            }
            Button(
                onClick = {
                    testing = true
                    testResult = null
                    scope.launch {
                        testResult = runCatching {
                            if (backend == WebSearchBackendKind.SEARXNG) {
                                val base = WebSearchBackends.searxngBaseUrl(context)
                                    ?: error(resources.getString(R.string.web_search_backend_url_invalid))
                                val local = WebSearchBackends.isLocalInstance(context)
                                val report = try {
                                    SearxngBackend(base, local).searchWithReport(TEST_QUERY, 3)
                                } catch (e: Exception) {
                                    if (e is CancellationException) throw e
                                    if (local) {
                                        SearxngLocalInstance.appendLogLine(
                                            "${testLogTime()} test failed: ${e.message ?: e::class.java.simpleName}"
                                        )
                                    }
                                    throw e
                                }
                                if (local) SearxngLocalInstance.appendLogLine(searxngTestLogLine(report))
                                searxngTestSummary(resources, report)
                            } else {
                                val results = HarnessAppWebSearch().search(TEST_QUERY, 3)
                                if (results.isEmpty()) resources.getString(R.string.web_search_backend_test_empty)
                                else resources.getString(R.string.web_search_backend_test_ok, results.size)
                            }
                        }.getOrElse { error ->
                            if (error is CancellationException) throw error
                            resources.getString(
                                R.string.web_search_backend_test_failed,
                                error.message ?: error::class.java.simpleName
                            )
                        }
                        testing = false
                    }
                },
                enabled = !testing && !urlInvalid
            ) {
                Text(
                    stringResource(
                        if (testing) R.string.web_search_backend_testing else R.string.web_search_backend_test
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            testResult?.let {
                // Wraps (no maxLines) so the failing-engine list stays readable at 360 dp; the card itself scrolls.
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SearxngLocalSettingsBlock()
        }
    }
}

private const val TEST_QUERY = "open source"

private fun testLogTime(): String = SimpleDateFormat("HH:mm:ss'Z'", Locale.US)
    .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())

/** One diagnostic log line for a Test: UTC time, elapsed ms, HTTP code, result count, failing engines (never the query). */
internal fun searxngTestLogLine(report: SearxngReport): String {
    val engines = if (report.unresponsiveEngines.isEmpty()) "none"
    else groupSearxngFailures(report.unresponsiveEngines).joinToString(", ") { (name, reasons) -> "$name($reasons)" }
    return "${testLogTime()} test http=${report.httpCode} elapsed=${report.elapsedMs}ms results=${report.results.size} failing=$engines"
}

/** One entry per engine with its distinct reasons joined ("access denied, timeout"), in first-seen order. */
internal fun groupSearxngFailures(pairs: List<Pair<String, String>>): List<Pair<String, String>> {
    val byEngine = LinkedHashMap<String, MutableList<String>>()
    pairs.forEach { (name, reason) ->
        val list = byEngine.getOrPut(name) { mutableListOf() }
        val r = reason.ifBlank { "?" }
        if (r !in list) list += r
    }
    return byEngine.map { (name, reasons) -> name to reasons.joinToString(", ") }
}

private fun searxngTestSummary(resources: android.content.res.Resources, report: SearxngReport): String {
    val head = if (report.results.isEmpty()) resources.getString(R.string.web_search_backend_test_empty)
    else resources.getString(R.string.web_search_backend_test_ok, report.results.size)
    val time = resources.getString(R.string.web_search_backend_test_elapsed, report.elapsedMs)
    if (report.unresponsiveEngines.isEmpty()) return "$head\n$time"
    val failing = groupSearxngFailures(report.unresponsiveEngines).joinToString("\n") { (name, reasons) ->
        resources.getString(R.string.web_search_backend_test_engine_line, name, reasons)
    }
    return "$head\n$time\n${resources.getString(R.string.web_search_backend_test_failing)}\n$failing"
}
