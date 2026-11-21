package com.example.llamadroid.ui.ai.llama.chat.cards

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.llamadroid.R
import com.example.llamadroid.chat.cards.CalloutTone
import com.example.llamadroid.chat.cards.ChartKind
import com.example.llamadroid.chat.cards.ChatCard
import com.example.llamadroid.chat.cards.ChatCardType
import com.example.llamadroid.chat.cards.FormFieldKind
import com.example.llamadroid.chat.cards.omni.OmniBlock
import com.example.llamadroid.ui.ai.llama.chat.cards.omni.ChatOmniStaticPreview

@StringRes
internal fun chatCardTypeTitle(type: ChatCardType): Int = when (type) {
    ChatCardType.CHOICES -> R.string.chat_card_type_choices_title
    ChatCardType.FORM -> R.string.chat_card_type_form_title
    ChatCardType.INPUT -> R.string.chat_card_type_input_title
    ChatCardType.FOLLOWUPS -> R.string.chat_card_type_followups_title
    ChatCardType.MAP -> R.string.chat_card_type_map_title
    ChatCardType.PLACES -> R.string.chat_card_type_places_title
    ChatCardType.IMAGES -> R.string.chat_card_type_images_title
    ChatCardType.WEATHER -> R.string.chat_card_type_weather_title
    ChatCardType.BUDGET -> R.string.chat_card_type_budget_title
    ChatCardType.STATS -> R.string.chat_card_type_stats_title
    ChatCardType.TIMELINE -> R.string.chat_card_type_timeline_title
    ChatCardType.CALLOUT -> R.string.chat_card_type_callout_title
    ChatCardType.LINKS -> R.string.chat_card_type_links_title
    ChatCardType.CHART -> R.string.chat_card_type_chart_title
    ChatCardType.OMNI -> R.string.chat_card_type_omni_title
}

@StringRes
internal fun chatCardTypeDescription(type: ChatCardType): Int = when (type) {
    ChatCardType.CHOICES -> R.string.chat_card_type_choices_desc
    ChatCardType.FORM -> R.string.chat_card_type_form_desc
    ChatCardType.INPUT -> R.string.chat_card_type_input_desc
    ChatCardType.FOLLOWUPS -> R.string.chat_card_type_followups_desc
    ChatCardType.MAP -> R.string.chat_card_type_map_desc
    ChatCardType.PLACES -> R.string.chat_card_type_places_desc
    ChatCardType.IMAGES -> R.string.chat_card_type_images_desc
    ChatCardType.WEATHER -> R.string.chat_card_type_weather_desc
    ChatCardType.BUDGET -> R.string.chat_card_type_budget_desc
    ChatCardType.STATS -> R.string.chat_card_type_stats_desc
    ChatCardType.TIMELINE -> R.string.chat_card_type_timeline_desc
    ChatCardType.CALLOUT -> R.string.chat_card_type_callout_desc
    ChatCardType.LINKS -> R.string.chat_card_type_links_desc
    ChatCardType.CHART -> R.string.chat_card_type_chart_desc
    ChatCardType.OMNI -> R.string.chat_card_type_omni_desc
}

