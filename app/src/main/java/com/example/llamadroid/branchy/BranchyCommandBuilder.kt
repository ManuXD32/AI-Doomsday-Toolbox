package com.example.llamadroid.branchy

/** One `--input path[:fmt[:colmap]]` argument of `tokenizer-train`. */
data class TokenizerInput(val path: String, val format: String? = null, val columnsArg: String? = null) {
    fun toArg(): String = when {
        !columnsArg.isNullOrBlank() -> "$path:${format?.takeIf { it.isNotBlank() } ?: "auto"}:$columnsArg"
        !format.isNullOrBlank() -> "$path:$format"
        else -> path
    }
}

/** Pure argv builders for the native `branchy` binary (spec "Native tree CLI"). */
object BranchyCommandBuilder {
    fun bench(exe: String, threads: Int): List<String> =
        listOf(exe, "bench", "--threads", threads.toString())

    /** `bench --sweep`: measures every candidate thread count, [seconds] each. */
    fun benchSweep(exe: String, seconds: Double): List<String> =
        listOf(exe, "bench", "--sweep", "--seconds", seconds.toString())

    /** `cluster-bench`: sweeps every helper of [configFile]; [apply] makes each helper adopt its best thread count. */
    fun clusterBench(exe: String, configFile: String, tokenFile: String, apply: Boolean = true): List<String> = buildList {
        add(exe); add("cluster-bench")
        add("--config"); add(configFile)
        add("--token-file"); add(tokenFile)
        if (apply) add("--apply")
    }

    fun plan(exe: String, inFile: String): List<String> =
        listOf(exe, "plan", "--in", inFile)

    fun tokenizerTrain(
        exe: String,
        out: String,
        vocab: Int,
        inputs: List<TokenizerInput>,
        maxBytes: Long? = null
    ): List<String> = buildList {
        add(exe); add("tokenizer-train")
        add("--out"); add(out)
        add("--vocab"); add(vocab.toString())
        inputs.forEach { add("--input"); add(it.toArg()) }
        if (maxBytes != null && maxBytes > 0L) { add("--max-bytes"); add(maxBytes.toString()) }
    }

    fun prepare(
        exe: String,
        tokenizer: String,
        input: String,
        format: String,
        columnsArg: String,
        out: String,
        maxRows: Long? = null
    ): List<String> = buildList {
        add(exe); add("prepare")
        add("--tokenizer"); add(tokenizer)
        add("--input"); add(input)
        add("--format"); add(format.ifBlank { "auto" })
        if (columnsArg.isNotBlank()) { add("--columns"); add(columnsArg) }
        add("--out"); add(out)
        if (maxRows != null && maxRows > 0L) { add("--max-rows"); add(maxRows.toString()) }
    }

    /** [controlFile]: single-device control JSON polled by the trainer (`{"checkpoint":true}` = save now). */
    fun train(
        exe: String, runDir: String, resume: Boolean, initFrom: String?, threads: Int, controlFile: String? = null
    ): List<String> = buildList {
        add(exe); add("train")
        add("--run"); add(runDir)
        if (resume) add("--resume")
        if (!initFrom.isNullOrBlank()) { add("--init-from"); add(initFrom) }
        if (threads > 0) { add("--threads"); add(threads.toString()) }
        if (!controlFile.isNullOrBlank()) { add("--control-file"); add(controlFile) }
    }

    /**
     * `train --cluster`: the coordinator of a multi-device run. The token travels as a 0600 file path, never as text
     * in argv.
     */
    fun trainCluster(
        exe: String,
        runDir: String,
        clusterJson: String,
        tokenFile: String,
        resume: Boolean,
        initFrom: String?,
        threads: Int
    ): List<String> = buildList {
        add(exe); add("train")
        add("--run"); add(runDir)
        add("--cluster"); add(clusterJson)
        if (resume) add("--resume")
        if (!initFrom.isNullOrBlank()) { add("--init-from"); add(initFrom) }
        if (threads > 0) { add("--threads"); add(threads.toString()) }
        add("--token-file"); add(tokenFile)
    }

    /**
     * `consolidate`: makes the latest complete checkpoint of [runDir] fully local, fetching units that the OLD
     * cluster ([clusterJson]) left on helpers. The token travels as a 0600 file path, never as text in argv.
     */
    fun consolidate(exe: String, runDir: String, clusterJson: String, tokenFile: String): List<String> =
        listOf(exe, "consolidate", "--run", runDir, "--cluster", clusterJson, "--token-file", tokenFile)

    /**
     * `export-gguf`. With [sliceBranches] it writes a helper slice (only those branches, `*.slice.gguf`) instead of
     * the full model; [tokenizer] is then optional.
     */
    fun exportGguf(
        exe: String,
        ckpt: String,
        tokenizer: String?,
        out: String,
        type: String = "auto",
        sliceBranches: List<Int>? = null
    ): List<String> = buildList {
        add(exe); add("export-gguf")
        add("--ckpt"); add(ckpt)
        if (!tokenizer.isNullOrBlank()) { add("--tokenizer"); add(tokenizer) }
        add("--out"); add(out)
        add("--type"); add(type)
        if (!sliceBranches.isNullOrEmpty()) { add("--slice-branches"); add(sliceBranches.joinToString(",")) }
    }

    /**
     * `push`: sends [file] to a helper's `branchy worker` (chunked, resumable by name and sha). The token travels as
     * a 0600 file path. A push replaces the worker's current session, so never push to a helper that is training.
     */
    fun push(exe: String, to: String, tokenFile: String, file: String, name: String): List<String> =
        listOf(exe, "push", "--to", to, "--token-file", tokenFile, "--file", file, "--name", name)

    fun generate(
        exe: String,
        ckpt: String,
        tokenizer: String,
        prompt: String,
        chat: Boolean,
        maxTokens: Int,
        temperature: Double,
        seed: Long
    ): List<String> = buildList {
        add(exe); add("generate")
        add("--ckpt"); add(ckpt)
        add("--tokenizer"); add(tokenizer)
        add("--prompt"); add(prompt)
        if (chat) add("--chat")
        add("--max-tokens"); add(maxTokens.toString())
        add("--temperature"); add(temperature.toString())
        add("--seed"); add(seed.toString())
    }
}
