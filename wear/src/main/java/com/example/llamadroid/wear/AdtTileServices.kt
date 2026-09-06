package com.example.llamadroid.wear

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.DimensionBuilders.wrap
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.ResourceBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.serialization.KSerializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

private const val PET_AVATAR_RESOURCE_ID = "adt_pet_avatar"
private const val PET_TILE_IMAGE_SIZE_PX = 96
private const val PET_TILE_RESOURCE_SCHEMA_VERSION = 3
private const val TILE_BG = 0xFF050816.toInt()
private const val TILE_PANEL = 0xFF111827.toInt()
private const val TILE_CARD = 0xFF1F2937.toInt()
private const val TILE_TEXT = 0xFFFFFFFF.toInt()
private const val TILE_MUTED = 0xFFC7D0DD.toInt()
private const val TILE_ACCENT = 0xFF93C5FD.toInt()
private const val TILE_GOOD = 0xFF86EFAC.toInt()
private const val TILE_WARN = 0xFFFACC15.toInt()
private const val TILE_DANGER = 0xFFFCA5A5.toInt()
private const val TILE_LLM = 0xFF60A5FA.toInt()
private const val TILE_CHAT = 0xFFC084FC.toInt()
private const val TILE_CALENDAR = 0xFFFB923C.toInt()
private const val TILE_PET = 0xFF34D399.toInt()
private const val TILE_TASK = 0xFF22D3EE.toInt()
private const val TILE_NOTE = 0xFFFACC15.toInt()

abstract class AdtBaseTileService : TileService() {
    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> =
        immediateFuture(
            TileBuilders.Tile.Builder()
                .setResourcesVersion(tileResourcesVersion())
                .setFreshnessIntervalMillis(60_000L)
                .setTileTimeline(
                    TimelineBuilders.Timeline.Builder()
                        .addTimelineEntry(
                            TimelineBuilders.TimelineEntry.Builder()
                                .setLayout(
                                    LayoutElementBuilders.Layout.Builder()
                                        .setRoot(buildTile(AdtTileCache(this)))
                                        .build()
                                )
                                .build()
                        )
                        .build()
                )
                .build()
        )

    override fun onResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
        immediateFuture(
            ResourceBuilders.Resources.Builder()
                .setVersion(tileResourcesVersion())
                .addIdToImageMapping(
                    PET_AVATAR_RESOURCE_ID,
                    petAvatarResource()
                )
                .build()
        )

    protected abstract fun buildTile(cache: AdtTileCache): LayoutElementBuilders.LayoutElement

    private fun tileResourcesVersion(): String {
        val pet = AdtTileCache(this).pet()
        return "pet-$PET_TILE_RESOURCE_SCHEMA_VERSION-${pet?.revisioned?.revision ?: 0L}-${pet?.spriteAssetId?.hashCode() ?: 0}"
    }

    private fun petAvatarResource(): ResourceBuilders.ImageResource {
        val spritePath = AdtTileCache(this).pet()?.spriteAssetId
        val inlineSprite = spritePath?.let(::loadInlinePetSprite)
        return ResourceBuilders.ImageResource.Builder().apply {
            if (inlineSprite != null) {
                setInlineResource(inlineSprite)
            } else {
                setAndroidResourceByResId(
                    ResourceBuilders.AndroidImageResourceByResId.Builder()
                        .setResourceId(R.drawable.ic_launcher_foreground)
                        .build()
                )
            }
        }.build()
    }

    // The legacy tiles builder's IntDef omits the ARGB format supported by its
    // ProtoLayout wire schema. Keep the same RGBA payload and named schema value.
    @android.annotation.SuppressLint("WrongConstant")
    private fun loadInlinePetSprite(assetPath: String): ResourceBuilders.InlineImageResource? = runCatching {
        val source = assets.open(assetPath).use(BitmapFactory::decodeStream) ?: return@runCatching null
        val scaled = source.scaleToTileImage()
        val pixels = IntArray(scaled.width * scaled.height)
        scaled.getPixels(pixels, 0, scaled.width, 0, 0, scaled.width, scaled.height)
        // ProtoLayout expects raw ARGB_8888 pixels in RGBA byte order. Bitmap#getPixels
        // returns packed Android ARGB ints, so writing them directly swaps the channels.
        val data = ByteArray(pixels.size * Int.SIZE_BYTES)
        pixels.forEachIndexed { index, pixel ->
            val offset = index * Int.SIZE_BYTES
            data[offset] = (pixel shr 16).toByte()
            data[offset + 1] = (pixel shr 8).toByte()
            data[offset + 2] = pixel.toByte()
            data[offset + 3] = (pixel ushr 24).toByte()
        }
        ResourceBuilders.InlineImageResource.Builder()
            .setData(data)
            .setWidthPx(scaled.width)
            .setHeightPx(scaled.height)
            .setFormat(androidx.wear.protolayout.ResourceBuilders.IMAGE_FORMAT_ARGB_8888)
            .build()
    }.getOrNull()