/** Live preview of [type] with sample data. Cards that fetch from the network are replaced by a placeholder. */
@Composable
internal fun ChatCardTypePreview(type: ChatCardType) {
    when (type) {
        ChatCardType.CHOICES -> {
            val card = ChatCard.Choices(
                stringResource(R.string.chat_card_sample_q),
                listOf(
                    stringResource(R.string.chat_card_sample_red),
                    stringResource(R.string.chat_card_sample_blue),
                    stringResource(R.string.chat_card_sample_green)
                )
            )
            ChatCardView(card, answerState = null, submitEnabled = false, onSubmit = { _, _ -> })
        }
        ChatCardType.FORM -> {
            val card = ChatCard.Form(
                stringResource(R.string.chat_card_sample_form_title),
                listOf(
                    ChatCard.FormField("d", stringResource(R.string.chat_card_sample_form_date), FormFieldKind.DATE),
                    ChatCard.FormField(
                        "t", stringResource(R.string.chat_card_sample_form_transport), FormFieldKind.SELECT,
                        options = listOf(stringResource(R.string.chat_card_sample_car), stringResource(R.string.chat_card_sample_train))
                    )
                ),
                stringResource(R.string.chat_card_sample_send)
            )
            ChatCardView(card, answerState = null, submitEnabled = false, onSubmit = { _, _ -> })
        }
        ChatCardType.INPUT -> ChatCardView(
            ChatCard.Input(
                question = stringResource(R.string.chat_card_sample_input_q),
                placeholder = stringResource(R.string.chat_card_sample_input_hint),
                multiline = true
            ),
            answerState = null, submitEnabled = false, onSubmit = { _, _ -> }
        )
        ChatCardType.FOLLOWUPS -> ChatCardView(
            ChatCard.Followups(
                listOf(stringResource(R.string.chat_card_sample_followup1), stringResource(R.string.chat_card_sample_followup2))
            ),
            answerState = null, submitEnabled = false, onSubmit = { _, _ -> }
        )
        ChatCardType.MAP -> OfflinePreview("🗺️")
        ChatCardType.PLACES -> OfflinePreview("📍")
        ChatCardType.IMAGES -> OfflinePreview("🖼️")
        ChatCardType.WEATHER -> OfflinePreview("⛅")
        ChatCardType.BUDGET -> ChatCardView(
            ChatCard.Budget(
                title = stringResource(R.string.chat_card_sample_budget_title),
                currency = "$",
                items = listOf(
                    ChatCard.Budget.Item(stringResource(R.string.chat_card_sample_budget1), "🏨", 240.0),
                    ChatCard.Budget.Item(stringResource(R.string.chat_card_sample_budget2), "🍽️", 90.0)
                )
            ),
            answerState = null, submitEnabled = false, onSubmit = { _, _ -> }
        )
        ChatCardType.STATS -> ChatCardView(
            ChatCard.Stats(
                listOf(
                    ChatCard.Stats.Item(stringResource(R.string.chat_card_sample_stat1), "1,284", "+12%", "👥"),
                    ChatCard.Stats.Item(stringResource(R.string.chat_card_sample_stat2), "$4.2k", "-3%", "💰")
                )
            ),
            answerState = null, submitEnabled = false, onSubmit = { _, _ -> }
        )
        ChatCardType.TIMELINE -> ChatCardView(
            ChatCard.Timeline(
                listOf(
                    ChatCard.Timeline.Item("09:00", stringResource(R.string.chat_card_sample_step1), icon = "🛬"),
                    ChatCard.Timeline.Item("11:30", stringResource(R.string.chat_card_sample_step2), icon = "🏛️")
                )
            ),
            answerState = null, submitEnabled = false, onSubmit = { _, _ -> }
        )
        ChatCardType.CALLOUT -> ChatCardView(
            ChatCard.Callout(
                CalloutTone.TIP,
                stringResource(R.string.chat_card_sample_callout_title),
                stringResource(R.string.chat_card_sample_callout_text)
            ),
            answerState = null, submitEnabled = false, onSubmit = { _, _ -> }
        )
        ChatCardType.LINKS -> ChatCardView(
            ChatCard.Links(
                listOf(
                    ChatCard.Links.Item(
                        "https://example.org",
                        stringResource(R.string.chat_card_sample_link),
                        stringResource(R.string.chat_card_sample_link_note)
                    )
                )
            ),
            answerState = null, submitEnabled = false, onSubmit = { _, _ -> }
        )
        ChatCardType.CHART -> ChartKindPreviews()
        ChatCardType.OMNI -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ChatOmniStaticPreview(
                ChatCard.Omni(
                    title = stringResource(R.string.chat_card_sample_omni_title),
                    blocks = listOf(
                        OmniBlock.Number("bill", "Bill", 50.0, 0.0, null, 1.0, "€"),
                        OmniBlock.Slider("tip", "Tip", 0.0, 30.0, null, 15.0, "%"),
                        OmniBlock.Result("Total", "bill*(1+tip/100)", "€", 2)
                    )
                )
            )
            ChatOmniStaticPreview(
                ChatCard.Omni(
                    title = null,
                    blocks = listOf(
                        OmniBlock.Keypad(null),
                        OmniBlock.Checklist("tasks", "Tasks", listOf("Milk", "Bread", "Eggs")),
                        OmniBlock.Timer(null, 90),
                        OmniBlock.Dice("dice", "", 2, 6)
                    )
                )
            )
            ChatOmniStaticPreview(
                ChatCard.Omni(
                    title = null,
                    blocks = listOf(
                        OmniBlock.Steps("Steps", listOf("Boil water", "Add pasta", "Drain")),
                        OmniBlock.Quiz("quiz", "Quiz", listOf(OmniBlock.QuizQ("2 + 2?", listOf("3", "4", "5"), 1))),
                        OmniBlock.Progress("Quiz score", "quiz", "1", null, "gauge"),
                        OmniBlock.Bars("Budget", listOf("Food" to "300", "Rent" to "800")),
                        OmniBlock.Date("day", "Start date", "2026-11-17"),
                        OmniBlock.Result("30 days later", "day + 30", null, 0, "date"),
                        OmniBlock.Scoreboard("sc", "Scores", listOf("Ana", "Leo"))
                    )
                )
            )
        }
    }
}

/** Placeholder for cards that load data from the internet when shown. */
@Composable
private fun OfflinePreview(emoji: String) {
    CardShell(title = null) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(emoji, fontSize = 32.sp)
            Text(
                stringResource(R.string.chat_card_gallery_online),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/** One chart per kind so the gallery shows every chart the model can write. */
@Composable
private fun ChartKindPreviews() {
    val cats = listOf(
        stringResource(R.string.chat_card_sample_cat1),
        stringResource(R.string.chat_card_sample_cat2),
        stringResource(R.string.chat_card_sample_cat3)
    )
    val series = stringResource(R.string.chat_card_sample_series)
    val titles = mapOf(
        ChartKind.BAR to stringResource(R.string.chat_card_sample_chart_title_bar),
        ChartKind.LINE to stringResource(R.string.chat_card_sample_chart_title_line),
        ChartKind.AREA to stringResource(R.string.chat_card_sample_chart_title_area),
        ChartKind.HBAR to stringResource(R.string.chat_card_sample_chart_title_hbar),
        ChartKind.PIE to stringResource(R.string.chat_card_sample_chart_title_pie),
        ChartKind.SCATTER to stringResource(R.string.chat_card_sample_chart_title_scatter),
    )
    val charts = remember(cats, series, titles) {
        ChartKind.entries.map { kind ->
            if (kind == ChartKind.SCATTER) {
                ChatCard.Chart(
                    kind, emptyList(),
                    listOf(ChatCard.Chart.Series(series, emptyList(), listOf(1.0 to 2.0, 2.0 to 3.5, 3.0 to 3.0, 4.0 to 5.5))),
                    title = titles[kind]
                )
            } else {
                val data = if (kind == ChartKind.PIE) listOf(5.0, 3.0, 2.0) else listOf(4.0, 7.0, 5.0)
                ChatCard.Chart(kind, cats, listOf(ChatCard.Chart.Series(series, data)), title = titles[kind])
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        charts.forEach { ChatChartCard(it) }
    }
}
