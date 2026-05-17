package com.example.llamadroid.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import com.example.llamadroid.MainActivity
import com.example.llamadroid.R
import com.example.llamadroid.tama.data.ActivityType
import com.example.llamadroid.tama.data.GrowthStage
import com.example.llamadroid.tama.data.LocationType
import com.example.llamadroid.tama.data.PetSpeciesLine
import com.example.llamadroid.tama.data.TamaDecorCatalog
import com.example.llamadroid.tama.data.TamaPet
import com.example.llamadroid.tama.data.TamaRoomCatalog
import com.example.llamadroid.tama.data.TamaWorkCatalog
import com.example.llamadroid.tama.data.localizedName
import com.example.llamadroid.tama.data.mapPetActionToSpriteState
import com.example.llamadroid.tama.data.resolvePetSpriteAssetPath
import com.example.llamadroid.tama.db.TamaDatabase
import com.example.llamadroid.tama.game.PetMapper
import com.example.llamadroid.tama.rpg.AdventureGateCombatEngine
import com.example.llamadroid.tama.rpg.AdventureGateProfile
import com.example.llamadroid.tama.rpg.AdventureGateRepository
import com.example.llamadroid.ui.navigation.Screen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private data class TamaWidgetPetState(
    val pet: TamaPet?,
    val adventureGateProfile: AdventureGateProfile?
)

class TamaPetWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        refreshAll(context.applicationContext)
    }

    override fun onEnabled(context: Context) {
        refreshAll(context.applicationContext)
    }

    override fun onDisabled(context: Context) {
        cancelScheduledRefresh(context.applicationContext)
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_REFRESH) {
            refreshAll(context.applicationContext)
            return
        }
        super.onReceive(context, intent)
    }

    companion object {
        private const val ACTION_REFRESH = "com.example.llamadroid.widget.action.REFRESH_TAMA_PET"
        private const val REQUEST_OPEN_BASE = 620_000
        private const val REQUEST_REFRESH = 620_901
        private const val ACTIVE_REFRESH_MS = 10_000L
        private const val IDLE_REFRESH_MS = 5 * 60_000L
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun refreshAll(context: Context) {
            val appContext = context.applicationContext
            val manager = AppWidgetManager.getInstance(appContext)
            val ids = widgetIds(appContext, manager)
            if (ids.isEmpty()) {
                cancelScheduledRefresh(appContext)
                return
            }
            scope.launch {
                val state = loadActivePetState(appContext)
                ids.forEach { updateWidget(appContext, manager, it, state) }
                scheduleNextRefresh(appContext, state.pet)
            }
        }

        fun hasWidgets(context: Context): Boolean {
            val appContext = context.applicationContext
            val manager = AppWidgetManager.getInstance(appContext)
            return widgetIds(appContext, manager).isNotEmpty()
        }

        fun updateWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            scope.launch {
                val state = loadActivePetState(context)
                updateWidget(context, appWidgetManager, appWidgetId, state)
                scheduleNextRefresh(context.applicationContext, state.pet)
            }
        }

        private suspend fun loadActivePetState(context: Context): TamaWidgetPetState {
            val database = TamaDatabase.getInstance(context)
            val pet = database.tamaDao().getActivePet()?.let(PetMapper::toDomain)
            val profile = pet?.let { activePet ->
                runCatching {
                    AdventureGateRepository(database).recoverProfile(activePet.id)
                }.getOrNull()
            }?.let(AdventureGateCombatEngine::normalizedProfile)
            return TamaWidgetPetState(pet = pet, adventureGateProfile = profile)
        }

        private fun updateWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int,
            state: TamaWidgetPetState
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_tama_pet)
            views.setOnClickPendingIntent(R.id.widget_root, openTama(context, appWidgetId))
            val pet = state.pet
            if (pet == null) {
                fillEmptyState(context, views)
            } else {
                fillPetState(context, views, pet, state.adventureGateProfile)
            }
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        private fun widgetIds(context: Context, manager: AppWidgetManager): IntArray =
            manager.getAppWidgetIds(ComponentName(context, TamaPetWidgetProvider::class.java))

        private fun scheduleNextRefresh(context: Context, pet: TamaPet?) {
            val intervalMs = if (pet?.currentActivity == ActivityType.WORKING ||
                pet?.currentActivity == ActivityType.STUDYING ||
                pet?.currentActivity == ActivityType.RELAXING
            ) {
                ACTIVE_REFRESH_MS
            } else {
                IDLE_REFRESH_MS
            }
            val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
            alarmManager.set(
                AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + intervalMs,
                refreshPendingIntent(context)
            )
        }

        private fun cancelScheduledRefresh(context: Context) {
            val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
            alarmManager.cancel(refreshPendingIntent(context))
        }

        private fun refreshPendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, TamaPetWidgetProvider::class.java).apply {
                action = ACTION_REFRESH
            }
            return PendingIntent.getBroadcast(
                context,
                REQUEST_REFRESH,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun openTama(context: Context, appWidgetId: Int): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                putExtra(MainActivity.EXTRA_OPEN_ROUTE, Screen.Tama.route)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            return PendingIntent.getActivity(
                context,
                REQUEST_OPEN_BASE + appWidgetId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun fillEmptyState(context: Context, views: RemoteViews) {
            views.setTextViewText(R.id.widget_tama_title, context.getString(R.string.widget_tama_title))
            views.setTextViewText(R.id.widget_tama_status, context.getString(R.string.widget_tama_no_pet_title))
            views.setTextViewText(R.id.widget_tama_detail, context.getString(R.string.widget_tama_no_pet_body))
            views.setViewVisibility(R.id.widget_tama_timer_row, View.GONE)
            views.setViewVisibility(R.id.widget_tama_adventure_bars, View.GONE)
            views.setViewVisibility(R.id.widget_tama_no_pet, View.VISIBLE)
            views.setTextViewText(R.id.widget_tama_no_pet, context.getString(R.string.widget_tama_no_pet_title))
            views.setImageViewBitmap(R.id.widget_tama_scene, TamaPetWidgetSceneRenderer.renderEmpty(context))
        }

        private fun fillPetState(
            context: Context,
            views: RemoteViews,
            pet: TamaPet,
            adventureGateProfile: AdventureGateProfile?
        ) {
            val scene = TamaPetWidgetSceneRenderer.render(context, pet)
            val placeLabel = placeLabel(context, pet)
            val statusLabel = statusLabel(context, pet, placeLabel)
            views.setTextViewText(R.id.widget_tama_title, pet.name.ifBlank { context.getString(R.string.widget_tama_title) })
            views.setTextViewText(R.id.widget_tama_status, statusLabel)
            views.setTextViewText(
                R.id.widget_tama_detail,
                activityGainText(context, pet) ?: statsDetailText(context, pet)
            )
            views.setTextViewText(R.id.widget_tama_timer_label, timerLabel(context, pet))
            views.setViewVisibility(R.id.widget_tama_no_pet, View.GONE)
            views.setImageViewBitmap(R.id.widget_tama_scene, scene)
            fillAdventureGateBars(context, views, adventureGateProfile)

            val startTime = activeStartTime(pet)
            if (startTime != null) {
                val base = SystemClock.elapsedRealtime() - (System.currentTimeMillis() - startTime).coerceAtLeast(0L)
                views.setViewVisibility(R.id.widget_tama_timer_row, View.VISIBLE)
                views.setChronometer(R.id.widget_tama_timer, base, null, true)
            } else {
                views.setViewVisibility(R.id.widget_tama_timer_row, View.GONE)
                views.setChronometer(R.id.widget_tama_timer, SystemClock.elapsedRealtime(), null, false)
            }
        }

        private fun fillAdventureGateBars(
            context: Context,
            views: RemoteViews,
            profile: AdventureGateProfile?
        ) {
            if (profile == null) {
                views.setViewVisibility(R.id.widget_tama_adventure_bars, View.GONE)
                return
            }
            val maxHp = profile.stats.maxHp.coerceAtLeast(1)
            val maxMana = profile.stats.maxMana.coerceAtLeast(1)
            val currentHp = profile.currentHp.coerceIn(0, maxHp)
            val currentMana = profile.currentMana.coerceIn(0, maxMana)
            views.setViewVisibility(R.id.widget_tama_adventure_bars, View.VISIBLE)
            views.setTextViewText(R.id.widget_tama_adventure_title, context.getString(R.string.widget_tama_adventure_title))
            views.setTextViewText(R.id.widget_tama_adventure_hp_label, context.getString(R.string.widget_tama_adventure_hp, currentHp, maxHp))
            views.setTextViewText(R.id.widget_tama_adventure_mana_label, context.getString(R.string.widget_tama_adventure_mana, currentMana, maxMana))
            views.setProgressBar(R.id.widget_tama_adventure_hp_bar, 1000, currentHp * 1000 / maxHp, false)
            views.setProgressBar(R.id.widget_tama_adventure_mana_bar, 1000, currentMana * 1000 / maxMana, false)
        }

        private fun placeLabel(context: Context, pet: TamaPet): String {
            if (pet.currentActivity == ActivityType.WORKING) {
                return TamaWorkCatalog.jobById(pet.currentWorkJobId)
                    ?.let { context.getString(it.titleRes) }
                    ?: LocationType.WORKPLACE.localizedName(context)
            }
            val locationType = resolveLocationType(pet.currentLocationId)
            return if (locationType == LocationType.HOME) {
                TamaRoomCatalog.roomById(pet.homeRoomId)
                    ?.let { context.getString(it.titleRes) }
                    ?: LocationType.HOME.localizedName(context)
            } else {
                locationType.localizedName(context)
            }
        }

        private fun statusLabel(context: Context, pet: TamaPet, placeLabel: String): String {
            if (pet.isSleeping) return context.getString(R.string.widget_tama_status_sleeping)
            return when (pet.currentActivity) {
                ActivityType.WORKING -> context.getString(R.string.widget_tama_status_working, placeLabel)
                ActivityType.STUDYING -> context.getString(R.string.widget_tama_status_studying)
                ActivityType.RELAXING -> context.getString(R.string.widget_tama_status_relaxing)
                ActivityType.NONE -> {
                    if (resolveLocationType(pet.currentLocationId) == LocationType.HOME) {
                        context.getString(R.string.widget_tama_status_in_room, placeLabel)
                    } else {
                        context.getString(R.string.widget_tama_status_at_location, placeLabel)
                    }
                }
            }
        }

        private fun activeStartTime(pet: TamaPet): Long? =
            when {
                pet.isSleeping -> pet.sleepStartTime
                pet.currentActivity != ActivityType.NONE -> pet.activityStartTime
                else -> null
            }

        private fun timerLabel(context: Context, pet: TamaPet): String =
            when (pet.currentActivity) {
                ActivityType.WORKING -> context.getString(R.string.tama_action_work)
                ActivityType.STUDYING -> context.getString(R.string.tama_action_study)
                ActivityType.RELAXING -> context.getString(R.string.tama_action_relax)
                ActivityType.NONE -> context.getString(R.string.widget_tama_timer_label)
            }

        private fun activityGainText(context: Context, pet: TamaPet): String? {
            val startTime = pet.activityStartTime ?: return null
            val durationMs = (System.currentTimeMillis() - startTime).coerceAtLeast(0L)
            val hoursPassed = durationMs / (1000 * 60 * 60f)
            return when (pet.currentActivity) {
                ActivityType.WORKING -> {
                    val hourlyPay = TamaWorkCatalog.jobById(pet.currentWorkJobId)?.hourlyPay ?: 4
                    context.getString(R.string.tama_activity_gain_money, (hoursPassed * hourlyPay).toInt())
                }
                ActivityType.STUDYING -> context.getString(
                    R.string.tama_activity_gain_education,
                    (hoursPassed * 5).toInt()
                )
                ActivityType.RELAXING -> context.getString(
                    R.string.tama_activity_gain_happiness,
                    (hoursPassed * 40).toInt()
                )
                ActivityType.NONE -> null
            }
        }

        private fun statsDetailText(context: Context, pet: TamaPet): String =
            context.getString(
                R.string.widget_tama_detail_stats,
                pet.stats.energy.toInt().coerceIn(0, 100),
                if (pet.isMad) context.getString(R.string.widget_tama_mood_mad) else pet.mood.emoji
            )
    }
}

