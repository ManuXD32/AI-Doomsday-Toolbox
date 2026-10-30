package com.example.llamadroid.service

import java.io.File

/** Android kernels need not expose task/children; only accept an exact direct child argv. */
internal fun findNativeChildPid(
    binaryPath: String,
    procRoot: File,
    selfPid: Int,
    expectedPort: Int? = null
): Int? {
    val children = File(procRoot, "$selfPid/task").listFiles().orEmpty()
        .flatMap { task ->
            runCatching { File(task, "children").readText() }.getOrDefault("")
                .trim().split(Regex("\\s+")).mapNotNull(String::toIntOrNull)
        }.toSet()
    val candidates = (children + procRoot.listFiles().orEmpty().mapNotNull { it.name.toIntOrNull() })
    return candidates.filter { pid ->
        if (pid <= 0 || pid == selfPid) return@filter false
        val directory = File(procRoot, pid.toString())
        val parent = runCatching {
            File(directory, "stat").readText().substringAfterLast(") ")
                .split(Regex("\\s+")).getOrNull(1)?.toIntOrNull()
        }.getOrNull()
        if (parent != selfPid && (parent != null || pid !in children)) return@filter false
        val argv = runCatching { File(directory, "cmdline").readBytes().toString(Charsets.UTF_8)
            .split('\u0000').filter(String::isNotEmpty) }.getOrDefault(emptyList())
        // A filename appearing in a model path or an unrelated shell command is not ownership.
        argv.firstOrNull() == binaryPath && (expectedPort == null ||
            argv.withIndex().any { (index, arg) ->
                (arg in setOf("--port", "-p") && argv.getOrNull(index + 1) == expectedPort.toString()) ||
                    arg == "--port=$expectedPort" || arg == "-p=$expectedPort" || arg == "-p$expectedPort"
            })
    }.singleOrNull()
}
