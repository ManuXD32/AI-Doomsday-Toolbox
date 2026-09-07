package com.example.llamadroid.widget

import android.app.AlarmManager
import android.app.Activity
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
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.llamadroid.LlamaApplication
import com.example.llamadroid.MainActivity
import com.example.llamadroid.R
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.tama.data.CropDefinitions
import com.example.llamadroid.tama.data.FARM_MAX_FARM_PAGES
import com.example.llamadroid.tama.data.FarmTile
import com.example.llamadroid.tama.data.PlantedCrop
import com.example.llamadroid.tama.data.TileStatus
import com.example.llamadroid.tama.data.farmPageCountForFarmlandLevel
import com.example.llamadroid.tama.data.farmTileIdsForPage
import com.example.llamadroid.tama.data.FARMLAND_UPGRADE_ID
import com.example.llamadroid.tama.db.TamaDatabase
import com.example.llamadroid.tama.game.FarmEngine
import com.example.llamadroid.tama.game.FarmRepository
import com.example.llamadroid.tama.game.PetMapper
import com.example.llamadroid.ui.components.AppScreenScaffold
import com.example.llamadroid.ui.components.AppSectionCard
import com.example.llamadroid.ui.navigation.Screen
import com.example.llamadroid.ui.theme.LlamaDroidTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.min

class TamaFarmWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        refreshAll(context.applicationContext)
    }

    override fun onEnabled(context: Context) {
        refreshAll(context.applicationContext)
    }

    override fun onDisabled(context: Context) {
        cancelScheduledRefresh(context.applicationContext)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        appWidgetIds.forEach { TamaFarmWidgetPrefs.remove(context, it) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_REFRESH) {
            refreshAll(context.applicationContext)
            return
        }
        super.onReceive(context, intent)
    }

    companion object {
        private const val ACTION_REFRESH = "com.example.llamadroid.widget.action.REFRESH_TAMA_FARM"
        private const val REQUEST_OPEN_BASE = 621_000
        private const val REQUEST_REFRESH = 621_901
        private const val GROWING_REFRESH_MS = 60_000L
        private const val IDLE_REFRESH_MS = 15 * 60_000L
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
                val state = loadFarmState(appContext)
                ids.forEach { updateWidget(appContext, manager, it, state) }
                scheduleNextRefresh(appContext, state)
            }
        }

        private suspend fun loadFarmState(context: Context): TamaFarmWidgetState {
            val database = TamaDatabase.getInstance(context)
            val pet = database.tamaDao().getActivePet()?.let(PetMapper::toDomain)
                ?: return TamaFarmWidgetState(null, emptyList(), unlockedPageCount = 1)
            val repository = FarmRepository(database.farmDao(), context)
            if (!pet.cycleFrozen) {
                FarmEngine(repository).updateFarm(pet.id)
            }
            val farmlandUpgrade = repository.getUpgrade(pet.id, FARMLAND_UPGRADE_ID)
            val farmlandLevel = farmlandUpgrade?.takeIf { it.isPurchased }?.level ?: 0
            return TamaFarmWidgetState(
                petName = pet.name,
                tiles = repository.ensureUnlockedFarmTiles(pet.id).sortedBy { it.id },
                unlockedPageCount = farmPageCountForFarmlandLevel(farmlandLevel)
            )
        }

        private fun updateWidget(
            context: Context,
            manager: AppWidgetManager,
            appWidgetId: Int,
            state: TamaFarmWidgetState
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_tama_farm)
            views.setOnClickPendingIntent(R.id.widget_farm_root, openTama(context, appWidgetId))
            val selectedPage = TamaFarmWidgetPrefs.getPage(context, appWidgetId)
            val petName = state.petName
            if (petName == null) {
                views.setTextViewText(R.id.widget_farm_title, context.getString(R.string.widget_tama_farm_title))
                views.setTextViewText(R.id.widget_farm_summary, context.getString(R.string.widget_tama_farm_no_pet))
                views.setViewVisibility(R.id.widget_farm_empty, View.VISIBLE)
                views.setTextViewText(R.id.widget_farm_empty, context.getString(R.string.widget_tama_farm_no_pet))
                views.setImageViewBitmap(R.id.widget_farm_grid, TamaFarmWidgetRenderer.render(context, emptyList(), pageIndex = 0))
            } else {
                val pageUnlocked = selectedPage < state.unlockedPageCount
                val pageTileIds = farmTileIdsForPage(selectedPage).toSet()
                val pageTiles = if (pageUnlocked) state.tiles.filter { it.id in pageTileIds } else emptyList()
                val crops = pageTiles.mapNotNull { it.crop }
                val ready = crops.count { it.stage >= 3 && !it.isDecayed }
                val growing = crops.count { it.stage < 3 && !it.isDecayed }
                views.setTextViewText(R.id.widget_farm_title, petName)
                views.setTextViewText(
                    R.id.widget_farm_summary,
                    if (pageUnlocked) {
                        context.getString(R.string.widget_tama_farm_summary_with_page, selectedPage + 1, growing, ready)
                    } else {
                        context.getString(R.string.widget_tama_farm_locked_summary, selectedPage + 1)
                    }
                )
                views.setViewVisibility(R.id.widget_farm_empty, if (!pageUnlocked || crops.isEmpty()) View.VISIBLE else View.GONE)
                views.setTextViewText(
                    R.id.widget_farm_empty,
                    if (pageUnlocked) context.getString(R.string.widget_tama_farm_empty) else context.getString(R.string.widget_tama_farm_locked, selectedPage + 1)
                )
                views.setImageViewBitmap(R.id.widget_farm_grid, TamaFarmWidgetRenderer.render(context, pageTiles, pageIndex = selectedPage))
            }
            manager.updateAppWidget(appWidgetId, views)
        }

        private fun widgetIds(context: Context, manager: AppWidgetManager): IntArray =
            manager.getAppWidgetIds(ComponentName(context, TamaFarmWidgetProvider::class.java))

        private fun scheduleNextRefresh(context: Context, state: TamaFarmWidgetState) {
            val hasGrowing = state.tiles.any { it.crop?.let { crop -> crop.stage < 3 && !crop.isDecayed } == true }
            val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
            alarmManager.set(
                AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + if (hasGrowing) GROWING_REFRESH_MS else IDLE_REFRESH_MS,
                refreshPendingIntent(context)
            )
        }

        private fun cancelScheduledRefresh(context: Context) {
            val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
            alarmManager.cancel(refreshPendingIntent(context))
        }

        private fun refreshPendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, TamaFarmWidgetProvider::class.java).apply { action = ACTION_REFRESH }
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
    }
}

