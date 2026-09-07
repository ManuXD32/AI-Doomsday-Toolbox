package com.example.llamadroid.ui.distributed

import androidx.annotation.StringRes
import com.example.llamadroid.R

internal data class TopologyMemoryLabelSpec(
    @StringRes val resourceId: Int,
    val formatArgs: List<Any> = emptyList()
)

internal data class TopologyMemoryLabelSpecs(
    val cluster: TopologyMemoryLabelSpec,
    val master: TopologyMemoryLabelSpec,
    val workers: List<TopologyMemoryLabelSpec>
)

internal fun topologyMemoryLabelSpecs(workerCount: Int): TopologyMemoryLabelSpecs =
    TopologyMemoryLabelSpecs(
        cluster = TopologyMemoryLabelSpec(R.string.worker_topology_cluster_memory_label),
        master = TopologyMemoryLabelSpec(R.string.worker_topology_master_memory_label),
        workers = List(workerCount.coerceAtLeast(0)) { index ->
            TopologyMemoryLabelSpec(
                resourceId = R.string.worker_topology_worker_memory_label,
                formatArgs = listOf(index + 1)
            )
        }
    )
