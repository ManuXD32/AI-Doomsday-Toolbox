package com.example.llamadroid.service

/** Small, strict unified-diff applier used where a shell `patch` binary is unavailable. */
object AgentLocalPatchSupport {
    data class FileInput(val path: String, val content: String?)
    data class FileOutput(val path: String, val content: String?, val delete: Boolean)

    private data class Hunk(
        val oldStart: Int,
        val oldCount: Int,
        val newStart: Int,
        val newCount: Int,
        val lines: List<String>
    )

    private val hunkHeader = Regex("""^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@.*$""")

    fun paths(patch: String): List<Pair<String?, String?>> {
        val lines = patch.lines()
        val result = mutableListOf<Pair<String?, String?>>()
        var index = 0
        while (index < lines.size) {
            if (lines[index].startsWith("--- ") && index + 1 < lines.size && lines[index + 1].startsWith("+++ ")) {
                result += normalizePath(lines[index].removePrefix("--- ")) to
                    normalizePath(lines[index + 1].removePrefix("+++ "))
                index += 2
            } else index += 1
        }
        return result
    }

    fun apply(patch: String, read: (String) -> String?): List<FileOutput> {
        require(patch.isNotBlank()) { "Patch content is empty." }
        val lines = patch.lines()
        val outputs = mutableListOf<FileOutput>()
        var index = 0
        while (index < lines.size) {
            if (!lines[index].startsWith("--- ")) {
                index += 1
                continue
            }
            require(index + 1 < lines.size && lines[index + 1].startsWith("+++ ")) {
                "Patch file header is missing a +++ line."
            }
            val oldPath = normalizePath(lines[index].removePrefix("--- "))
            val newPath = normalizePath(lines[index + 1].removePrefix("+++ "))
            val targetPath = newPath ?: oldPath ?: error("Patch file header has no path.")
            validatePath(targetPath)
            index += 2
            val hunks = mutableListOf<Hunk>()
            while (index < lines.size && !lines[index].startsWith("--- ")) {
                val match = hunkHeader.matchEntire(lines[index])
                if (match == null) {
                    if (lines[index].isBlank()) {
                        index += 1
                        continue
                    }
                    throw IllegalArgumentException("Patch contains text outside a unified-diff hunk.")
                }
                val oldStart = match.groupValues[1].toInt()
                val oldCount = match.groupValues[2].takeIf(String::isNotBlank)?.toInt() ?: 1
                val newStart = match.groupValues[3].toInt()
                val newCount = match.groupValues[4].takeIf(String::isNotBlank)?.toInt() ?: 1
                index += 1
                val body = mutableListOf<String>()
                while (index < lines.size && !lines[index].startsWith("@@ ") && !lines[index].startsWith("--- ")) {
                    val line = lines[index]
                    if (line.startsWith("\\ No newline at end of file")) {
                        index += 1
                        continue
                    }
                    require(line.isNotEmpty() && line[0] in charArrayOf(' ', '+', '-')) {
                        "Unsupported unified-diff line near `${line.take(80)}`."
                    }
                    body += line
                    index += 1
                }
                require(body.count { it.first() != '+' } == oldCount) { "Patch old-line count does not match its hunk header." }
                require(body.count { it.first() != '-' } == newCount) { "Patch new-line count does not match its hunk header." }
                hunks += Hunk(oldStart, oldCount, newStart, newCount, body)
            }
            require(hunks.isNotEmpty()) { "Patch for `$targetPath` contains no hunks." }
            if (newPath == null) {
                require(read(oldPath!!) != null) { "File not found: $oldPath" }
                applyHunks(read(oldPath).orEmpty(), hunks) // Validate deletion patch context.
                outputs += FileOutput(targetPath, null, delete = true)
            } else {
                val source = if (oldPath == null) "" else read(oldPath)
                    ?: throw IllegalArgumentException("File not found: $oldPath")
                outputs += FileOutput(targetPath, applyHunks(source, hunks), delete = false)
            }
        }
        require(outputs.isNotEmpty()) { "Patch must include unified diff file headers." }
        return outputs
    }

    private fun applyHunks(source: String, hunks: List<Hunk>): String {
        val hadTrailingNewline = source.endsWith('\n')
        val original = if (source.isEmpty()) emptyList() else source.removeSuffix("\n").split('\n')
        val output = mutableListOf<String>()
        var sourceIndex = 0
        hunks.forEach { hunk ->
            val targetIndex = (hunk.oldStart - 1).coerceAtLeast(0)
            require(targetIndex >= sourceIndex && targetIndex <= original.size) { "Patch hunk starts outside the file." }
            output += original.subList(sourceIndex, targetIndex)
            sourceIndex = targetIndex
            hunk.lines.forEach { line ->
                val text = line.drop(1)
                when (line.first()) {
                    ' ' -> {
                        require(original.getOrNull(sourceIndex) == text) { "Patch context does not match the current file." }
                        output += text
                        sourceIndex += 1
                    }
                    '-' -> {
                        require(original.getOrNull(sourceIndex) == text) { "Patch removal does not match the current file." }
                        sourceIndex += 1
                    }
                    '+' -> output += text
                }
            }
        }
        output += original.drop(sourceIndex)
        val joined = output.joinToString("\n")
        return if ((hadTrailingNewline || source.isEmpty()) && output.isNotEmpty()) "$joined\n" else joined
    }

    private fun normalizePath(raw: String): String? {
        val token = raw.trim().substringBefore('\t').substringBefore(' ')
        if (token == "/dev/null") return null
        return if (token.startsWith("a/") || token.startsWith("b/")) token.drop(2) else token
    }

    private fun validatePath(path: String) {
        require(path.isNotBlank() && !path.startsWith('/') && path.split('/').none { it == ".." }) {
            "Patch path must stay inside the workspace: $path"
        }
    }
}
