package com.example.llamadroid.tama.shimeji

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import androidx.core.graphics.withScale
import androidx.core.graphics.withTranslation
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Draws the current Shimeji frame (nearest-neighbour) plus an optional speech bubble above it.
 * The view grows upwards/sideways to fit the bubble; the service reads [spriteOffsetX] and
 * [spriteOffsetY] to keep the sprite where [ShimejiMotion] says it is.
 */
class ShimejiView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val spritePaint = Paint().apply { isFilterBitmap = false; isAntiAlias = false }
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bubbleStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val src = Rect()
    private val dst = Rect()
    private val bubbleRect = RectF()
    private val badgeFill = Paint()
    private val badgeStroke = Paint().apply { style = Paint.Style.STROKE }
    private val badgePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val badgeRect = RectF()

    private var art: ShimejiClipArt? = null
    private var frameIndex = 0
    private var mirror = false
    private var bubbleLayout: StaticLayout? = null
    private var badgeText: String? = null

    var spriteSizePx: Int = (128 * density).roundToInt()
        private set
    var spriteOffsetX: Int = 0
        private set
    var spriteOffsetY: Int = 0
        private set
    private var viewWidthPx = spriteSizePx
    private var viewHeightPx = spriteSizePx

    private val bubblePad = 8 * density
    private val bubbleTail = 8 * density
    private val bubbleMaxWidth = 200 * density
    private val badgePadX = 6 * density
    private val badgePadY = 3 * density
    private val badgeGap = 3 * density

    init {
        val night = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        // Soft Studio surface / onSurface / primary tokens (docs/styling-overview.md).
        bubblePaint.color = if (night) 0xFF171319.toInt() else 0xFFFFF9FF.toInt()
        bubbleStroke.color = if (night) 0xFFD8B8FF.toInt() else 0xFF65458F.toInt()
        bubbleStroke.strokeWidth = 2 * density
        textPaint.color = if (night) 0xFFEAE0E8.toInt() else 0xFF1E1A20.toInt()
        textPaint.textSize = 13 * density
        // Pixel-style timer pill: square corners, hard 2 dp outline, monospace digits (stable width).
        badgeFill.color = bubblePaint.color
        badgeStroke.color = bubbleStroke.color
        badgeStroke.strokeWidth = 2 * density
        badgePaint.color = textPaint.color
        badgePaint.textSize = 11 * density
        setBackgroundColor(Color.TRANSPARENT)
    }

    fun setSpriteSizePx(px: Int) {
        if (px == spriteSizePx) return
        spriteSizePx = px
        relayout()
    }

    /** Sets the frame to draw; returns true when something visible changed. */
    fun setFrame(newArt: ShimejiClipArt?, index: Int, mirrored: Boolean): Boolean {
        if (newArt === art && index == frameIndex && mirrored == mirror) return false
        art = newArt
        frameIndex = index
        mirror = mirrored
        invalidate()
        return true
    }

    /** Returns true when the view size/offsets changed so the window must be repositioned. */
    fun setBubble(text: String?): Boolean {
        val before = Triple(viewWidthPx, viewHeightPx, bubbleLayout != null)
        bubbleLayout = if (text.isNullOrBlank()) null else {
            val width = (bubbleMaxWidth - 2 * bubblePad).roundToInt()
            StaticLayout.Builder.obtain(text, 0, text.length, textPaint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setMaxLines(3)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build()
        }
        relayout()
        return before != Triple(viewWidthPx, viewHeightPx, bubbleLayout != null)
    }

    val hasBubble: Boolean get() = bubbleLayout != null

    /**
     * Sets the timer pill drawn above the pet (null hides it). Returns true when the view size changed
     * so the window must be repositioned.
     */
    fun setBadge(text: String?): Boolean {
        val value = text?.takeIf { it.isNotBlank() }
        if (value == badgeText) return false
        val before = Pair(viewWidthPx, viewHeightPx)
        badgeText = value
        relayout()
        return before != Pair(viewWidthPx, viewHeightPx)
    }

    val hasBadge: Boolean get() = badgeText != null

    private fun badgeWidth(): Float = badgeText?.let { badgePaint.measureText(it) + 2 * badgePadX } ?: 0f

    private fun badgeHeight(): Float =
        if (badgeText == null) 0f else badgePaint.fontMetrics.let { it.descent - it.ascent } + 2 * badgePadY

    private fun badgeBlock(): Float = if (badgeText == null) 0f else badgeHeight() + badgeGap

    private fun relayout() {
        val layout = bubbleLayout
        val bubbleW = layout?.let { layoutWidth(it) + 2 * bubblePad } ?: 0f
        val bubbleH = layout?.let { it.height + 2 * bubblePad + bubbleTail } ?: 0f
        viewWidthPx = max(spriteSizePx.toFloat(), max(bubbleW, badgeWidth())).roundToInt()
        viewHeightPx = (spriteSizePx + bubbleH + badgeBlock()).roundToInt()
        spriteOffsetX = ((viewWidthPx - spriteSizePx) / 2f).roundToInt()
        spriteOffsetY = viewHeightPx - spriteSizePx
        requestLayout()
        invalidate()
    }

    private fun layoutWidth(layout: StaticLayout): Float {
        var widest = 0f
        for (line in 0 until layout.lineCount) widest = max(widest, layout.getLineWidth(line))
        return widest
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(viewWidthPx, viewHeightPx)
    }

    override fun onDraw(canvas: Canvas) {
        val bubble = bubbleLayout
        if (bubble != null) {
            val w = layoutWidth(bubble) + 2 * bubblePad
            val h = bubble.height + 2 * bubblePad
            val left = (viewWidthPx - w) / 2f
            bubbleRect.set(left, 0f, left + w, h)
            canvas.drawRoundRect(bubbleRect, 12 * density, 12 * density, bubblePaint)
            canvas.drawRoundRect(bubbleRect, 12 * density, 12 * density, bubbleStroke)
            canvas.withTranslation(left + bubblePad, bubblePad) { bubble.draw(this) }
        }
        badgeText?.let { text ->
            val w = badgeWidth()
            val h = badgeHeight()
            val left = (viewWidthPx - w) / 2f
            val top = spriteOffsetY - badgeBlock()
            badgeRect.set(left, top, left + w, top + h)
            badgeRect.inset(density, density)
            canvas.drawRect(badgeRect, badgeFill)
            canvas.drawRect(badgeRect, badgeStroke)
            canvas.drawText(text, left + badgePadX, top + badgePadY - badgePaint.fontMetrics.ascent, badgePaint)
        }
        val current = art ?: return
        current.srcRect(frameIndex, src)
        dst.set(spriteOffsetX, spriteOffsetY, spriteOffsetX + spriteSizePx, spriteOffsetY + spriteSizePx)
        if (mirror) {
            canvas.withScale(-1f, 1f, dst.exactCenterX(), 0f) {
                drawBitmap(current.sheet, src, dst, spritePaint)
            }
        } else {
            canvas.drawBitmap(current.sheet, src, dst, spritePaint)
        }
    }

    /** True when the point (view coordinates) lies on the sprite square, not the bubble area. */
    fun hitsSprite(x: Float, y: Float): Boolean =
        x >= spriteOffsetX && x <= spriteOffsetX + spriteSizePx && y >= spriteOffsetY && y <= spriteOffsetY + spriteSizePx

    override fun performClick(): Boolean = super.performClick()
}
