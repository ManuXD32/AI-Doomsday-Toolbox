package com.example.llamadroid.service.delegation

import android.content.Context
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.harness.HarnessAppRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One project (harness workspace) as shown to a model. */
data class CatalogProject(
    val id: String,
    val title: String,
    val lastActivityAt: Long,
    val threadCount: Int,
    val usedByChat: Boolean
)

/** One thread (harness session) of a project as shown to a model. */
data class CatalogThread(
    val id: String,
    val title: String,
    val lastActivityAt: Long,
    /** 1-based thread number inside the current task, or null when the task has not attached it yet. */
    val taskNumber: Int? = null
)

/**
 * Lists the projects (workspaces) and threads of a chat's DeepSeek runtime for the main LLM's
 * `deepseek_projects` tool and the subagent's `harness_list_projects` / `harness_list_threads`.
 * Reads the runtime's Room rows, so it works while the runtime is stopped. Formatting is pure.
 */
object DelegationProjectCatalog {
    const val MAX_CHARS = 1_500
    private const val MAX_TITLE_CHARS = 40

    // ---- pure formatting -----------------------------------------------------------------------

    fun relativeTime(then: Long, now: Long): String {
        val delta = now - then
        return when {
            then <= 0L -> "unknown"
            delta < 60_000L -> "just now"
            delta < 3_600_000L -> "${delta / 60_000L}m ago"
            delta < 86_400_000L -> "${delta / 3_600_000L}h ago"
            delta < 14 * 86_400_000L -> "${delta / 86_400_000L}d ago"
            else -> SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date(then))
        }
    }

    private fun cleanTitle(raw: String): String {
        val one = raw.replace(Regex("\\s+"), " ").trim().ifEmpty { "(untitled)" }
        return if (one.length > MAX_TITLE_CHARS) one.take(MAX_TITLE_CHARS - 1).trimEnd() + "…" else one
    }

    private fun plural(n: Int, word: String) = if (n == 1) "$n $word" else "$n ${word}s"

    /** Case-insensitive title filter, newest activity first. */
    fun filterProjects(projects: List<CatalogProject>, query: String?): List<CatalogProject> {
        val q = query?.trim()?.lowercase(Locale.ROOT).orEmpty()
        return projects.filter { q.isEmpty() || it.title.lowercase(Locale.ROOT).contains(q) }
            .sortedByDescending { it.lastActivityAt }
    }

    /** Bounded to [maxChars]; lines that do not fit are replaced by `+N more`. */
    fun formatProjects(projects: List<CatalogProject>, query: String?, now: Long, maxChars: Int = MAX_CHARS): String {
        val shown = filterProjects(projects, query)
        val q = query?.trim().orEmpty()
        if (shown.isEmpty()) {
            return if (q.isEmpty()) "No projects in this DeepSeek runtime yet. deepseek_task_start creates one."
            else "No projects match \"${q.take(60)}\"."
        }
        val lines = shown.map { p ->
            buildString {
                append("- ").append(p.id).append(" | ").append(cleanTitle(p.title)).append(" | ")
                    .append(relativeTime(p.lastActivityAt, now)).append(" | ").append(plural(p.threadCount, "thread"))
                if (p.usedByChat) append(" | used by this chat")
            }
        }
        return bounded("Projects (${shown.size}, newest first):", lines, maxChars)
    }

    fun formatThreads(threads: List<CatalogThread>, now: Long, maxChars: Int = MAX_CHARS): String {
        if (threads.isEmpty()) return "This project has no threads yet."
        val lines = threads.sortedByDescending { it.lastActivityAt }.map { t ->
            buildString {
                append("- ").append(t.id).append(" | ").append(cleanTitle(t.title)).append(" | ")
                    .append(relativeTime(t.lastActivityAt, now))
                t.taskNumber?.let { append(" | thread ").append(it).append(" of this task") }
            }
        }
        return bounded("Threads (${threads.size}, newest first):", lines, maxChars)
    }

    private fun bounded(header: String, lines: List<String>, maxChars: Int): String {
        val out = StringBuilder(header)
        var used = 0
        for ((index, line) in lines.withIndex()) {
            // Keep room for the exact "+N more" line that would follow if the next line does not fit.
            val reserve = if (index == lines.lastIndex) 0 else moreLine(lines.size - index - 1).length
            if (out.length + 1 + line.length + reserve > maxChars) break
            out.append('\n').append(line)
            used++
        }
        if (used < lines.size) out.append(moreLine(lines.size - used))
        return out.toString()
    }

    private fun moreLine(count: Int): String = "\n+$count more (narrow with a query)"

    // ---- Room-backed -----------------------------------------------------------------------------

    private suspend fun runtimeIdOf(context: Context, chatId: Long): String? {
        val chat = AppDatabase.getDatabase(context.applicationContext).llamaChatDao().getChatById(chatId) ?: return null
        return NativeChatDelegationConfig.fromParams(parseDelegationParams(chat.apiParams)).runtimeId
    }

    private suspend fun loadProjects(context: Context, chatId: Long, runtimeId: String): List<CatalogProject> {
        val app = context.applicationContext
        val dao = HarnessAppRuntime.forRuntime(app, runtimeId).dao
        val sessions = dao.observeSessions().first().filterNot { it.archived }
        val used = AppDatabase.getDatabase(app).nativeChatDelegateTaskDao().observeForChat(chatId).first()
            .mapNotNull { it.workspaceId }.toSet()
        return dao.workspaces().map { ws ->
            val own = sessions.filter { it.workspaceId == ws.id }
            CatalogProject(ws.id, ws.title, maxOf(ws.updatedAt, own.maxOfOrNull { it.updatedAt } ?: 0L), own.size, ws.id in used)
        }
    }

    /** Model-facing project list (at most [MAX_CHARS]) of the chat's runtime. */
    suspend fun listText(context: Context, chatId: Long, query: String?): String {
        val runtimeId = runtimeIdOf(context, chatId)
            ?: return "No DeepSeek runtime is configured for this chat, so there are no projects to list."
        return try {
            formatProjects(loadProjects(context, chatId, runtimeId), query, System.currentTimeMillis())
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            "The project list of the DeepSeek runtime could not be read. The runtime may not be installed yet."
        }
    }

    /** Threads of project [workspaceId] in [runtimeId]; [attached] maps thread ids already in the task to their number. */
    suspend fun listThreadsText(
        context: Context,
        runtimeId: String,
        workspaceId: String,
        attached: Map<String, Int> = emptyMap()
    ): String = try {
        val dao = HarnessAppRuntime.forRuntime(context.applicationContext, runtimeId).dao
        val titles = dao.conversations().associate { it.id to it.title }
        val threads = dao.observeSessions().first().filter { it.workspaceId == workspaceId && !it.archived }.map {
            CatalogThread(it.harnessSessionId, titles[it.conversationId].orEmpty(), it.updatedAt, attached[it.harnessSessionId])
        }
        formatThreads(threads, System.currentTimeMillis())
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        "The thread list could not be read."
    }

    /** Project list for the subagent, same format as [listText] but addressed by runtime. */
    suspend fun listProjectsText(context: Context, chatId: Long, runtimeId: String, query: String?): String = try {
        formatProjects(loadProjects(context, chatId, runtimeId), query, System.currentTimeMillis())
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        "The project list could not be read."
    }

    /** True when [workspaceId] is a project of [runtimeId]. */
    suspend fun projectExists(context: Context, runtimeId: String, workspaceId: String): Boolean = try {
        HarnessAppRuntime.forRuntime(context.applicationContext, runtimeId).dao.workspace(workspaceId) != null
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }

    /** True when [sessionId] is a non-archived thread of [workspaceId]. */
    suspend fun threadInProject(context: Context, runtimeId: String, workspaceId: String, sessionId: String): Boolean = try {
        HarnessAppRuntime.forRuntime(context.applicationContext, runtimeId).dao.session(sessionId)
            ?.let { it.workspaceId == workspaceId && !it.archived } ?: false
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }

    /**
     * Validates a `deepseek_task_start` project/thread choice against the chat's runtime. Returns a
     * model-facing error, or null when the choice is usable.
     */
    suspend fun validateStart(
        context: Context,
        chatId: Long,
        projectId: String?,
        continueProject: Boolean,
        threadId: String?
    ): String? {
        if (projectId == null && threadId == null) return null
        val runtimeId = runtimeIdOf(context, chatId) ?: return "No DeepSeek runtime is configured for this chat."
        if (projectId != null && !projectExists(context, runtimeId, projectId)) {
            return "Unknown project id '$projectId'. Call deepseek_projects to list the valid ids, or use project=new."
        }
        if (threadId != null) {
            val workspace = projectId ?: if (continueProject) {
                AppDatabase.getDatabase(context.applicationContext).nativeChatDelegateTaskDao()
                    .getLatestWithWorkspace(chatId)?.workspaceId
            } else null
            workspace ?: return "thread needs project=<project id> (see deepseek_projects) or project=continue."
            if (!threadInProject(context, runtimeId, workspace, threadId)) {
                return "Unknown thread id '$threadId' in that project. Use thread=new; the subagent can list and open threads itself."
            }
        }
        return null
    }
}