private data class TamaFarmWidgetState(
    val petName: String?,
    val tiles: List<FarmTile>,
    val unlockedPageCount: Int
)

private object TamaFarmWidgetPrefs {
    private const val PREFS_NAME = "tama_farm_widgets"
    private const val PREF_PAGE_PREFIX = "page_"

    fun setPage(context: Context, appWidgetId: Int, pageIndex: Int) {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt("$PREF_PAGE_PREFIX$appWidgetId", pageIndex.coerceIn(0, FARM_MAX_FARM_PAGES - 1))
            .apply()
    }

    fun getPage(context: Context, appWidgetId: Int): Int =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt("$PREF_PAGE_PREFIX$appWidgetId", 0)
            .coerceIn(0, FARM_MAX_FARM_PAGES - 1)

    fun remove(context: Context, appWidgetId: Int) {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove("$PREF_PAGE_PREFIX$appWidgetId")
            .apply()
    }
}

class TamaFarmWidgetConfigActivity : ComponentActivity() {
    private var appWidgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LlamaApplication.updateLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(Activity.RESULT_CANCELED)

        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        setContent {
            val appearanceSettings = remember { SettingsRepository(applicationContext) }
            val themeMode = appearanceSettings.themeMode.collectAsState().value
            val dynamicColor = appearanceSettings.dynamicColor.collectAsState().value
            LlamaDroidTheme(themeMode = themeMode, dynamicColor = dynamicColor) {
                TamaFarmWidgetConfigScreen(
                    onCancel = { finish() },
                    onSelectPage = { pageIndex ->
                        TamaFarmWidgetPrefs.setPage(applicationContext, appWidgetId, pageIndex)
                        TamaFarmWidgetProvider.refreshAll(applicationContext)
                        val result = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                        setResult(Activity.RESULT_OK, result)
                        finish()
                    }
                )
            }
        }
    }
}

