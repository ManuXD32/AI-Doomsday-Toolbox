package com.example.llamadroid.chat.cards

import com.example.llamadroid.chat.cards.omni.OmniParser
import com.example.llamadroid.chat.cards.omni.OmniValidator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * Parses the JSON body of a ```card fence. Lenient and bounded: input is capped at [MAX_INPUT_CHARS],
 * repaired in one linear pass, then validated/normalised. Returns null when no valid card results.
 * The lenient-repair idea is credited to Frontier-UI's `_loads_lenient`.
 */
object ChatCardParser {
    const val MAX_INPUT_CHARS = 32 * 1024
    private const val MAX_TEXT = 600
    private const val MAX_DEPTH = 24
    private val lenient = Json { isLenient = true; ignoreUnknownKeys = true }

    fun parse(json: String): ChatCard? {
        if (json.length > MAX_INPUT_CHARS || tooDeep(json)) return null
        // The lenient reader accepts some broken input (e.g. smart quotes as bare words) without producing
        // a usable card, so a failed build is retried once on the repaired text.
        return candidates(json).firstNotNullOfOrNull { root ->
            unwrap(root)?.let { obj -> runCatching { build(obj) }.getOrNull() }
        }
    }

    /** True when the top-level object of [json] carries an explicit `"type"` that is a known card type or alias. */
    fun hasKnownType(json: String): Boolean {
        if (json.length > MAX_INPUT_CHARS || tooDeep(json)) return false
        return candidates(json).any { (it as? JsonObject)?.typeOf() != null }
    }

    /** Rejects pathological nesting before any recursive parser runs (approximate, bracket-only). */
    private fun tooDeep(text: String): Boolean {
        var depth = 0
        for (c in text) {
            if (c == '{' || c == '[') { if (++depth > MAX_DEPTH) return true }
            else if ((c == '}' || c == ']') && depth > 0) depth--
        }
        return false
    }

    private fun candidates(raw: String): Sequence<JsonElement> = sequence {
        var text = raw.trim()
        if (text.startsWith("```")) text = text.removePrefix("```").substringAfter('\n', "")
        text = text.trim().removeSuffix("```").trim()
        val start = text.indexOf('{')
        if (start < 0) return@sequence
        text = text.substring(start)
        val end = text.lastIndexOf('}')
        val strict = if (end >= 0) text.substring(0, end + 1) else text
        runCatching { lenient.parseToJsonElement(strict) }.getOrNull()?.let { yield(it) }
        runCatching { lenient.parseToJsonElement(ChatCardJsonRepair.repair(text)) }.getOrNull()?.let { yield(it) }
    }

    private fun unwrap(root: JsonElement): JsonObject? {
        var cur: JsonElement = root
        repeat(3) {
            val o = cur as? JsonObject ?: return null
            if (o.typeOf() != null) return o
            val inner = o["card"] ?: o["cards"]?.let { (it as? JsonArray)?.firstOrNull() }
                ?: o["data"] ?: o["payload"]
            cur = inner ?: return null
        }
        return (cur as? JsonObject)?.takeIf { it.typeOf() != null }
    }

    private fun JsonObject.typeOf(): String? {
        val raw = (this["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        // Small models drop "type" on mini-apps; a "blocks" array is only used by omni.
        if (raw == null && this["blocks"] is JsonArray) return "omni"
        val t = raw?.trim()?.lowercase()?.replace(Regex("[\\s_-]+"), "") ?: return null
        if (isSoloOmniBlock(t)) return "omni"
        return when (t) {
            "choices", "choice", "options", "quiz", "question", "poll", "singlechoice", "multichoice", "multiplechoice" -> "choices"
            "form", "questionnaire", "inputform" -> "form"
            "input", "ask", "textinput", "openquestion" -> "input"
            "omni", "app", "widget", "miniapp", "todolist", "checklist", "todo", "packinglist", "shoppinglist" -> "omni"
            "followups", "followup", "suggestions", "suggestedreplies", "quickreplies" -> "followups"
            "map", "route", "locations" -> "map"
            "places", "restaurants", "hotels", "venues", "place" -> "places"
            "images", "gallery", "photos", "pictures" -> "images"
            "weather", "forecast" -> "weather"
            "budget", "costs", "cost", "expenses", "pricing" -> "budget"
            "stats", "stat", "metrics", "kpis", "statistics" -> "stats"
            "timeline", "steps", "events" -> "timeline"
            "callout", "alert", "note", "tip", "warning", "admonition" -> "callout"
            "links", "link", "sources", "references" -> "links"
            "chart", "graph", "plot", "barchart", "linechart", "piechart", "donutchart", "areachart", "scatterchart", "scatterplot",
            "pie", "donut", "area", "scatter", "hbar", "barh", "horizontalbar", "horizontalbarchart", "hbarchart" -> "chart"
            else -> null
        }
    }

    private fun build(o: JsonObject): ChatCard? = when (o.typeOf()) {
        "choices" -> choices(o)
        "form" -> form(o)
        "input" -> input(o)
        "omni" -> omni(o)
        "followups" -> followups(o)
        "map" -> map(o)
        "stats" -> stats(o)
        "timeline" -> timeline(o)
        "callout" -> callout(o)
        "links" -> links(o)
        "chart" -> chart(o)
        "places" -> places(o)
        "images" -> images(o)
        "weather" -> weather(o)
        "budget" -> budget(o)
        else -> null
    }

    // ---- helpers ----
    private fun JsonObject.first(vararg keys: String): JsonElement? =
        keys.firstNotNullOfOrNull { k -> this[k]?.takeIf { it !is JsonNull } }

    private fun JsonElement?.str(): String? {
        val p = this as? JsonPrimitive ?: return null
        if (p is JsonNull) return null
        return p.content.trim().take(MAX_TEXT).ifEmpty { null }
    }

    private fun JsonElement?.bool(default: Boolean = false): Boolean {
        val p = this as? JsonPrimitive ?: return default
        return p.booleanOrNull ?: when (p.content.trim().lowercase()) {
            "yes", "si", "1" -> true
            "no", "0" -> false
            else -> default
        }
    }

    private fun JsonElement?.array(): List<JsonElement> = (this as? JsonArray)?.toList().orEmpty()

    /** Strings from an array; objects contribute label/text/title/name/value. */
    private fun JsonElement?.strings(max: Int): List<String> =
        array().mapNotNull { el ->
            if (el is JsonObject) el.first("label", "text", "title", "name", "value").str() else el.str()
        }.distinct().take(max)

    private fun JsonElement?.objects(): List<JsonObject> = array().mapNotNull { it as? JsonObject }

    private fun choices(o: JsonObject): ChatCard? {
        val options = o.first("options", "choices", "answers", "items").strings(8)
        if (options.size < 2) return null
        val question = o.first("question", "prompt", "title", "text").str() ?: return null
        return ChatCard.Choices(
            question = question,
            options = options,
            multi = o.first("multi", "multiple", "multiselect").bool(),
            allowOther = o.first("allow_other", "allowOther", "other").bool(),
        )
    }

    private fun input(o: JsonObject): ChatCard? {
        val question = o.first("question", "prompt", "title", "text", "label").str() ?: return null
        return ChatCard.Input(
            question = question,
            placeholder = o.first("placeholder", "hint").str(),
            multiline = o.first("multiline", "multi_line", "long").bool(),
            submit = o.first("submit", "submit_label", "button").str(),
        )
    }

    /** Building-block mini-app; null when no valid block remains (html/code-only cards fall back to a code block). */
    /**
     * Small models write one block as the whole card (`{"type":"quiz","questions":[...]}`, `{"type":"flashcards",...}`):
     * those become an omni card with that single block. Quiz needs a questions array (a plain quiz is a choices card) and
     * plot/graph/chart need a formula and no series (otherwise it is a data chart).
     */
    private fun JsonObject.isSoloOmniBlock(t: String): Boolean {
        if (this["blocks"] != null) return false
        return when (t) {
            in SOLO_OMNI_TYPES -> true
            "quiz", "test", "trivia" -> this["questions"] is JsonArray
            "plot", "graph", "chart" -> this["series"] == null && this["data"] == null && this["labels"] == null &&
                first("expr", "exprs", "functions", "formulas") != null
            else -> false
        }
    }

    private val SOLO_OMNI_TYPES = setOf(
        "flashcards", "flashcard", "scoreboard", "intervals", "workout", "pomodoro", "hiit", "matrix", "matrixcalculator",
        "calculator", "keypad", "timer", "countdown", "stopwatch", "dice", "coin", "counter", "tracker", "log", "ledger",
        "progress", "gauge",
    )

    private fun omni(o: JsonObject): ChatCard? {
        val raw = (o["type"] as? JsonPrimitive)?.content?.trim()?.lowercase()?.replace(Regex("[\\s_-]+"), "")
        if (raw != null && o.isSoloOmniBlock(raw)) {
            // The card object is itself the block: OmniParser reads its "type" as the block kind.
            return omni(JsonObject(mapOfNotNull("title" to o.first("title", "name", "label"), "blocks" to JsonArray(listOf(o)))))
        }
        val parsed = OmniParser.parse(o)
        if (parsed.blocks.isNotEmpty()) {
            val issues = (parsed.issues + OmniValidator.issues(parsed.blocks)).take(OmniParser.MAX_ISSUES)
            return ChatCard.Omni(o.first("title", "name").str(), parsed.blocks, issues)
        }
        val fallback = OmniParser.checklistFallback(o).ifEmpty { return null }
        return ChatCard.Omni(o.first("title", "name").str(), fallback)
    }

    private fun form(o: JsonObject): ChatCard? {
        val used = HashSet<String>()
        val fields = o.first("fields", "inputs", "questions").objects().take(8).mapNotNull { f ->
            val label = f.first("label", "name", "title").str() ?: return@mapNotNull null
            var id = f.first("id", "key", "name").str()?.take(40)
                ?: label.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifEmpty { "field" }
            if (!used.add(id)) {
                var k = 2
                while (!used.add("${id}_$k")) k++
                id = "${id}_$k"
            }
            val options = f.first("options", "choices").strings(12)
            var kind = when (f.first("kind", "type").str()?.lowercase()) {
                "number", "numeric", "integer", "int" -> FormFieldKind.NUMBER
                "date" -> FormFieldKind.DATE
                "select", "dropdown", "choice", "radio" -> FormFieldKind.SELECT
                "toggle", "boolean", "checkbox", "switch", "bool" -> FormFieldKind.TOGGLE
                else -> FormFieldKind.TEXT
            }
            if (kind == FormFieldKind.SELECT && options.isEmpty()) kind = FormFieldKind.TEXT
            ChatCard.FormField(
                id = id, label = label, kind = kind,
                options = if (kind == FormFieldKind.SELECT) options else emptyList(),
                placeholder = f.first("placeholder", "hint").str(),
                required = f.first("required").bool(),
            )
        }
        if (fields.isEmpty()) return null
        return ChatCard.Form(o.first("title").str(), fields, o.first("submit", "submit_label", "button").str())
    }

    private fun followups(o: JsonObject): ChatCard? {
        val items = o.first("items", "suggestions", "questions", "options", "replies").strings(4)
        return if (items.isEmpty()) null else ChatCard.Followups(items)
    }

    private fun map(o: JsonObject): ChatCard? {
        val places = o.first("places", "locations", "items", "markers", "points").array().take(12).mapNotNull { el ->
            if (el is JsonPrimitive) {
                return@mapNotNull el.str()?.let { ChatCard.MapPlace(it) }
            }
            val p = el as? JsonObject ?: return@mapNotNull null
            val name = p.first("name", "title", "label", "place").str() ?: return@mapNotNull null
            val lat = p.first("lat", "latitude").num()
            val lng = p.first("lng", "lon", "long", "longitude").num()
            val valid = lat != null && lng != null && lat in -90.0..90.0 && lng in -180.0..180.0
            ChatCard.MapPlace(name, if (valid) lat else null, if (valid) lng else null, p.first("note", "description", "detail").str(), p.first("icon", "emoji").icon())
        }
        if (places.isEmpty()) return null
        return ChatCard.MapCard(o.first("title").str(), places, o.first("route").bool())
    }

    /** Short icon (one emoji or a few characters); longer text is dropped. */
    private fun JsonElement?.icon(): String? = str()?.takeIf { it.length <= 4 }

    private fun JsonElement?.num(): Double? {
        val p = this as? JsonPrimitive ?: return null
        return p.doubleOrNull ?: p.content.trim().toDoubleOrNull()
    }

    private fun stats(o: JsonObject): ChatCard? {
        val items = o.first("items", "stats", "metrics").objects().take(12).mapNotNull { s ->
            val label = s.first("label", "name", "title").str() ?: return@mapNotNull null
            val value = s.first("value", "amount").str() ?: return@mapNotNull null
            ChatCard.Stats.Item(label, value, s.first("delta", "change").str(), s.first("icon", "emoji").icon())
        }
        return if (items.isEmpty()) null else ChatCard.Stats(items)
    }

    private fun timeline(o: JsonObject): ChatCard? {
        val items = o.first("items", "events", "steps").objects().take(20).mapNotNull { s ->
            val title = s.first("title", "name", "label", "event").str() ?: return@mapNotNull null
            ChatCard.Timeline.Item(s.first("time", "date", "when").str(), title, s.first("detail", "description", "text").str(), s.first("icon", "emoji").icon())
        }
        return if (items.isEmpty()) null else ChatCard.Timeline(items)
    }

    private fun callout(o: JsonObject): ChatCard? {
        val text = o.first("text", "message", "body", "content").str() ?: return null
        val raw = (o.first("tone", "level", "severity", "variant").str() ?: (o["type"].str()?.takeIf { it != "callout" }))
        val tone = when (raw?.lowercase()) {
            "tip", "success", "hint" -> CalloutTone.TIP
            "warning", "warn", "caution" -> CalloutTone.WARNING
            "danger", "error", "critical" -> CalloutTone.DANGER
            else -> CalloutTone.INFO
        }
        return ChatCard.Callout(tone, o.first("title").str(), text)
    }

    private fun links(o: JsonObject): ChatCard? {
        val items = o.first("items", "links", "sources").array().take(12).mapNotNull { el ->
            val obj = el as? JsonObject
            val url = (obj?.first("url", "href", "link") ?: el).str() ?: return@mapNotNull null
            if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) return@mapNotNull null
            ChatCard.Links.Item(url, obj?.first("title", "name", "label").str(), obj?.first("note", "description").str(), obj?.first("icon", "emoji").icon())
        }
        return if (items.isEmpty()) null else ChatCard.Links(items)
    }

    /** Maps a kind/type word to a non-bar chart kind; null when it names no special kind. */
    private fun chartKindOf(raw: String?): ChartKind? {
        val t = raw?.lowercase()?.replace(Regex("[\\s_-]+"), "") ?: return null
        return when {
            "scatter" in t -> ChartKind.SCATTER
            "pie" in t || "donut" in t -> ChartKind.PIE
            "area" in t -> ChartKind.AREA
            "hbar" in t || "barh" in t || "horizontalbar" in t -> ChartKind.HBAR
            "line" in t -> ChartKind.LINE
            else -> null
        }
    }

    private fun point(el: JsonElement): Pair<Double, Double>? {
        val (x, y) = when (el) {
            is JsonArray -> el.getOrNull(0).num() to el.getOrNull(1).num()
            is JsonObject -> el["x"].num() to el["y"].num()
            else -> return null
        }
        return if (x == null || y == null) null else x to y
    }

    private fun chart(o: JsonObject): ChatCard? {
        val kindWord = o.first("kind", "chart", "chartType").str()
        val typeWord = o["type"].str()
        val kind = chartKindOf(kindWord) ?: chartKindOf(typeWord) ?: ChartKind.BAR
        val donut = kind == ChartKind.PIE && (o["donut"].bool() ||
            kindWord?.contains("donut", true) == true || typeWord?.contains("donut", true) == true)
        val xLabel = o.first("x_label", "xLabel").str()
        val yLabel = o.first("y_label", "yLabel").str()
        val seriesObjects = o.first("series", "datasets").objects()
        val labels = o["labels"].array().take(40).map { it.str() ?: "" }
        if (kind == ChartKind.SCATTER) {
            val series = seriesObjects.take(6).mapNotNull { s ->
                val pts = s["points"].array().take(200).mapNotNull { point(it) }
                if (pts.isEmpty()) null else ChatCard.Chart.Series(s.first("name", "label").str() ?: "", emptyList(), pts)
            }
            if (series.isEmpty()) return null
            return ChatCard.Chart(kind, labels, series, o.first("unit").str(), o.first("title").str(), false, xLabel, yLabel)
        }
        if (labels.isEmpty()) return null
        var series = seriesObjects.take(if (kind == ChartKind.PIE) 1 else 6).mapNotNull { s ->
            val data = s["data"].array().take(labels.size).map { it.num() }
            if (data.isEmpty() || data.any { it == null }) return@mapNotNull null
            ChatCard.Chart.Series(s.first("name", "label").str() ?: "", data.filterNotNull())
        }
        if (series.isEmpty()) return null
        if (kind == ChartKind.PIE) {
            val d = series.first().data
            if (d.any { it < 0.0 } || d.sum() <= 0.0) return null
            series = series.take(1)
        }
        return ChatCard.Chart(kind, labels, series, o.first("unit").str(), o.first("title").str(), donut, xLabel, yLabel)
    }

    private fun places(o: JsonObject): ChatCard? {
        val items = o.first("items", "places", "restaurants", "hotels", "venues", "results").array().take(8).mapNotNull { el ->
            if (el is JsonPrimitive) return@mapNotNull el.str()?.let { ChatCard.Places.Item(it) }
            val p = el as? JsonObject ?: return@mapNotNull null
            val name = p.first("name", "title", "label", "place").str() ?: return@mapNotNull null
            val url = p.first("url", "link", "website").str()?.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
            ChatCard.Places.Item(
                name = name,
                subtitle = p.first("subtitle", "category", "address", "type").str(),
                description = p.first("description", "note", "detail", "text").str(),
                tags = p["tags"].strings(4),
                rating = p.first("rating", "score", "stars").num()?.takeIf { it.isFinite() },
                price = p.first("price", "cost", "price_range").str(),
                image = p.first("image", "photo", "image_query").str()?.takeUnless { it.startsWith("http", true) },
                url = url,
            )
        }
        return if (items.isEmpty()) null else ChatCard.Places(o.first("title").str(), items)
    }

    private fun images(o: JsonObject): ChatCard? {
        val items = o.first("items", "images", "photos", "gallery").array().take(8).mapNotNull { el ->
            if (el is JsonPrimitive) return@mapNotNull el.str()?.takeUnless { it.startsWith("http", true) }?.let { ChatCard.Images.Item(it) }
            val p = el as? JsonObject ?: return@mapNotNull null
            val q = p.first("query", "q", "search", "title", "name").str()?.takeUnless { it.startsWith("http", true) }
                ?: return@mapNotNull null
            ChatCard.Images.Item(q, p.first("caption", "description", "alt").str())
        }
        return if (items.isEmpty()) null else ChatCard.Images(items)
    }

    private fun weather(o: JsonObject): ChatCard? {
        val location = o.first("location", "city", "place", "name").str() ?: return null
        val days = (o.first("days", "forecast_days").num()?.toInt() ?: 5).coerceIn(1, 7)
        return ChatCard.Weather(location, days)
    }

    private fun budget(o: JsonObject): ChatCard? {
        val items = o.first("items", "costs", "lines", "expenses").objects().take(10).mapNotNull { b ->
            val label = b.first("label", "name", "title", "category").str() ?: return@mapNotNull null
            val amount = b.first("amount", "value", "cost", "price").num()?.takeIf { it.isFinite() } ?: return@mapNotNull null
            ChatCard.Budget.Item(
                label = label,
                icon = b.first("icon", "emoji").icon(),
                amount = amount,
                min = b["min"].num()?.takeIf { it.isFinite() },
                max = b["max"].num()?.takeIf { it.isFinite() },
                note = b.first("note", "detail", "description").str(),
            )
        }
        if (items.isEmpty()) return null
        return ChatCard.Budget(
            title = o.first("title").str(),
            currency = o.first("currency", "unit").str()?.take(8).orEmpty(),
            people = o.first("people", "persons", "travelers").num()?.toInt()?.takeIf { it >= 1 },
            cap = o.first("cap", "budget", "limit").num()?.takeIf { it.isFinite() && it > 0 },
            items = items,
        )
    }
}

private fun mapOfNotNull(vararg pairs: Pair<String, JsonElement?>): Map<String, JsonElement> =
    pairs.mapNotNull { (k, v) -> v?.let { k to it } }.toMap()