    private fun Bitmap.scaleToTileImage(): Bitmap {
        val largestSide = maxOf(width, height)
        if (largestSide <= PET_TILE_IMAGE_SIZE_PX) return this
        val scale = PET_TILE_IMAGE_SIZE_PX.toFloat() / largestSide
        return Bitmap.createScaledBitmap(
            this,
            (width * scale).toInt().coerceAtLeast(1),
            (height * scale).toInt().coerceAtLeast(1),
            true
        )
    }

    protected fun tileColumn(vararg children: LayoutElementBuilders.LayoutElement): LayoutElementBuilders.LayoutElement =
        tileColumnInternal(null, false, children.toList())

    protected fun centeredTileColumn(vararg children: LayoutElementBuilders.LayoutElement): LayoutElementBuilders.LayoutElement =
        tileColumnInternal(null, true, children.toList())

    protected fun tappableTileColumn(
        activityClass: Class<*>,
        vararg children: LayoutElementBuilders.LayoutElement
    ): LayoutElementBuilders.LayoutElement = tileColumnInternal(activityClass, false, children.toList())

    private fun tileColumnInternal(
        activityClass: Class<*>?,
        verticallyCentered: Boolean,
        children: List<LayoutElementBuilders.LayoutElement>
    ): LayoutElementBuilders.LayoutElement {
        val modifiers = ModifiersBuilders.Modifiers.Builder()
            .setBackground(ModifiersBuilders.Background.Builder().setColor(argb(TILE_BG)).build())
            .setPadding(
                ModifiersBuilders.Padding.Builder()
                    .setStart(dp(18f))
                    .setEnd(dp(18f))
                    .setTop(dp(if (verticallyCentered) 16f else 28f))
                    .setBottom(dp(12f))
                    .build()
            )
        activityClass?.let {
            modifiers.setClickable(
                ModifiersBuilders.Clickable.Builder()
                    .setId(it.simpleName)
                    .setOnClick(launch(it))
                    .build()
            )
        }
        return LayoutElementBuilders.Box.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(
                if (verticallyCentered) LayoutElementBuilders.VERTICAL_ALIGN_CENTER else LayoutElementBuilders.VERTICAL_ALIGN_TOP
            )
            .setModifiers(modifiers.build())
            .addContent(tileInnerColumn(children, expandHeight = !verticallyCentered))
            .build()
    }

    private fun tileInnerColumn(
        children: List<LayoutElementBuilders.LayoutElement>,
        expandHeight: Boolean = true
    ): LayoutElementBuilders.LayoutElement {
        val column = LayoutElementBuilders.Column.Builder()
            .setWidth(expand())
            .setHeight(if (expandHeight) expand() else wrap())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
        children.forEach { column.addContent(it) }
        return column.build()
    }

    protected fun title(text: String): LayoutElementBuilders.LayoutElement = text(text, 18f, TILE_TEXT, true, 1)

