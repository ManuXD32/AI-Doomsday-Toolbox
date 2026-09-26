package com.example.llamadroid.tama.ui

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.example.llamadroid.R
import com.example.llamadroid.tama.data.TamaPet
import com.example.llamadroid.tama.data.TamaQuestCompletionPresentation
import com.example.llamadroid.tama.game.TamaGameEngine
import com.example.llamadroid.tama.world.persistence.TamaWorldActionReceiptEntity
import com.example.llamadroid.tama.world.persistence.TamaWorldActionReceiptKind
import com.example.llamadroid.tama.world.persistence.TamaWorldActionReceiptResult
import com.example.llamadroid.tama.world.persistence.TamaWorldActionReceiptStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

private val parkReceiptJson = Json { ignoreUnknownKeys = true }

/** Completion presentation comes only from a committed receipt, including after process death. */
@Composable
internal fun TamaParkReceiptHost(pet: TamaPet?, engine: TamaGameEngine, onHandled: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val receiptsFlow = remember(engine, pet?.id) {
        pet?.let { engine.world.observeParkActionReceipts(it.id) }
            ?.catch { emit(emptyList()) }
            ?: flowOf(emptyList<TamaWorldActionReceiptEntity>())
    }
    val receipts by receiptsFlow.collectAsState(initial = emptyList())
    val receipt = receipts.firstOrNull { it.kind in parkReceiptKinds && it.status in setOf(
        TamaWorldActionReceiptStatus.SUCCEEDED, TamaWorldActionReceiptStatus.REJECTED, TamaWorldActionReceiptStatus.FAILED
    ) }
    val result = remember(receipt?.id, receipt?.resultJson) {
        receipt?.resultJson?.let { runCatching { parkReceiptJson.decodeFromString<TamaWorldActionReceiptResult>(it) }.getOrNull() }
    }
    val presentation = if (receipt?.status == TamaWorldActionReceiptStatus.SUCCEEDED && result?.success == true) {
        result.questPresentation?.let { TamaQuestCompletionPresentation(it.npcId, it.npcName, it.thanksLine, it.rewardCoins) }
    } else null

    suspend fun acknowledge(row: TamaWorldActionReceiptEntity) {
        try {
            if (engine.world.acknowledgeParkAction(row.petId, row.id)) onHandled()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Toast.makeText(context, R.string.tama_world_runtime_action_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    if (pet != null && presentation != null && receipt != null) {
        TamaQuestRewardDialog(pet, presentation) { scope.launch { acknowledge(receipt) } }
    }
    LaunchedEffect(receipt?.id, presentation) {
        if (receipt == null || presentation != null) return@LaunchedEffect
        val message = result?.message?.takeIf(String::isNotBlank)
            ?: if (result?.success != true) context.getString(R.string.tama_world_runtime_action_unavailable) else null
        if (message != null) Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        acknowledge(receipt)
    }
}

private val parkReceiptKinds = setOf(
    TamaWorldActionReceiptKind.PARK_QUEST_ACCEPT, TamaWorldActionReceiptKind.PARK_QUEST_FINISH,
    TamaWorldActionReceiptKind.RECYCLER_HELP, TamaWorldActionReceiptKind.RECYCLER_FINISH,
    TamaWorldActionReceiptKind.RECYCLER_DECLINE, TamaWorldActionReceiptKind.SELLER_ACCEPT,
    TamaWorldActionReceiptKind.SELLER_SALE, TamaWorldActionReceiptKind.SELLER_DECLINE,
    TamaWorldActionReceiptKind.SELLER_FINISH
)