@Composable
private fun TamaFarmWidgetConfigScreen(
    onCancel: () -> Unit,
    onSelectPage: (Int) -> Unit
) {
    AppScreenScaffold(
        title = androidx.compose.ui.res.stringResource(R.string.widget_tama_farm_config_title),
        subtitle = androidx.compose.ui.res.stringResource(R.string.widget_tama_farm_config_desc),
        onBack = onCancel
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(FARM_MAX_FARM_PAGES) { pageIndex ->
                AppSectionCard(
                    modifier = Modifier.clickable { onSelectPage(pageIndex) }
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = androidx.compose.ui.res.stringResource(R.string.widget_tama_farm_config_page_title, pageIndex + 1),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = androidx.compose.ui.res.stringResource(R.string.widget_tama_farm_config_page_desc, pageIndex + 1),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = androidx.compose.ui.res.stringResource(R.string.widget_tama_farm_config_select),
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

private object TamaFarmWidgetRenderer {
    private const val SIZE = 512
    private const val CROP_DECAY_AFTER_MATURE_MS = 15 * 60 * 60 * 1000L

    fun render(context: Context, tiles: List<FarmTile>, pageIndex: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        drawAssetCover(context, canvas, "farm/Others/farm_field_background.png", RectF(0f, 0f, SIZE.toFloat(), SIZE.toFloat()), paint)
        paint.color = Color.argb(46, 55, 28, 28)
        canvas.drawRect(0f, 0f, SIZE.toFloat(), SIZE.toFloat(), paint)

        val pad = 38f
        val gap = 9f
        val cell = (SIZE - pad * 2 - gap * 2) / 3f
        val byId = tiles.associateBy { it.id }
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(76, 255, 241, 172)
        canvas.drawRoundRect(RectF(pad - 12f, pad - 12f, SIZE - pad + 12f, SIZE - pad + 12f), 28f, 28f, paint)
        repeat(9) { index ->
            val row = index / 3
            val col = index % 3
            val left = pad + col * (cell + gap)
            val top = pad + row * (cell + gap)
            val tileId = pageIndex * 9 + index
            drawTile(context, canvas, paint, RectF(left, top, left + cell, top + cell), byId[tileId])
        }
        return bitmap
    }

    private fun drawTile(context: Context, canvas: Canvas, paint: Paint, rect: RectF, tile: FarmTile?) {
        val crop = tile?.crop
        drawAssetCover(context, canvas, "farm/Others/soil.png", rect, paint)
        if (tile?.status == TileStatus.WET_FARMLAND) {
            paint.style = Paint.Style.FILL
            paint.color = Color.argb(58, 44, 19, 8)
            canvas.drawRoundRect(rect, 10f, 10f, paint)
        }
        if (tile?.status != TileStatus.SOIL) {
            drawFurrows(canvas, paint, rect, wet = tile?.status == TileStatus.WET_FARMLAND)
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        paint.color = Color.argb(145, 255, 219, 110)
        canvas.drawRoundRect(rect, 10f, 10f, paint)
        paint.style = Paint.Style.FILL

        if (crop == null) {
            return
        }

        val cropBitmap = if (crop.isDecayed) {
            loadBitmap(context, "farm/Others/rotten_crop.png")
        } else {
            loadCropBitmap(context, crop)
        }
        cropBitmap?.let {
            val scale = cropScale(crop)
            val iconSize = rect.width() * scale
            val dst = RectF(
                rect.centerX() - iconSize / 2f,
                rect.centerY() - iconSize / 2f - rect.height() * 0.04f,
                rect.centerX() + iconSize / 2f,
                rect.centerY() + iconSize / 2f - rect.height() * 0.04f
            )
            canvas.drawBitmap(it, null, dst, paint)
        }

        if (crop.isFertilized && crop.stage < 3 && !crop.isDecayed) {
            loadBitmap(context, "farm/Others/fertilizer.png")?.let { fertilizer ->
                val badgeSize = rect.width() * 0.24f
                val badgeRect = RectF(rect.left + 5f, rect.bottom - badgeSize - 5f, rect.left + 5f + badgeSize, rect.bottom - 5f)
                canvas.drawBitmap(fertilizer, null, badgeRect, paint)
            }
        }

        val label = cropLabel(context, crop)
        paint.color = Color.WHITE
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = if (label.length > 6) 19f else 22f
        paint.isFakeBoldText = true
        val badge = RectF(rect.centerX() - rect.width() * 0.34f, rect.bottom - 27f, rect.centerX() + rect.width() * 0.34f, rect.bottom - 7f)
        val oldColor = paint.color
        paint.color = Color.argb(142, 0, 0, 0)
        canvas.drawRoundRect(badge, 7f, 7f, paint)
        paint.color = oldColor
        canvas.drawText(label, rect.centerX(), rect.bottom - 11f, paint)
        paint.isFakeBoldText = false
    }

    private fun drawFurrows(canvas: Canvas, paint: Paint, rect: RectF, wet: Boolean) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = rect.width() * 0.035f
        val dark = if (wet) Color.argb(150, 73, 38, 22) else Color.argb(128, 108, 74, 47)
        val light = if (wet) Color.argb(54, 214, 161, 108) else Color.argb(66, 230, 177, 115)
        listOf(0.26f, 0.5f, 0.74f).forEach { fraction ->
            val x = rect.left + rect.width() * fraction
            paint.color = dark
            canvas.drawLine(x, rect.top + 2f, x, rect.bottom - 2f, paint)
            paint.color = light
            canvas.drawLine(x + paint.strokeWidth * 0.48f, rect.top + 2f, x + paint.strokeWidth * 0.48f, rect.bottom - 2f, paint)
        }
        listOf(0.3f, 0.56f, 0.82f).forEach { fraction ->
            val y = rect.top + rect.height() * fraction
            paint.color = dark
            canvas.drawLine(rect.left + 2f, y, rect.right - 2f, y, paint)
            paint.color = light
            canvas.drawLine(rect.left + 2f, y + paint.strokeWidth * 0.48f, rect.right - 2f, y + paint.strokeWidth * 0.48f, paint)
        }
        paint.style = Paint.Style.FILL
    }

    private fun drawAssetCover(context: Context, canvas: Canvas, path: String, rect: RectF, paint: Paint) {
        val bitmap = loadBitmap(context, path)
        if (bitmap == null) {
            paint.color = Color.rgb(93, 143, 81)
            canvas.drawRect(rect, paint)
            return
        }
        canvas.drawBitmap(bitmap, null, rect, paint)
    }

    private fun loadCropBitmap(context: Context, crop: PlantedCrop): Bitmap? =
        loadBitmap(context, "farm/Crops/${stageFolder(crop)}/${crop.type}.png")

    private fun loadBitmap(context: Context, path: String): Bitmap? =
        runCatching { context.assets.open(path).use(BitmapFactory::decodeStream) }.getOrNull()

    private fun cropScale(crop: PlantedCrop): Float = when {
        crop.isDecayed -> 1.02f
        crop.stage <= 0 -> 0.68f
        crop.stage == 1 -> 0.80f
        crop.stage == 2 -> 0.94f
        else -> 1.04f
    }

    private fun stageFolder(crop: PlantedCrop): String = when (crop.stage.coerceIn(0, 3)) {
        0 -> "seed"
        1 -> "stage_1"
        2 -> "stage_2"
        else -> "stage_final"
    }

    private fun cropLabel(context: Context, crop: PlantedCrop): String = when {
        crop.isDecayed -> context.getString(R.string.widget_tama_farm_decayed)
        crop.stage >= 3 -> context.getString(R.string.widget_tama_farm_ready)
        else -> formatRemaining(remainingMs(crop))
    }

    private fun remainingMs(crop: PlantedCrop, now: Long = System.currentTimeMillis()): Long {
        val definition = CropDefinitions.CROPS[crop.type] ?: return 0L
        val elapsed = now - crop.lastStageUpdateTime
        return definition.stageTimes.mapIndexedNotNull { stageIndex, baseTime ->
            if (stageIndex < crop.stage || stageIndex >= 3) {
                null
            } else {
                val adjusted = if (crop.isFertilized) baseTime / 2 else baseTime
                if (stageIndex == crop.stage) maxOf(0L, adjusted - elapsed) else adjusted
            }
        }.sum().let { remaining ->
            if (crop.stage >= 3) {
                (crop.lastStageUpdateTime + CROP_DECAY_AFTER_MATURE_MS - now).coerceAtLeast(0L)
            } else {
                remaining
            }
        }
    }

    private fun formatRemaining(ms: Long): String {
        val minutes = (ms / 60_000L).coerceAtLeast(1L)
        val hours = minutes / 60L
        val mins = minutes % 60L
        return if (hours > 0) {
            "${min(hours, 99)}h ${mins}m"
        } else {
            "${mins}m"
        }
    }
}