private object TamaPetWidgetSceneRenderer {
    private const val WIDTH = 480
    private const val HEIGHT = 280
    private const val STUDY_ACTION_ICON_ASSET = "tama/actions/study.png"
    private const val WORK_ACTION_ICON_ASSET = "tama/actions/work.png"
    private const val RELAX_ACTION_ICON_ASSET = "tama/icons/ui/park_tree_relax.png"
    private const val POOP_PROP_ASSET = "tama/decor/poop.png"

    fun renderEmpty(context: Context): Bitmap =
        Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            drawBackground(context, canvas, TamaRoomCatalog.homeRoomAssetPath(TamaRoomCatalog.PRINCIPAL_ROOM_ID))
            drawScrim(canvas)
        }

    fun render(context: Context, pet: TamaPet): Bitmap =
        Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            drawBackground(context, canvas, backgroundAssetFor(pet))
            val homeScene = resolveLocationType(pet.currentLocationId) == LocationType.HOME &&
                !pet.isSleeping &&
                pet.currentActivity == ActivityType.NONE
            if (homeScene) drawHomeDecor(context, canvas, pet)
            drawPoopIfNeeded(context, canvas, pet)
            drawPet(context, canvas, pet, actionFor(pet))
            drawActivityProp(context, canvas, pet)
        }

    private fun backgroundAssetFor(pet: TamaPet): String {
        if (pet.isSleeping) return "tama/backgrounds/bedroom.png"
        return when (pet.currentActivity) {
            ActivityType.WORKING -> TamaWorkCatalog.jobById(pet.currentWorkJobId)?.backgroundAssetPath
                ?: "tama/backgrounds/workplace.png"
            ActivityType.STUDYING -> "tama/backgrounds/classroom.png"
            ActivityType.RELAXING -> "tama/backgrounds/park.png"
            ActivityType.NONE -> when (resolveLocationType(pet.currentLocationId)) {
                LocationType.HOME -> TamaRoomCatalog.homeRoomAssetPath(pet.homeRoomId)
                LocationType.SCHOOL -> "tama/backgrounds/classroom.png"
                LocationType.WORKPLACE -> "tama/backgrounds/workplace.png"
                LocationType.SHOP -> "tama/backgrounds/shop.png"
                LocationType.ARCADE -> "tama/backgrounds/arcade_location.png"
                LocationType.PARK -> "tama/backgrounds/park.png"
                LocationType.HOSPITAL -> "tama/backgrounds/hospital.png"
                LocationType.ALCHEMIST -> "tama/backgrounds/alchemist.png"
                LocationType.FARM -> "tama/backgrounds/farm.png"
                LocationType.DUNGEON -> "tama/backgrounds/dungeon.png"
                LocationType.ADVENTURE_GATE -> "tama/backgrounds/adventure_gate.png"
            }
        }
    }

    private fun actionFor(pet: TamaPet): String =
        when {
            pet.isSleeping -> "sleeping"
            pet.currentActivity == ActivityType.WORKING -> "working"
            pet.currentActivity == ActivityType.STUDYING -> "studying"
            pet.currentActivity == ActivityType.RELAXING -> "relaxing"
            else -> "idle"
        }

    private fun drawBackground(context: Context, canvas: Canvas, assetPath: String) {
        val bitmap = decodeAsset(context, assetPath)
        if (bitmap == null) {
            canvas.drawColor(Color.rgb(32, 35, 45))
            return
        }
        val dest = RectF(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat())
        val source = centerCropSource(bitmap, dest)
        canvas.drawBitmap(bitmap, source, dest, bitmapPaint(filter = true))
    }

    private fun drawScrim(canvas: Canvas) {
        canvas.drawColor(Color.argb(72, 10, 12, 20))
    }

    private fun drawHomeDecor(context: Context, canvas: Canvas, pet: TamaPet) {
        TamaDecorCatalog.decorById(pet.leftDecorationId)?.let { decor ->
            drawAsset(context, canvas, decor.assetPath, RectF(24f, 156f, 132f, 264f))
        }
        TamaDecorCatalog.decorById(pet.rightDecorationId)?.let { decor ->
            drawAsset(context, canvas, decor.assetPath, RectF(348f, 156f, 456f, 264f))
        }
    }

    private fun drawPoopIfNeeded(context: Context, canvas: Canvas, pet: TamaPet) {
        if (pet.poopCount <= 0 || resolveLocationType(pet.currentLocationId) != LocationType.HOME) return
        val rects = listOf(
            RectF(170f, 222f, 208f, 260f),
            RectF(212f, 222f, 250f, 260f),
            RectF(170f, 184f, 208f, 222f),
            RectF(212f, 184f, 250f, 222f)
        )
        repeat(pet.poopCount.coerceAtMost(rects.size)) { index ->
            drawAsset(context, canvas, POOP_PROP_ASSET, rects[index])
        }
    }

    private fun drawPet(context: Context, canvas: Canvas, pet: TamaPet, action: String) {
        val speciesLine = PetSpeciesLine.fromSpeciesId(pet.species, pet.genetics.bodyStyle)
        val spriteState = mapPetActionToSpriteState(action, pet.isSleeping)
        val frameIndex = if (spriteState.frameCount > 1) {
            ((System.currentTimeMillis() / 600L) % spriteState.frameCount).toInt()
        } else {
            0
        }
        val assetPath = resolvePetSpriteAssetPath(speciesLine, pet.stage, spriteState, frameIndex)
        val size = when (pet.stage) {
            GrowthStage.EGG -> 118f
            GrowthStage.BABY -> 150f
            else -> 184f
        }
        val centerX = if (pet.currentActivity == ActivityType.WORKING) 276f else 240f
        val bottom = if (pet.isSleeping) 252f else 266f
        drawAsset(
            context = context,
            canvas = canvas,
            assetPath = assetPath,
            target = RectF(centerX - size / 2f, bottom - size, centerX + size / 2f, bottom)
        )
    }

    private fun drawActivityProp(context: Context, canvas: Canvas, pet: TamaPet) {
        when {
            pet.currentActivity == ActivityType.STUDYING -> {
                drawAsset(context, canvas, STUDY_ACTION_ICON_ASSET, RectF(306f, 150f, 408f, 252f))
            }
            pet.currentActivity == ActivityType.WORKING -> {
                drawAsset(context, canvas, WORK_ACTION_ICON_ASSET, RectF(84f, 154f, 194f, 264f))
            }
            pet.currentActivity == ActivityType.RELAXING -> {
                drawAsset(context, canvas, RELAX_ACTION_ICON_ASSET, RectF(320f, 140f, 418f, 238f))
            }
        }
    }

    private fun drawAsset(context: Context, canvas: Canvas, assetPath: String, target: RectF) {
        val bitmap = decodeAsset(context, assetPath) ?: return
        canvas.drawBitmap(bitmap, null, target, bitmapPaint(filter = false))
    }

    private fun decodeAsset(context: Context, assetPath: String): Bitmap? =
        runCatching {
            context.assets.open(assetPath).use { stream ->
                BitmapFactory.decodeStream(stream)
            }
        }.getOrNull()

    private fun centerCropSource(bitmap: Bitmap, dest: RectF): Rect {
        val sourceRatio = bitmap.width.toFloat() / bitmap.height.toFloat()
        val destRatio = dest.width() / dest.height()
        return if (sourceRatio > destRatio) {
            val width = (bitmap.height * destRatio).toInt()
            val left = (bitmap.width - width) / 2
            Rect(left, 0, left + width, bitmap.height)
        } else {
            val height = (bitmap.width / destRatio).toInt()
            val top = (bitmap.height - height) / 2
            Rect(0, top, bitmap.width, top + height)
        }
    }

    private fun bitmapPaint(filter: Boolean): Paint =
        Paint().apply {
            isAntiAlias = filter
            isFilterBitmap = filter
            isDither = true
        }
}

private fun resolveLocationType(locationId: String?): LocationType {
    val raw = locationId?.trim().orEmpty().lowercase()
    return when {
        raw == "fixed_1_0" || raw.contains("shop") -> LocationType.SHOP
        raw == "fixed_2_0" || raw.contains("park") -> LocationType.PARK
        raw == "fixed_3_0" || raw.contains("hospital") -> LocationType.HOSPITAL
        raw == "fixed_4_0" || raw.contains("arcade") -> LocationType.ARCADE
        raw == "fixed_0_1" || raw.contains("alchemist") -> LocationType.ALCHEMIST
        raw == "fixed_1_1" || raw.contains("school") -> LocationType.SCHOOL
        raw == "fixed_2_1" || raw.contains("work") || raw.contains("office") -> LocationType.WORKPLACE
        raw == "fixed_3_1" || raw.contains("farm") -> LocationType.FARM
        raw == "fixed_0_2" || raw == "fixed_4_2" || raw.contains("dungeon") -> LocationType.DUNGEON
        raw == "fixed_2_2" || raw.contains("adventure") -> LocationType.ADVENTURE_GATE
        else -> LocationType.HOME
    }
}
