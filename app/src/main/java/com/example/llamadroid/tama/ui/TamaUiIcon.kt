package com.example.llamadroid.tama.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

private const val TAMA_UI_ICON_ASSET_PREFIX = "tama/icons/ui"
private const val TAMA_UI_ICON_SLOT_SCALE = 1.25f
private const val TAMA_UI_ICON_ART_SCALE = 2f

internal object TamaUiIconCatalog {
    private val foodItemIdToAsset = mapOf(
        "apple" to "food_apple.png",
        "bread" to "food_bread.png",
        "cake" to "food_cake.png",
        "pizza" to "food_pizza.png",
        "burger" to "food_burger.png",
        "sushi" to "food_sushi.png",
        "donut" to "food_donut.png",
        "salad" to "food_salad.png",
        "lettuce" to "food_lettuce.png",
        "candy" to "food_candy.png"
    )

    private val emojiToAsset = mapOf(
        "💟" to "tama_heart_badge.png",
        "🐾" to "pet_view_paw.png",
        "🗺" to "map_view.png",
        "🍖" to "meat_hunger_feed.png",
        "😊" to "happiness_face.png",
        "❤" to "health_heart.png",
        "🌙" to "moon_energy_sleep.png",
        "🧼" to "soap_hygiene.png",
        "💰" to "money_bag.png",
        "📚" to "books_education.png",
        "💬" to "chat_bubble.png",
        "🎒" to "inventory_backpack.png",
        "✅" to "checklist.png",
        "☀" to "sun_wake.png",
        "🛒" to "shopping_cart.png",
        "🕹" to "arcade_joystick.png",
        "🌳" to "park_tree_relax.png",
        "⚗" to "alchemy_flask.png",
        "💊" to "medicine_pill.png",
        "⭐" to "star_menu.png",
        "✋" to "stop_hand.png",
        "✓" to "selected_check.png",
        "🧽" to "clean_sponge.png",
        "🎾" to "play_ball.png",
        "✨" to "sparkles_dream.png",
        "💼" to "work_briefcase.png",
        "📜" to "quest_scroll.png",
        "😄" to "mood_ecstatic.png",
        "🙂" to "mood_happy.png",
        "😐" to "mood_neutral.png",
        "😢" to "mood_sad.png",
        "🥱" to "mood_sleepy.png",
        "😠" to "mood_angry.png",
        "🤒" to "mood_sick.png",
        "😴" to "mood_sleeping.png",
        "😡" to "livestock_angry.png",
        "🍎" to "food_apple.png",
        "🍞" to "food_bread.png",
        "🎂" to "food_cake.png",
        "🍕" to "food_pizza.png",
        "🍔" to "food_burger.png",
        "🍣" to "food_sushi.png",
        "🍩" to "food_donut.png",
        "🥗" to "food_salad.png",
        "🥬" to "food_lettuce.png",
        "🍬" to "food_candy.png",
        "🏠" to "location_home.png",
        "🏫" to "location_school.png",
        "🏢" to "location_workplace.png",
        "🏪" to "location_shop.png",
        "🏥" to "location_hospital.png",
        "🌾" to "farm_wheat.png",
        "🏚" to "location_dungeon.png",
        "🌀" to "adventure_gate_portal.png",
        "🐄" to "cow.png",
        "🐔" to "chicken.png",
        "🥚" to "egg.png",
        "🪙" to "coin.png",
        "🧪" to "potion_bottle.png",
        "🎮" to "toy_gamepad.png",
        "🌱" to "seed_sprout.png",
        "🖼" to "decoration_picture.png",
        "⚔" to "weapon_swords.png",
        "🛡" to "armor_shield.png",
        "💍" to "accessory_ring.png",
        "💎" to "treasure_gem.png",
        "🛠" to "tool_hammer_wrench.png",
        "📦" to "material_box.png",
        "🗑" to "delete_trash.png",
        "💀" to "dungeon_shadow_crypts_skull.png",
        "🔮" to "dungeon_arcane_depths_orb.png",
        "🔥" to "dungeon_infernal_spire_flame.png",
        "❄" to "dungeon_frostbound_halls_snowflake.png",
        "🌿" to "dungeon_verdant_maw_vine.png",
        "🌑" to "dungeon_void_sanctum_moon.png",
        "🎲" to "dungeon_chaos_realm_dice.png"
    )

    fun assetPathForEmoji(emoji: String?): String? {
        val normalized = emoji?.replace("\uFE0F", "").orEmpty()
        return emojiToAsset[normalized]?.let { "$TAMA_UI_ICON_ASSET_PREFIX/$it" }
    }

    fun assetPathForFoodItemId(itemId: String): String? {
        return foodItemIdToAsset[itemId]?.let { "$TAMA_UI_ICON_ASSET_PREFIX/$it" }
    }
}

@Composable
internal fun TamaUiIcon(
    emoji: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 18.sp
) {
    val assetPath = remember(emoji) { TamaUiIconCatalog.assetPathForEmoji(emoji) }
    if (assetPath != null) {
        val slotSize = with(LocalDensity.current) {
            fontSize.toPx().toDp() * TAMA_UI_ICON_SLOT_SCALE
        }
        Box(
            modifier = modifier.size(slotSize.coerceAtLeast(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            AsyncImage(
                model = "file:///android_asset/$assetPath",
                contentDescription = null,
                modifier = Modifier
                    .size(slotSize.coerceAtLeast(12.dp))
                    .graphicsLayer(
                        scaleX = TAMA_UI_ICON_ART_SCALE,
                        scaleY = TAMA_UI_ICON_ART_SCALE
                    ),
                contentScale = ContentScale.Fit,
                filterQuality = FilterQuality.None
            )
        }
    } else {
        Text(
            text = emoji,
            modifier = modifier,
            fontSize = fontSize,
            lineHeight = fontSize
        )
    }
}