    protected fun tileHeader(
        symbol: String,
        text: String,
        color: Int
    ): LayoutElementBuilders.LayoutElement =
        LayoutElementBuilders.Column.Builder()
            .setWidth(expand())
            .setHeight(wrap())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_START)
            .addContent(
                row(
                    this.text(symbol, 13f, color, true, 1),
                    this.text(text, 17f, TILE_TEXT, true, 1)
                )
            )
            .addContent(spacer(4f))
            .addContent(
                LayoutElementBuilders.Box.Builder()
                    .setWidth(dp(42f))
                    .setHeight(dp(3f))
                    .setModifiers(
                        ModifiersBuilders.Modifiers.Builder()
                            .setBackground(
                                ModifiersBuilders.Background.Builder()
                                    .setColor(argb(color))
                                    .setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(2f)).build())
                                    .build()
                            )
                            .build()
                    )
                    .build()
            )
            .build()

    protected fun body(text: String, maxLines: Int = 2): LayoutElementBuilders.LayoutElement = text(text, 12f, TILE_MUTED, false, maxLines)

    protected fun accent(text: String): LayoutElementBuilders.LayoutElement = text(text, 12f, TILE_ACCENT, false, 1)

    protected fun spacer(size: Float = 6f): LayoutElementBuilders.LayoutElement =
        LayoutElementBuilders.Spacer.Builder().setHeight(dp(size)).build()

    protected fun row(vararg children: LayoutElementBuilders.LayoutElement): LayoutElementBuilders.LayoutElement {
        val row = LayoutElementBuilders.Row.Builder()
            .setWidth(expand())
            .setHeight(wrap())
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
        children.forEachIndexed { index, child ->
            if (index > 0) row.addContent(LayoutElementBuilders.Spacer.Builder().setWidth(dp(4f)).build())
            row.addContent(child)
        }
        return row.build()
    }

    protected fun card(vararg children: LayoutElementBuilders.LayoutElement): LayoutElementBuilders.LayoutElement =
        cardInternal(null, children.toList())

    protected fun coloredCard(
        color: Int,
        vararg children: LayoutElementBuilders.LayoutElement
    ): LayoutElementBuilders.LayoutElement =
        cardInternal(null, children.toList(), backgroundColor = color)

    protected fun coloredCompactCard(
        color: Int,
        vararg children: LayoutElementBuilders.LayoutElement
    ): LayoutElementBuilders.LayoutElement =
        cardInternal(null, children.toList(), padding = 3f, backgroundColor = color)

    protected fun compactCard(vararg children: LayoutElementBuilders.LayoutElement): LayoutElementBuilders.LayoutElement =
        cardInternal(null, children.toList(), padding = 3f)

    protected fun tappableCard(
        activityClass: Class<*>,
        vararg children: LayoutElementBuilders.LayoutElement
    ): LayoutElementBuilders.LayoutElement = cardInternal(activityClass, children.toList())

    private fun cardInternal(
        activityClass: Class<*>?,
        children: List<LayoutElementBuilders.LayoutElement>,
        padding: Float = 7f,
        backgroundColor: Int = TILE_CARD
    ): LayoutElementBuilders.LayoutElement {
        val column = LayoutElementBuilders.Column.Builder()
            .setWidth(expand())
            .setHeight(wrap())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_START)
        children.forEach { column.addContent(it) }
        val modifiers = ModifiersBuilders.Modifiers.Builder()
            .setBackground(
                ModifiersBuilders.Background.Builder()
                    .setColor(argb(backgroundColor))
                    .setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(8f)).build())
                    .build()
            )
            .setPadding(ModifiersBuilders.Padding.Builder().setAll(dp(padding)).build())
        activityClass?.let {
            modifiers.setClickable(
                ModifiersBuilders.Clickable.Builder()
                    .setId(it.simpleName)
                    .setOnClick(launch(it))
                    .build()
            )
        }
        return LayoutElementBuilders.Box.Builder()
            .setWidth(expand())
            .setHeight(wrap())
            .setModifiers(modifiers.build())
            .addContent(column.build())
            .build()
    }

    protected fun button(text: String, activityClass: Class<*>): LayoutElementBuilders.LayoutElement =
        LayoutElementBuilders.Box.Builder()
            .setWidth(wrap())
            .setHeight(dp(34f))
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setBackground(
                        ModifiersBuilders.Background.Builder()
                            .setColor(argb(TILE_PANEL))
                            .setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(16f)).build())
                            .build()
                    )
                    .setPadding(
                        ModifiersBuilders.Padding.Builder()
                            .setStart(dp(12f))
                            .setEnd(dp(12f))
                            .setTop(dp(7f))
                            .setBottom(dp(7f))
                            .build()
                    )
                    .setClickable(
                        ModifiersBuilders.Clickable.Builder()
                            .setId(activityClass.simpleName)
                            .setOnClick(launch(activityClass))
                            .build()
                    )
                    .build()
            )
            .addContent(text(text, 12f, TILE_TEXT, false, 1))
            .build()

    protected fun smallButton(text: String, activityClass: Class<*>): LayoutElementBuilders.LayoutElement =
        LayoutElementBuilders.Box.Builder()
            .setWidth(wrap())
            .setHeight(dp(26f))
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setBackground(
                        ModifiersBuilders.Background.Builder()
                            .setColor(argb(TILE_PANEL))
                            .setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(13f)).build())
                            .build()
                    )
                    .setPadding(
                        ModifiersBuilders.Padding.Builder()
                            .setStart(dp(9f))
                            .setEnd(dp(9f))
                            .setTop(dp(5f))
                            .setBottom(dp(5f))
                            .build()
                    )
                    .setClickable(
                        ModifiersBuilders.Clickable.Builder()
                            .setId(activityClass.simpleName)
                            .setOnClick(launch(activityClass))
                            .build()
                    )
                    .build()
            )
            .addContent(text(text, 10f, TILE_TEXT, false, 1))
            .build()

    protected fun accentButton(
        text: String,
        color: Int,
        activityClass: Class<*>
    ): LayoutElementBuilders.LayoutElement =
        LayoutElementBuilders.Box.Builder()
            .setWidth(wrap())
            .setHeight(dp(30f))
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setBackground(
                        ModifiersBuilders.Background.Builder()
                            .setColor(argb(color))
                            .setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(15f)).build())
                            .build()
                    )
                    .setPadding(
                        ModifiersBuilders.Padding.Builder()
                            .setStart(dp(12f))
                            .setEnd(dp(12f))
                            .setTop(dp(6f))
                            .setBottom(dp(6f))
                            .build()
                    )
                    .setClickable(
                        ModifiersBuilders.Clickable.Builder()
                            .setId(activityClass.simpleName)
                            .setOnClick(launch(activityClass))
                            .build()
                    )
                    .build()
            )
            .addContent(text(text, 11f, TILE_BG, true, 1))
            .build()

    protected fun statusPill(text: String, color: Int): LayoutElementBuilders.LayoutElement =
        LayoutElementBuilders.Box.Builder()
            .setWidth(wrap())
            .setHeight(wrap())
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setBackground(
                        ModifiersBuilders.Background.Builder()
                            .setColor(argb(TILE_PANEL))
                            .setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(10f)).build())
                            .build()
                    )
                    .setPadding(
                        ModifiersBuilders.Padding.Builder()
                            .setStart(dp(8f))
                            .setEnd(dp(8f))
                            .setTop(dp(3f))
                            .setBottom(dp(3f))
                            .build()
                    )
                    .build()
            )
            .addContent(text(text, 10f, color, true, 1))
            .build()

    protected fun progressBar(percent: Int?): LayoutElementBuilders.LayoutElement {
        val clamped = percent?.coerceIn(0, 100) ?: 100
        val width = (118f * clamped / 100f).coerceAtLeast(6f)
        return LayoutElementBuilders.Box.Builder()
            .setWidth(dp(118f))
            .setHeight(dp(5f))
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setBackground(
                        ModifiersBuilders.Background.Builder()
                            .setColor(argb(TILE_PANEL))
                            .setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(3f)).build())
                            .build()
                    )
                    .build()
            )
            .addContent(
                LayoutElementBuilders.Box.Builder()
                    .setWidth(dp(width))
                    .setHeight(dp(5f))
                    .setModifiers(
                        ModifiersBuilders.Modifiers.Builder()
                            .setBackground(
                                ModifiersBuilders.Background.Builder()
                                    .setColor(argb(TILE_ACCENT))
                                    .setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(3f)).build())
                                    .build()
                            )
                            .build()
                    )
                    .build()
            )
            .build()
    }

    protected fun image(resourceId: String, size: Float): LayoutElementBuilders.LayoutElement =
        LayoutElementBuilders.Image.Builder()
            .setResourceId(resourceId)
            .setWidth(dp(size))
            .setHeight(dp(size))
            .setContentScaleMode(LayoutElementBuilders.CONTENT_SCALE_MODE_FIT)
            .build()

    protected fun coloredBody(text: String, color: Int, maxLines: Int = 1): LayoutElementBuilders.LayoutElement =
        text(text, 11f, color, false, maxLines)

    protected fun text(value: String, size: Float, color: Int, bold: Boolean, maxLines: Int): LayoutElementBuilders.LayoutElement =
        LayoutElementBuilders.Text.Builder()
            .setText(value)
            .setMaxLines(maxLines)
            .setOverflow(LayoutElementBuilders.TEXT_OVERFLOW_ELLIPSIZE_END)
            .setFontStyle(
                LayoutElementBuilders.FontStyle.Builder()
                    .setSize(sp(size))
                    .setColor(argb(color))
                    .setWeight(if (bold) LayoutElementBuilders.FONT_WEIGHT_BOLD else LayoutElementBuilders.FONT_WEIGHT_NORMAL)
                    .build()
            )
            .build()

    private fun launch(activityClass: Class<*>): ActionBuilders.LaunchAction =
        ActionBuilders.LaunchAction.Builder()
            .setAndroidActivity(
                ActionBuilders.AndroidActivity.Builder()
                    .setPackageName(packageName)
                    .setClassName(activityClass.name)
                    .build()
            )
            .build()
}

