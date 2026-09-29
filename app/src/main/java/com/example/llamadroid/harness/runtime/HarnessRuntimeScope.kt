package com.example.llamadroid.harness.runtime

import android.annotation.SuppressLint
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.example.llamadroid.data.proot.AgentProotEnvironmentPaths
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Captured ownership, never a lookup of the currently selected screen. */
object HarnessRuntimeScope {
    const val LEGACY_RUNTIME_ID = "deepseek-harness-shared"
    const val INSTALLATIONS_DIRECTORY = "agent_harness/installations"
    const val CONTROL_PREFERENCES = "harness_installations_control"
    val transferablePreferences = listOf(
        "harness_local_model_capabilities", "harness_workspace_title_sync", "harness_agent_settings"
    )
    val ownedPreferences = transferablePreferences + listOf(
        "harness_credentials", "harness_ssh_cleanup", "harness_inference_owners", "harness_lan_access"
    )

    private fun captured(context: Context): ScopedContext? = when (context) {
        is ScopedContext -> context
        is ContextWrapper -> if (context.baseContext !== context) captured(context.baseContext) else null
        else -> null
    }

    fun id(context: Context): String = captured(context)?.runtimeId ?: LEGACY_RUNTIME_ID

    fun host(context: Context): Context = captured(context)?.baseContext ?: context.applicationContext

    fun context(context: Context, runtimeId: String): Context {
        AgentProotEnvironmentPaths.requireSafeEnvironmentId(runtimeId)
        if (context is ScopedContext && context.runtimeId == runtimeId) return context
        return ScopedContext(host(context), runtimeId)
    }

    fun isCaptured(context: Context): Boolean = captured(context) != null

    fun dataRoot(context: Context): File = dataFile(context, "agent_harness")

    fun dataFile(context: Context, relative: String): File {
        require(relative == "agent_harness" || relative.startsWith("agent_harness/"))
        require(relative.split('/').none { it.isEmpty() || it == "." || it == ".." })
        val base = context.filesDir.canonicalFile
        val path = if (id(context) == LEGACY_RUNTIME_ID) relative else
            "$INSTALLATIONS_DIRECTORY/${id(context)}" + relative.removePrefix("agent_harness")
        val target = File(base, path).absoluteFile
        var component = base
        path.split('/').forEach { segment ->
            component = File(component, segment)
            require(!java.nio.file.Files.isSymbolicLink(component.toPath())) { "HARNESS_RUNTIME_PATH_INVALID" }
        }
        require(target.canonicalFile.toPath().startsWith(base.toPath())) { "HARNESS_RUNTIME_PATH_INVALID" }
        return target
    }

    fun projects(context: Context): File = if (id(context) == LEGACY_RUNTIME_ID)
        File(context.filesDir, "agent_local_workspaces").also {
            require(!java.nio.file.Files.isSymbolicLink(it.toPath())) { "HARNESS_RUNTIME_PATH_INVALID" }
        }.canonicalFile
    else dataFile(context, "agent_harness/projects").canonicalFile

    fun preferenceName(runtimeId: String, name: String): String =
        if (runtimeId == LEGACY_RUNTIME_ID) name else "harness_runtime_${runtimeId}_$name"

    fun preferences(context: Context, name: String): SharedPreferences = host(context)
        .getSharedPreferences(preferenceName(id(context), name), Context.MODE_PRIVATE)

    private class ScopedContext(base: Context, val runtimeId: String) : ContextWrapper(base) {
        private val settings by lazy {
            RuntimeAgentPreferences(
                baseContext.getSharedPreferences("llamadroid_settings", MODE_PRIVATE),
                baseContext.getSharedPreferences(preferenceName(runtimeId, "harness_agent_settings"), MODE_PRIVATE)
            )
        }
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = when {
            name == CONTROL_PREFERENCES -> baseContext.getSharedPreferences(name, mode)
            name == "llamadroid_settings" && runtimeId != LEGACY_RUNTIME_ID -> settings
            name.startsWith("harness_") -> baseContext.getSharedPreferences(preferenceName(runtimeId, name), mode)
            else -> baseContext.getSharedPreferences(name, mode)
        }
    }
}

/** Only agent settings are isolated; appearance, the model library and server settings stay shared. */
private class RuntimeAgentPreferences(
    private val shared: SharedPreferences,
    private val agent: SharedPreferences,
) : SharedPreferences {
    private fun owner(key: String?): SharedPreferences = if (key?.startsWith("agent_") == true) agent else shared
    private val listeners = ConcurrentHashMap<SharedPreferences.OnSharedPreferenceChangeListener, SharedPreferences.OnSharedPreferenceChangeListener>()
    override fun getAll(): MutableMap<String, *> = (shared.all.filterKeys { !it.startsWith("agent_") } + agent.all).toMutableMap()
    override fun getString(key: String?, defValue: String?): String? = owner(key).getString(key, defValue)
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = owner(key).getStringSet(key, defValues)
    override fun getInt(key: String?, defValue: Int): Int = owner(key).getInt(key, defValue)
    override fun getLong(key: String?, defValue: Long): Long = owner(key).getLong(key, defValue)
    override fun getFloat(key: String?, defValue: Float): Float = owner(key).getFloat(key, defValue)
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = owner(key).getBoolean(key, defValue)
    override fun contains(key: String?): Boolean = owner(key).contains(key)
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        val adapter = SharedPreferences.OnSharedPreferenceChangeListener { source, key ->
            if (source === owner(key)) listener.onSharedPreferenceChanged(this, key)
        }
        listeners.put(listener, adapter)?.let { shared.unregisterOnSharedPreferenceChangeListener(it); agent.unregisterOnSharedPreferenceChangeListener(it) }
        shared.registerOnSharedPreferenceChangeListener(adapter)
        agent.registerOnSharedPreferenceChangeListener(adapter)
    }
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        listeners.remove(listener)?.let { shared.unregisterOnSharedPreferenceChangeListener(it); agent.unregisterOnSharedPreferenceChangeListener(it) }
    }
    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        // These delegates are finalized together by commit()/apply() below;
        // the lint detector cannot follow the split editor protocol.
        @SuppressLint("CommitPrefEdits")
        private val common = shared.edit()
        @SuppressLint("CommitPrefEdits")
        private val local = agent.edit()
        private fun editFor(key: String?): SharedPreferences.Editor = if (owner(key) === agent) local else common
        override fun putString(key: String?, value: String?) = apply { editFor(key).putString(key, value) }
        override fun putStringSet(key: String?, values: MutableSet<String>?) = apply { editFor(key).putStringSet(key, values) }
        override fun putInt(key: String?, value: Int) = apply { editFor(key).putInt(key, value) }
        override fun putLong(key: String?, value: Long) = apply { editFor(key).putLong(key, value) }
        override fun putFloat(key: String?, value: Float) = apply { editFor(key).putFloat(key, value) }
        override fun putBoolean(key: String?, value: Boolean) = apply { editFor(key).putBoolean(key, value) }
        override fun remove(key: String?) = apply { editFor(key).remove(key) }
        override fun clear() = apply { local.clear() }
        override fun commit(): Boolean { val first = local.commit(); return common.commit() && first }
        override fun apply() { local.apply(); common.apply() }
    }
}
