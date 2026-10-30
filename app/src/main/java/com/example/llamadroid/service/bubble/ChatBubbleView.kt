package com.example.llamadroid.service.bubble

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

/** Round chat bubble with a speech-bubble glyph and an optional red "new reply" dot. */
class ChatBubbleView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE53935.toInt() }
    private val dotRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val rect = RectF()
    private val tail = Path()

    var accentColor: Int = 0
        set(value) {
            field = value
            fill.color = value
            invalidate()
        }

    var showDot: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    val sizePx: Int get() = (SIZE_DP * density).toInt()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(sizePx, sizePx)
    }

    override fun onDraw(canvas: Canvas) {
        val s = width.toFloat()
        canvas.drawCircle(s / 2f, s / 2f, s / 2f, fill)
        // Speech bubble: rounded rect plus a small tail at the lower left.
        rect.set(s * 0.26f, s * 0.30f, s * 0.74f, s * 0.62f)
        canvas.drawRoundRect(rect, s * 0.08f, s * 0.08f, glyph)
        tail.reset()
        tail.moveTo(s * 0.34f, s * 0.60f)
        tail.lineTo(s * 0.34f, s * 0.75f)
        tail.lineTo(s * 0.50f, s * 0.60f)
        tail.close()
        canvas.drawPath(tail, glyph)
        if (showDot) {
            val r = s * 0.16f
            val cx = s - r - s * 0.02f
            val cy = r + s * 0.02f
            canvas.drawCircle(cx, cy, r, dotRing)
            canvas.drawCircle(cx, cy, r * 0.75f, dot)
        }
    }

    companion object {
        const val SIZE_DP = 56
    }
}
