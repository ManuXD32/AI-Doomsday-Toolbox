package com.example.llamadroid.ui.distributed

import com.example.llamadroid.R
import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkTopologyUiSupportTest {
    @Test
    fun `memory labels keep precise format argument counts`() {
        val labels = topologyMemoryLabelSpecs(workerCount = 2)

        assertEquals(R.string.worker_topology_cluster_memory_label, labels.cluster.resourceId)
        assertEquals(0, labels.cluster.formatArgs.size)
        assertEquals(R.string.worker_topology_master_memory_label, labels.master.resourceId)
        assertEquals(0, labels.master.formatArgs.size)
        assertEquals(
            listOf(1, 2),
            labels.workers.map { it.formatArgs.single() }
        )
        assertEquals(
            listOf(1, 1),
            labels.workers.map { it.formatArgs.size }
        )
    }

    @Test
    fun `English and Spanish labels format with actual screen arguments`() {
        val names = mapOf(
            R.string.worker_topology_cluster_memory_label to "worker_topology_cluster_memory_label",
            R.string.worker_topology_master_memory_label to "worker_topology_master_memory_label",
            R.string.worker_topology_worker_memory_label to "worker_topology_worker_memory_label"
        )
        listOf("values", "values-es").forEach { language ->
            val relative = "src/main/res/$language/strings_network_topology_controls.xml"
            val file = listOf(java.io.File(relative), java.io.File("app/$relative")).first { it.isFile }
            val document = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            val nodes = document.getElementsByTagName("string")
            val strings = (0 until nodes.length).associate { index ->
                val node = nodes.item(index) as org.w3c.dom.Element
                node.getAttribute("name") to node.textContent
            }
            val labels = topologyMemoryLabelSpecs(2)
            (listOf(labels.cluster, labels.master) + labels.workers).forEach { spec ->
                val formatted = String.format(java.util.Locale.ROOT, strings.getValue(names.getValue(spec.resourceId)), *spec.formatArgs.toTypedArray())
                org.junit.Assert.assertTrue(formatted.isNotBlank())
                org.junit.Assert.assertFalse(formatted.contains('%'))
            }
        }
    }

    @Test
    fun `negative worker count cannot create rows`() {
        assertEquals(emptyList<TopologyMemoryLabelSpec>(), topologyMemoryLabelSpecs(-1).workers)
    }
}
