package com.example.llamadroid.chat.cards

import com.example.llamadroid.chat.cards.omni.OmniBlock
import com.example.llamadroid.chat.cards.omni.OmniIssue

/** Markdown fence language that marks an interactive/display card inside an assistant message. */
const val CHAT_CARD_FENCE_LANGUAGE = "card"

enum class FormFieldKind { TEXT, NUMBER, DATE, SELECT, TOGGLE }

enum class CalloutTone { INFO, TIP, WARNING, DANGER }

enum class ChartKind { BAR, LINE, PIE, AREA, SCATTER, HBAR }

/** Parsed chat card. Interactive: [Choices], [Form], [Input], [Followups]. Everything else is display-only ([Omni] sends through its own bridge). */
sealed interface ChatCard {
    val isInteractive: Boolean get() = false

    data class Choices(
        val question: String,
        val options: List<String>,
        val multi: Boolean = false,
        val allowOther: Boolean = false,
    ) : ChatCard {
        override val isInteractive: Boolean get() = true
    }

    /** Free-text question: the user types an answer. [submit] overrides the Send button label. */
    data class Input(
        val question: String,
        val placeholder: String? = null,
        val multiline: Boolean = false,
        val submit: String? = null,
    ) : ChatCard {
        override val isInteractive: Boolean get() = true
    }

    data class FormField(
        val id: String,
        val label: String,
        val kind: FormFieldKind = FormFieldKind.TEXT,
        val options: List<String> = emptyList(),
        val placeholder: String? = null,
        val required: Boolean = false,
    )

    data class Form(
        val title: String?,
        val fields: List<FormField>,
        val submit: String?,
    ) : ChatCard {
        override val isInteractive: Boolean get() = true
    }

    data class Followups(val items: List<String>) : ChatCard {
        override val isInteractive: Boolean get() = true
    }

    data class MapPlace(
        val name: String,
        val lat: Double? = null,
        val lng: Double? = null,
        val note: String? = null,
        val icon: String? = null,
    )

    data class MapCard(
        val title: String?,
        val places: List<MapPlace>,
        val route: Boolean = false,
    ) : ChatCard

    data class Stats(val items: List<Item>) : ChatCard {
        data class Item(val label: String, val value: String, val delta: String? = null, val icon: String? = null)
    }

    data class Timeline(val items: List<Item>) : ChatCard {
        data class Item(val time: String?, val title: String, val detail: String? = null, val icon: String? = null)
    }

    data class Callout(val tone: CalloutTone, val title: String?, val text: String) : ChatCard

    data class Links(val items: List<Item>) : ChatCard {
        data class Item(val url: String, val title: String? = null, val note: String? = null, val icon: String? = null)
    }

    data class Chart(
        val kind: ChartKind,
        val labels: List<String>,
        val series: List<Series>,
        val unit: String? = null,
        val title: String? = null,
        val donut: Boolean = false,
        val xLabel: String? = null,
        val yLabel: String? = null,
    ) : ChatCard {
        /** [data] is used by every kind except SCATTER, which uses [points] (x to y) instead. */
        data class Series(val name: String, val data: List<Double>, val points: List<Pair<Double, Double>> = emptyList())
    }

    data class Places(val title: String?, val items: List<Item>) : ChatCard {
        data class Item(
            val name: String,
            val subtitle: String? = null,
            val description: String? = null,
            val tags: List<String> = emptyList(),
            val rating: Double? = null,
            val price: String? = null,
            /** Photo search query (never a URL); resolved by CardImageResolver. */
            val image: String? = null,
            val url: String? = null,
        )
    }

    data class Images(val items: List<Item>) : ChatCard {
        data class Item(val query: String, val caption: String? = null)
    }

    data class Weather(val location: String, val days: Int = 5) : ChatCard

    /** Interactive building-block mini-app (calculator, tracker, ...). Display-only for the batch; may send one message. */
    data class Omni(val title: String?, val blocks: List<OmniBlock>, val issues: List<OmniIssue> = emptyList()) : ChatCard

    data class Budget(
        val title: String?,
        val currency: String = "",
        val people: Int? = null,
        val cap: Double? = null,
        val items: List<Item>,
    ) : ChatCard {
        data class Item(
            val label: String,
            val icon: String? = null,
            val amount: Double,
            val min: Double? = null,
            val max: Double? = null,
            val note: String? = null,
        )
    }
}
