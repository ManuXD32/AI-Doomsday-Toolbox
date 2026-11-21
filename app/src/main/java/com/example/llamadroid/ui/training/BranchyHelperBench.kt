package com.example.llamadroid.ui.training

import android.app.Application
import com.example.llamadroid.branchy.BranchyClusterRun
import com.example.llamadroid.branchy.BranchyEvent
import com.example.llamadroid.branchy.BranchyNative
import com.example.llamadroid.branchy.cluster.BranchyClusterDeviceEntity
import com.example.llamadroid.branchy.cluster.BranchyClusterToken
import com.example.llamadroid.branchy.cluster.BranchyEndpointPreflight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Shared `cluster-bench` path of the wizard and the paused-run editor (one helper or the whole selection). */
internal object BranchyHelperBench {
    /** Result row of one helper's sweep; a failed or empty sweep is not [BenchCurve.applied]. */
    fun curve(d: BranchyEvent.BenchDevice, name: String?): BenchCurve = BenchCurve(
        id = d.id,
        name = name ?: d.name ?: d.id,
        points = d.results,
        best = d.bestThreads.takeIf { it > 0 } ?: BranchyBenchMath.best(d.results),
        gflops = d.gflops,
        applied = d.error == null && d.gflops > 0.0,
        error = d.error
    )

    /**
     * Benchmarks [selected] (Wi-Fi/VPN address preflighted first, as for a run) and reports each finished helper to
     * [onCurve]. Returns without work when there is nothing to benchmark.
     */
    suspend fun run(
        app: Application,
        native: BranchyNative,
        selected: List<BranchyClusterDeviceEntity>,
        onCurve: (BenchCurve) -> Unit
    ) {
        val helpers = BranchyEndpointPreflight.preflight(app, selected)
        val json = BranchyClusterRun.benchClusterJson("bench", helpers) ?: return
        val config = File(app.cacheDir, "branchy_bench_cluster.json")
        val tokenFile = BranchyClusterToken.writeTokenFile(app)
        withContext(Dispatchers.IO) { config.writeText(json) }
        val names = helpers.associate { it.id to it.name }
        native.clusterBench(config, tokenFile, apply = true) { d -> onCurve(curve(d, names[d.id])) }
    }
}
