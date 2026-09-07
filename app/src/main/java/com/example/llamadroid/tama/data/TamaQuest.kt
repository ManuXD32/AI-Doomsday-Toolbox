package com.example.llamadroid.tama.data

import android.content.Context
import com.example.llamadroid.R
import java.util.Calendar
import java.util.Locale
import kotlin.math.ceil
import kotlinx.serialization.Serializable

@Serializable
data class QuestItemRequest(
    val itemId: String,
    val quantity: Int
)

@Serializable
data class LegacyQuestCropRequest(
    val cropId: String,
    val quantity: Int
)

@Serializable
enum class TamaQuestStatus {
    AVAILABLE,
    ACCEPTED,
    COMPLETED,
    EXPIRED
}

@Serializable
data class TamaQuest(
    val id: String,
    val petId: String,
    val status: TamaQuestStatus,
    val generatedDateKey: String,
    val acceptedAt: Long? = null,
    val expiresAt: Long? = null,
    val completedAt: Long? = null,
    val npcId: String,
    val requests: List<QuestItemRequest>,
    val rewardCoins: Long,
    val summary: TamaLocalizedText
)

data class TamaQuestBoard(
    val available: List<TamaQuest>,
    val accepted: List<TamaQuest>,
    val nextRefreshAt: Long
)

data class TamaQuestCompletionPresentation(
    val npcId: String,
    val npcName: String,
    val thanksLine: String,
    val rewardCoins: Long
)

fun cropDisplayText(cropId: String): TamaLocalizedText {
    return when (cropId.lowercase(Locale.ROOT)) {
        "wheat" -> TamaLocalizedText("Wheat", "Trigo")
        "rice" -> TamaLocalizedText("Rice", "Arroz")
        "carrot" -> TamaLocalizedText("Carrot", "Zanahoria")
        "tomato" -> TamaLocalizedText("Tomato", "Tomate")
        "corn" -> TamaLocalizedText("Corn", "Maíz")
        "strawberry" -> TamaLocalizedText("Strawberry", "Fresa")
        "daisy" -> TamaLocalizedText("Daisy", "Margarita")
        "sunflower" -> TamaLocalizedText("Sunflower", "Girasol")
        "rose" -> TamaLocalizedText("Rose", "Rosa")
        "tullip" -> TamaLocalizedText("Tulip", "Tulipán")
        "melon" -> TamaLocalizedText("Melon", "Melón")
        "pumpkin" -> TamaLocalizedText("Pumpkin", "Calabaza")
        else -> {
            val displayName = cropId.replace('_', ' ').replaceFirstChar { it.titlecase(Locale.getDefault()) }
            TamaLocalizedText(displayName, displayName)
        }
    }
}

fun cropDisplayName(context: Context, cropId: String): String {
    return cropDisplayText(cropId).resolve(context.resources.configuration.locales[0])
}

fun seedDisplayText(cropId: String): TamaLocalizedText {
    val cropLabel = cropDisplayText(cropId)
    return TamaLocalizedText(
        en = "${cropLabel.en} Seeds",
        es = "Semillas de ${cropLabel.es}"
    )
}

fun inventoryItemDisplayName(context: Context, item: InventoryItem): String {
    when (item.id) {
        "hoe", "hoe_starter" -> return context.getString(R.string.tama_inventory_hoe)
        "watering_can", "watering_can_starter" -> return context.getString(R.string.tama_inventory_watering_can)
    }
    val locale = context.resources.configuration.locales[0]
    return when (item.type) {
        ItemType.CROP -> FarmTradeItemCatalog.displayName(item.id, locale)
        ItemType.SEED -> seedDisplayText(item.id.removePrefix("seed_")).resolve(locale)
        else -> item.name
    }
}

fun questSummaryText(context: Context, requests: List<QuestItemRequest>): TamaLocalizedText {
    val english = requests.joinToString(", ") { request ->
        "${request.quantity} ${FarmTradeItemCatalog.displayText(request.itemId)?.en ?: request.itemId}"
    }
    val spanish = requests.joinToString(", ") { request ->
        "${request.quantity} ${FarmTradeItemCatalog.displayText(request.itemId)?.es ?: request.itemId}"
    }
    return TamaLocalizedText(english, spanish)
}

fun questAcceptedEventText(
    context: Context,
    petName: String,
    npcName: String,
    questSummary: String,
    rewardCoins: Long
): String {
    return context.getString(
        R.string.tama_quest_event_accepted,
        petName,
        npcName,
        questSummary,
        rewardCoins
    )
}

fun questCompletedEventText(
    context: Context,
    petName: String,
    npcName: String,
    questSummary: String,
    rewardCoins: Long
): String {
    return context.getString(
        R.string.tama_quest_event_completed,
        petName,
        questSummary,
        npcName,
        rewardCoins
    )
}

fun questRewardDetailsText(
    context: Context,
    petName: String,
    npcName: String,
    questSummary: String,
    rewardCoins: Long
): String {
    return context.getString(
        R.string.tama_quest_reward_details,
        petName,
        questSummary,
        npcName,
        rewardCoins
    )
}

fun nextLocalMidnightMillis(now: Long = System.currentTimeMillis()): Long {
    return Calendar.getInstance().apply {
        timeInMillis = now
        add(Calendar.DAY_OF_YEAR, 1)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}

fun questDateKey(now: Long = System.currentTimeMillis()): String {
    val calendar = Calendar.getInstance().apply { timeInMillis = now }
    return String.format(
        Locale.US,
        "%04d-%02d-%02d",
        calendar.get(Calendar.YEAR),
        calendar.get(Calendar.MONTH) + 1,
        calendar.get(Calendar.DAY_OF_MONTH)
    )
}

fun calculateQuestReward(requests: List<QuestItemRequest>, multiplier: Double): Long {
    val baseValue = requests.sumOf { request ->
        FarmTradeItemCatalog.sellPrice(request.itemId) * request.quantity
    }
    return ceil(baseValue * multiplier).toLong().coerceAtLeast(baseValue.toLong())
}

fun questRewardMultiplier(random: kotlin.random.Random): Double {
    return random.nextDouble(1.75, 2.5000001)
}