class AdtControlTileService : AdtBaseTileService() {
    override fun buildTile(cache: AdtTileCache): LayoutElementBuilders.LayoutElement {
        val server = cache.server()
        val running = server?.state == "running"
        val statusColor = when (server?.state) {
            "running" -> TILE_GOOD
            "starting", "stopping" -> TILE_WARN
            "failed" -> TILE_DANGER
            else -> TILE_MUTED
        }
        return centeredTileColumn(
            tileHeader("●", getString(R.string.tile_control_title), TILE_LLM),
            spacer(8f),
            coloredCard(
                0xFF172554.toInt(),
                statusPill(
                    server?.state?.replaceFirstChar { it.titlecase(Locale.getDefault()) }
                        ?: getString(R.string.wear_disconnected),
                    statusColor
                ),
                spacer(5f),
                body(server?.label ?: getString(R.string.wear_waiting_for_phone), 1),
                server?.port?.let { coloredBody(getString(R.string.tile_server_port, it), TILE_LLM) } ?: spacer(1f)
            ),
            spacer(8f),
            accentButton(
                getString(if (running) R.string.wear_llama_stop else R.string.wear_llama_start),
                if (running) TILE_DANGER else TILE_LLM,
                if (running) StopServerTileActionActivity::class.java else StartServerTileActionActivity::class.java
            )
        )
    }
}

