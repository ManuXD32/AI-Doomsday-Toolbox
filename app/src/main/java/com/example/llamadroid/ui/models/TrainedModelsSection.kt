package com.example.llamadroid.ui.models

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.util.FormatUtils
import com.example.llamadroid.modeltools.GgufQuantizeSupport
import java.util.Locale

const val MODEL_ORIGIN_BRANCHY_EXPORT = "branchy_export"
const val MODEL_ORIGIN_QUANTIZED = "quantized"

/**
 * One lineage family. [rootFilename] is the original file; [root] is that model when it is
 * installed (it may be an ordinary model without origin). [members] are the origin != null
 * models of the family, root first when it has an origin itself.
 */
data class TrainedModelFamily(
    val rootFilename: String,
    val root: ModelEntity?,
    val members: List<ModelEntity>
)

/** True for models that belong in the "Trained & quantized" section. */
fun ModelEntity.isLineageModel(): Boolean = origin != null

/** Quantization tag from a GGUF filename (e.g. `Q4_K_M`), or null. */
fun lineageQuantTag(filename: String): String? = GgufQuantizeSupport.quantTag(filename)

/**
 * Groups models with origin != null into families. Chains (quantized of quantized) resolve to the
 * topmost ancestor; a derivedFrom pointing outside the lineage set (e.g. a quantized HF model)
 * makes that original the family root. Pure and cycle-safe.
 */
fun trainedModelFamilies(models: List<ModelEntity>): List<TrainedModelFamily> {
    val lineage = models.filter { it.isLineageModel() }
    val byName = lineage.associateBy { it.filename }
    val all = models.associateBy { it.filename }
    fun rootOf(model: ModelEntity): String {
        var current = model
        val path = mutableListOf(current.filename)
        while (true) {
            val parent = current.derivedFrom?.takeIf { it.isNotBlank() } ?: return current.filename
            val next = byName[parent] ?: return parent
            val loopAt = path.indexOf(next.filename)
            // A derivation cycle: every member resolves to the same (smallest) name of the loop.
            if (loopAt >= 0) return path.subList(loopAt, path.size).minOf { it }
            path.add(next.filename)
            current = next
        }
    }
    return lineage.groupBy { rootOf(it) }.map { (rootName, members) ->
        val sorted = members.sortedWith(
            compareByDescending<ModelEntity> { it.filename == rootName }
                .thenByDescending { it.origin == MODEL_ORIGIN_BRANCHY_EXPORT }
                .thenBy { it.filename.lowercase(Locale.ROOT) }
        )
        TrainedModelFamily(rootName, all[rootName], sorted)
    }.sortedBy { it.rootFilename.lowercase(Locale.ROOT) }
}

@Composable
fun TrainedModelsSection(
    families: List<TrainedModelFamily>,
    onQuantize: (ModelEntity) -> Unit,
    onShare: (ModelEntity) -> Unit,
    onDelete: (ModelEntity) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.model_lineage_section_title, families.sumOf { it.members.size }),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        families.forEach { family ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (family.root?.isLineageModel() != true) {
                        Text(
                            stringResource(R.string.model_lineage_family_original, family.rootFilename),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    family.members.forEach { model ->
                        TrainedModelRow(
                            model = model,
                            indented = model.filename != family.rootFilename,
                            onQuantize = onQuantize,
                            onShare = onShare,
                            onDelete = onDelete
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TrainedModelRow(
    model: ModelEntity,
    indented: Boolean,
    onQuantize: (ModelEntity) -> Unit,
    onShare: (ModelEntity) -> Unit,
    onDelete: (ModelEntity) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = if (indented) 12.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                model.filename,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                    Text(
                        stringResource(
                            if (model.origin == MODEL_ORIGIN_BRANCHY_EXPORT) R.string.model_lineage_origin_branchy
                            else R.string.model_lineage_origin_quantized
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    listOfNotNull(lineageQuantTag(model.filename), FormatUtils.formatFileSize(model.sizeBytes))
                        .joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
            }
        }
        if (model.type == ModelType.LLM && model.path.endsWith(".gguf", ignoreCase = true)) {
            IconButton(onClick = { onQuantize(model) }) {
                Icon(Icons.Default.Compress, stringResource(R.string.model_quantize_action), tint = MaterialTheme.colorScheme.secondary)
            }
        }
        IconButton(onClick = { onShare(model) }) {
            Icon(Icons.Default.Share, stringResource(R.string.action_share), tint = MaterialTheme.colorScheme.primary)
        }
        IconButton(onClick = { onDelete(model) }) {
            Icon(Icons.Default.Delete, stringResource(R.string.action_delete), tint = MaterialTheme.colorScheme.error)
        }
    }
}
