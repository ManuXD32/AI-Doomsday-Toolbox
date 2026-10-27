package com.example.llamadroid.tama.shimeji

import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.example.llamadroid.R
import com.example.llamadroid.tama.data.TamaFoodKind
import kotlin.math.roundToInt

/**
 * Care panel shown next to the pet on a double tap: Feed (food list), Bath, Sleep/Wake, Study
 * (normal or Pomodoro), Relax, Stop and Close. The content scrolls when taller than the screen room.
 * Dismisses on any outside touch or Back.
 */
class ShimejiActionPanel(
    private val context: Context,
    private val windowManager: WindowManager,
    private val listener: Listener
) {
    interface Listener {
        fun onFeedPlain()
        fun onFeedFood(food: ShimejiFood)
        fun onBath()
        fun onSleep()
        fun onWake()
        fun onStudy()
        fun onPomodoro()
        fun onRelax()
        fun onStopActivity()
        fun onHidePet()
    }

    /** What the panel needs to know about the pet to offer the right buttons. */
    data class State(
        val foods: List<ShimejiFood>,
        val isSleeping: Boolean = false,
        val isEgg: Boolean = false,
        /** Working, studying, training or relaxing right now. */
        val hasActivity: Boolean = false
    )

    private val density = context.resources.displayMetrics.density
    private val night = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES
    // Soft Studio tokens (docs/styling-overview.md): surface, onSurface, primary, onPrimary, outline.
    private val surface = if (night) 0xFF171319.toInt() else 0xFFFFF9FF.toInt()
    private val onSurface = if (night) 0xFFEAE0E8.toInt() else 0xFF1E1A20.toInt()
    private val primary = if (night) 0xFFD8B8FF.toInt() else 0xFF65458F.toInt()
    private val onPrimary = if (night) 0xFF3A1D61.toInt() else 0xFFFFFFFF.toInt()
    private val outline = if (night) 0xFF968D98.toInt() else 0xFF7B747E.toInt()

    private var root: PanelRoot? = null
    private var column: LinearLayout? = null
    private var scrollView: CappedScrollView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var state = State(emptyList())

    val isShowing: Boolean get() = root != null

    /**
     * [anchorTop]/[anchorBottom]: pet window span in screen px; [screenW]/[screenH]: full display.
     * The panel opens below the pet when the pet is in the upper half, above it otherwise.
     */
    fun show(anchorCenterX: Int, anchorTop: Int, anchorBottom: Int, screenW: Int, screenH: Int, state: State) {
        dismiss()
        this.state = state
        val placement = placementFor(anchorCenterX, anchorTop, anchorBottom, screenW, screenH)

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (12 * density).roundToInt()
            setPadding(pad, pad, pad, pad)
        }
        val scroll = CappedScrollView(context, placement.capPx).apply {
            isVerticalScrollBarEnabled = true
            addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val panel = PanelRoot(context) { dismiss() }.apply {
            background = GradientDrawable().apply {
                setColor(surface)
                cornerRadius = 16 * density
                setStroke((1 * density).roundToInt().coerceAtLeast(1), outline)
            }
            addView(scroll, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        }
        column = content
        scrollView = scroll
        root = panel
        showMainPage()

        val params = WindowManager.LayoutParams(
            placement.widthPx,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        applyPlacement(params, placement)
        try {
            windowManager.addView(panel, params)
            layoutParams = params
        } catch (_: RuntimeException) {
            root = null
            column = null
            scrollView = null
        }
    }

    /**
     * Moves the open panel to follow the pet (for example after it landed). Keeps the current page and
     * scroll position; the result is always clamped inside the screen. No-op when nothing is showing.
     */
    fun reposition(anchorCenterX: Int, anchorTop: Int, anchorBottom: Int, screenW: Int, screenH: Int) {
        val view = root ?: return
        val params = layoutParams ?: return
        val placement = placementFor(anchorCenterX, anchorTop, anchorBottom, screenW, screenH)
        scrollView?.capPx = placement.capPx
        applyPlacement(params, placement)
        try {
            windowManager.updateViewLayout(view, params)
        } catch (_: RuntimeException) {
            // Window already gone; the next dismiss() cleans up.
        }
    }

    private fun placementFor(anchorCenterX: Int, anchorTop: Int, anchorBottom: Int, screenW: Int, screenH: Int) =
        ShimejiPanelPlacement.compute(
            anchorCenterX = anchorCenterX,
            anchorTop = anchorTop,
            anchorBottom = anchorBottom,
            screenW = screenW,
            screenH = screenH,
            gapPx = (8 * density).roundToInt(),
            preferredWidthPx = (264 * density).roundToInt(),
            minCapPx = (140 * density).roundToInt()
        )

    private fun applyPlacement(params: WindowManager.LayoutParams, placement: ShimejiPanelPlacement) {
        params.width = placement.widthPx
        params.x = placement.x
        params.y = placement.y
        params.gravity = if (placement.below) Gravity.TOP or Gravity.START else Gravity.BOTTOM or Gravity.START
    }

    fun dismiss() {
        val view = root ?: return
        root = null
        column = null
        scrollView = null
        layoutParams = null
        try {
            windowManager.removeViewImmediate(view)
        } catch (_: RuntimeException) {
            // Already detached (for example the overlay permission was revoked).
        }
    }

    private fun showMainPage() {
        val col = column ?: return
        col.removeAllViews()
        col.addView(button(context.getString(R.string.tama_shimeji_panel_feed), filled = true) {
            if (state.foods.isEmpty()) {
                dismiss()
                listener.onFeedPlain()
            } else {
                showFoodPage()
            }
        })
        col.addView(button(context.getString(R.string.tama_shimeji_panel_bath), filled = true) {
            dismiss()
            listener.onBath()
        })
        if (!state.isEgg) {
            if (state.isSleeping) {
                col.addView(button(context.getString(R.string.tama_shimeji_panel_wake), filled = true) {
                    dismiss()
                    listener.onWake()
                })
            } else {
                col.addView(button(context.getString(R.string.tama_shimeji_panel_sleep), filled = true) {
                    dismiss()
                    listener.onSleep()
                })
                if (state.hasActivity) {
                    col.addView(button(context.getString(R.string.tama_shimeji_panel_stop), filled = true) {
                        dismiss()
                        listener.onStopActivity()
                    })
                } else {
                    col.addView(button(context.getString(R.string.tama_shimeji_panel_study), filled = true) { showStudyPage() })
                    col.addView(button(context.getString(R.string.tama_shimeji_panel_relax), filled = true) {
                        dismiss()
                        listener.onRelax()
                    })
                }
            }
        }
        col.addView(button(context.getString(R.string.tama_shimeji_panel_hide), filled = false) {
            dismiss()
            listener.onHidePet()
        })
        col.addView(button(context.getString(R.string.tama_shimeji_panel_close), filled = false) { dismiss() })
    }

    private fun showStudyPage() {
        val col = column ?: return
        col.removeAllViews()
        col.addView(title(context.getString(R.string.tama_shimeji_panel_study_title)))
        col.addView(button(context.getString(R.string.tama_shimeji_panel_study_normal), filled = true) {
            dismiss()
            listener.onStudy()
        })
        col.addView(button(context.getString(R.string.tama_shimeji_panel_study_pomodoro), filled = true) {
            dismiss()
            listener.onPomodoro()
        })
        col.addView(button(context.getString(R.string.tama_shimeji_panel_back), filled = false) { showMainPage() })
    }

    private fun showFoodPage() {
        val col = column ?: return
        col.removeAllViews()
        col.addView(title(context.getString(R.string.tama_shimeji_panel_food_title)))
        state.foods.forEach { food -> col.addView(foodRow(food)) }
        col.addView(button(context.getString(R.string.tama_shimeji_panel_back), filled = false) { showMainPage() })
    }

    private fun title(text: String): View = TextView(context).apply {
        this.text = text
        setTextColor(onSurface)
        textSize = 14f
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setPadding(0, 0, 0, (6 * density).roundToInt())
    }

    /** Food row: name and quantity ("∞" for free foods) on top, kind badge underneath. */
    private fun foodRow(food: ShimejiFood): View {
        val name = TextView(context).apply {
            text = listOf(food.emoji, food.label).filter { it.isNotBlank() }.joinToString(" ")
            textSize = 15f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(onPrimary)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val count = TextView(context).apply {
            text = if (food.unlimited) "\u221E" else context.getString(R.string.tama_shimeji_panel_food_count, food.quantity)
            textSize = 15f
            maxLines = 1
            setTextColor(onPrimary)
            setPadding((8 * density).roundToInt(), 0, 0, 0)
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(name)
            addView(count)
        }
        val kind = TextView(context).apply {
            text = context.getString(kindLabel(food.kind))
            textSize = 11f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(onPrimary)
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = (48 * density).roundToInt()
            val padX = (14 * density).roundToInt()
            val padY = (6 * density).roundToInt()
            setPadding(padX, padY, padX, padY)
            background = GradientDrawable().apply {
                cornerRadius = 24 * density
                setColor(primary)
            }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                dismiss()
                listener.onFeedFood(food)
            }
            addView(header)
            addView(kind)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (8 * density).roundToInt() }
        }
    }

    private fun kindLabel(kind: TamaFoodKind): Int = when (kind) {
        TamaFoodKind.FREE -> R.string.tama_shimeji_food_kind_free
        TamaFoodKind.SHOP -> R.string.tama_shimeji_food_kind_shop
        TamaFoodKind.CROP -> R.string.tama_shimeji_food_kind_crop
        TamaFoodKind.WORLD -> R.string.tama_shimeji_food_kind_world
    }

    private fun button(label: String, filled: Boolean, onClick: () -> Unit): View = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        textSize = 15f
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setTextColor(if (filled) onPrimary else primary)
        minHeight = (48 * density).roundToInt()
        val pad = (12 * density).roundToInt()
        setPadding(pad, 0, pad, 0)
        background = GradientDrawable().apply {
            cornerRadius = 24 * density
            if (filled) setColor(primary) else setStroke((1 * density).roundToInt().coerceAtLeast(1), primary)
        }
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = (8 * density).roundToInt() }
    }

    /** Dismisses on outside touches and Back. */
    private class PanelRoot(context: Context, private val onDismiss: () -> Unit) : FrameLayout(context) {
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.action == MotionEvent.ACTION_OUTSIDE) {
                onDismiss()
                return true
            }
            if (event.action == MotionEvent.ACTION_UP) performClick()
            return super.onTouchEvent(event)
        }

        override fun performClick(): Boolean = super.performClick()

        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                onDismiss()
                return true
            }
            return super.dispatchKeyEvent(event)
        }
    }

    /** ScrollView that never grows past [capPx], so long food lists scroll on small phones. */
    private class CappedScrollView(context: Context, capPx: Int) : ScrollView(context) {
        var capPx: Int = capPx
            set(value) {
                if (field == value) return
                field = value
                requestLayout()
            }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(capPx, MeasureSpec.AT_MOST))
        }
    }
}