class AdtChatTileService : AdtBaseTileService() {
    override fun buildTile(cache: AdtTileCache): LayoutElementBuilders.LayoutElement {
        val pinned = cache.chats()?.chats.orEmpty().filter { it.pinned }.take(2)
        val rows = mutableListOf<LayoutElementBuilders.LayoutElement>(
            tileHeader("✦", getString(R.string.tile_chat_title), TILE_CHAT),
            spacer(7f),
            accentButton(getString(R.string.tile_chat_quick), TILE_CHAT, QuickChatTileActionActivity::class.java),
            spacer(7f)
        )
        if (pinned.isNotEmpty()) rows += coloredBody(getString(R.string.tile_chat_pinned), TILE_CHAT)
        pinned.forEachIndexed { index, chat ->
            rows += tappableCard(
                if (index == 0) OpenPinnedChatOneTileActivity::class.java else OpenPinnedChatTwoTileActivity::class.java,
                coloredBody(chat.title, TILE_TEXT),
                body(chat.preview.ifBlank { DateFormat.getDateInstance(DateFormat.SHORT).format(Date(chat.lastModifiedEpochMs)) }, 1)
            )
            if (index < pinned.lastIndex) rows += spacer(4f)
        }
        return tileColumn(*rows.toTypedArray())
    }
}

class AdtCalendarTileService : AdtBaseTileService() {
    override fun buildTile(cache: AdtTileCache): LayoutElementBuilders.LayoutElement {
        val events = cache.organizerEvents()?.events.orEmpty()
            .sortedBy { it.startAtEpochMs }
            .take(5)
        val rows = mutableListOf<LayoutElementBuilders.LayoutElement>(
            tileHeader("◆", getString(R.string.wear_calendar), TILE_CALENDAR),
            spacer(5f)
        )
        if (events.isEmpty()) {
            rows += coloredCard(0xFF431407.toInt(), statusPill(getString(R.string.tile_calendar_clear), TILE_CALENDAR))
        } else {
            events.forEachIndexed { index, event ->
                rows += coloredCompactCard(
                    if (index == 0) 0xFF431407.toInt() else TILE_CARD,
                    coloredBody(
                        "${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(event.startAtEpochMs))} · ${event.title}",
                        if (index == 0) TILE_CALENDAR else TILE_MUTED,
                        1
                    )
                )
                if (index < events.lastIndex) rows += spacer(1f)
            }
        }
        return tappableTileColumn(OpenCalendarTileActivity::class.java, *rows.toTypedArray())
    }
}

class AdtPinnedNoteTileService : AdtBaseTileService() {
    override fun buildTile(cache: AdtTileCache): LayoutElementBuilders.LayoutElement {
        AdtTileBridge.sendRpc(
            this,
            AdtWearProtocol.ORGANIZER_PINNED_NOTE,
            PingRequest.serializer(),
            PingRequest(AdtTileBridge.meta()),
            OrganizerPinnedNoteResult.serializer()
        ) { result ->
            result?.let {
                cache.writePinnedNote(it)
                runCatching { TileService.getUpdater(this).requestUpdate(AdtPinnedNoteTileService::class.java) }
            }
        }
        val note = cache.pinnedNote()?.note
        return tileColumn(
            tileHeader("◆", getString(R.string.tile_note_title), TILE_NOTE),
            spacer(4f),
            accentButton(
                getString(if (note == null) R.string.tile_note_choose else R.string.tile_note_change),
                TILE_NOTE,
                OpenPinnedNoteChooserTileActivity::class.java
            ),
            spacer(6f),
            coloredCard(
                0xFF422006.toInt(),
                coloredBody(note?.title ?: getString(R.string.tile_note_none), TILE_NOTE, 1),
                body(note?.content.orEmpty(), 5)
            )
        )
    }
}

class AdtPetTileService : AdtBaseTileService() {
    override fun buildTile(cache: AdtTileCache): LayoutElementBuilders.LayoutElement {
        val pet = cache.pet()
        return tileColumn(
            tileHeader("♥", pet?.name ?: getString(R.string.wear_tama_title), TILE_PET),
            spacer(5f),
            image(PET_AVATAR_RESOURCE_ID, 62f),
            spacer(2f),
            coloredCard(
                0xFF052E2B.toInt(),
                statusPill(pet?.moodLabel ?: getString(R.string.wear_tama_no_pet), TILE_PET),
                body(pet?.activityLabel ?: pet?.stage ?: "", 1)
            ),
            spacer(6f),
            accentButton(getString(R.string.tile_open_pet), TILE_PET, OpenPetTileActivity::class.java)
        )
    }
}

class AdtActiveTaskTileService : AdtBaseTileService() {
    override fun buildTile(cache: AdtTileCache): LayoutElementBuilders.LayoutElement {
        val tasks = cache.activeTasks()?.tasks.orEmpty()
        val rows = mutableListOf<LayoutElementBuilders.LayoutElement>(
            tileHeader("■", getString(R.string.tile_tasks_title), TILE_TASK),
            spacer(6f)
        )
        if (tasks.isEmpty()) {
            rows += coloredCard(
                0xFF083344.toInt(),
                statusPill(getString(R.string.tile_tasks_clear), TILE_TASK),
                body(getString(R.string.wear_tasks_empty), 2)
            )
            rows += spacer(8f)
            rows += accentButton(getString(R.string.tile_open_adt), TILE_TASK, OpenHomeTileActivity::class.java)
        } else {
            tasks.take(2).forEach { task ->
                rows += card(
                    body(task.title, 1),
                    body(task.subtitle.ifBlank { task.stage.ifBlank { task.state } }, 1),
                    progressBar(task.progressPercent),
                    accent(task.progressPercent?.let { "$it%" } ?: task.state)
                )
            }
            rows += spacer()
            rows += row(
                smallButton(getString(R.string.tile_open_tasks), OpenTasksTileActivity::class.java),
                smallButton(getString(R.string.wear_refresh), RefreshTasksTileActionActivity::class.java)
            )
            val first = tasks.firstOrNull()
            if (first?.canCancel == true) rows += smallButton(getString(R.string.wear_cancel), CancelTaskTileActionActivity::class.java)
            if (first?.canPause == true) rows += smallButton(getString(R.string.wear_pause), PauseTaskTileActionActivity::class.java)
            if (first?.canResume == true) rows += smallButton(getString(R.string.wear_resume), ResumeTaskTileActionActivity::class.java)
        }
        return tileColumn(*rows.toTypedArray())
    }
}

class AdtTileCache(context: Context) {
    private val prefs = context.getSharedPreferences("adt_wear_cache_v1", Context.MODE_PRIVATE)

    fun server(): LlamaServerSnapshot? = read("server", LlamaServerSnapshot.serializer())
    fun chats(): ChatListPage? = read("chats", ChatListPage.serializer())
    fun organizerEvents(): OrganizerEventPage? = read("organizer_events", OrganizerEventPage.serializer())
    fun organizerMonth(): OrganizerMonthPage? = read("organizer_month", OrganizerMonthPage.serializer())
    fun pet(): PetSnapshot? = read("pet", PetSnapshot.serializer())
    fun activeTasks(): ActiveTaskSnapshot? = read("active_tasks", ActiveTaskSnapshot.serializer())
    fun pinnedNote(): OrganizerPinnedNoteResult? = read("pinned_note", OrganizerPinnedNoteResult.serializer())
    fun writePinnedNote(value: OrganizerPinnedNoteResult) =
        prefs.edit().putString(
            "pinned_note",
            AdtWearProtocol.json.encodeToString(OrganizerPinnedNoteResult.serializer(), value)
        ).apply()

    private fun <T> read(key: String, serializer: KSerializer<T>): T? =
        prefs.getString(key, null)?.let { runCatching { AdtWearProtocol.json.decodeFromString(serializer, it) }.getOrNull() }
}

object AdtTileBridge {
    fun <TRequest, TResult> sendRpc(
        context: Context,
        path: String,
        requestSerializer: KSerializer<TRequest>,
        request: TRequest,
        resultSerializer: KSerializer<TResult>,
        onResult: (TResult?) -> Unit = {}
    ) {
        val appContext = context.applicationContext
        com.google.android.gms.wearable.Wearable.getCapabilityClient(appContext)
            .getCapability(AdtWearProtocol.PHONE_CAPABILITY, com.google.android.gms.wearable.CapabilityClient.FILTER_REACHABLE)
            .addOnSuccessListener { capability ->
                val node = capability.nodes.firstOrNull { it.isNearby } ?: capability.nodes.firstOrNull()
                if (node == null) {
                    onResult(null)
                    return@addOnSuccessListener
                }
                val bytes = AdtWearProtocol.json.encodeToString(requestSerializer, request).toByteArray(Charsets.UTF_8)
                com.google.android.gms.wearable.Wearable.getMessageClient(appContext)
                    .sendRequest(node.id, path, bytes)
                    .addOnSuccessListener { responseBytes ->
                        val response = runCatching {
                            AdtWearProtocol.json.decodeFromString(
                                RpcResponse.serializer(resultSerializer),
                                responseBytes.toString(Charsets.UTF_8)
                            )
                        }.getOrNull()
                        onResult(response?.result)
                    }
                    .addOnFailureListener { onResult(null) }
            }
            .addOnFailureListener { onResult(null) }
    }

    fun meta(): RpcMeta =
        RpcMeta(UUID.randomUUID().toString(), watchVersionCode = BuildConfig.VERSION_CODE, createdAtEpochMs = System.currentTimeMillis())

    fun refreshTiles(context: Context) {
        listOf(
            AdtControlTileService::class.java,
            AdtChatTileService::class.java,
            AdtCalendarTileService::class.java,
            AdtPetTileService::class.java,
            AdtActiveTaskTileService::class.java,
            AdtPinnedNoteTileService::class.java
        ).forEach { service ->
            runCatching { TileService.getUpdater(context).requestUpdate(service) }
        }
    }

    fun launch(context: Context, route: String, chatId: Long? = null) {
        context.startActivity(
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(MainActivity.EXTRA_OPEN_ROUTE, route)
                chatId?.let { putExtra(MainActivity.EXTRA_CHAT_ID, it) }
            }
        )
    }
}

private fun <T> immediateFuture(value: T): ListenableFuture<T> = object : ListenableFuture<T> {
    override fun addListener(listener: Runnable, executor: Executor) {
        executor.execute(listener)
    }

    override fun cancel(mayInterruptIfRunning: Boolean): Boolean = false
    override fun isCancelled(): Boolean = false
    override fun isDone(): Boolean = true
    override fun get(): T = value
    override fun get(timeout: Long, unit: TimeUnit): T = value
}
