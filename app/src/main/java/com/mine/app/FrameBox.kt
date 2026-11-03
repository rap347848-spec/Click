package com.mine.app

import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.view.*
import android.widget.TextView

fun lp(w: Int, h: Int, x: Int, y: Int, touch: Boolean) = WindowManager.LayoutParams(
    w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
    (if (touch) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) or
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
    PixelFormat.TRANSLUCENT
).apply { gravity = Gravity.TOP or Gravity.START; this.x = x; this.y = y }

/** إطار فارغ (شفاف من الداخل) + مقبضان صغيران خارج الإطار: ✥ للتحريك و ⇲ للتكبير والتصغير */
class FrameBox(
    val ctx: Context, val wm: WindowManager, val color: Int,
    x0: Int, y0: Int, w0: Int, h0: Int, val sw: Int, val sh: Int
) {
    val d = ctx.resources.displayMetrics.density
    @Volatile var x = x0
    @Volatile var y = y0
    @Volatile var w = w0
    @Volatile var h = h0
    var onChange: () -> Unit = {}
    private val hs = (36 * d).toInt()
    private val minS = (60 * d).toInt()
    private val col = color

    private val frame = object : View(ctx) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        override fun onDraw(c: Canvas) {
            val o = 3 * d
            p.strokeWidth = 6 * d; p.color = Color.BLACK
            c.drawRect(o, o, width - o, height - o, p)
            p.strokeWidth = 3.5f * d; p.color = col
            c.drawRect(o, o, width - o, height - o, p)
        }
    }
    private fun handle(t: String) = TextView(ctx).apply {
        text = t; textSize = 16f; gravity = Gravity.CENTER
        setTextColor(if (col == Color.WHITE) Color.BLACK else Color.WHITE)
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL; setColor(col); setStroke((2 * d).toInt(), Color.BLACK)
        }
    }
    private val move = handle("✥")
    private val resize = handle("⇲")
    private val fp = lp(w0, h0, x0, y0, false)
    private val mp = lp(hs, hs, x0 - hs, y0 - hs, true)
    private val rp = lp(hs, hs, x0 + w0, y0 + h0, true)

    init {
        wm.addView(frame, fp); wm.addView(move, mp); wm.addView(resize, rp)
        drag(move, { x to y }) { bx, by, dx, dy -> x = bx + dx; y = by + dy; layout() }
        drag(resize, { w to h }) { bw, bh, dx, dy -> w = bw + dx; h = bh + dy; layout() }
        layout(false)
    }

    private fun drag(v: View, base: () -> Pair<Int, Int>, f: (Int, Int, Int, Int) -> Unit) {
        var sx = 0f; var sy = 0f; var b = 0 to 0
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; b = base() }
                MotionEvent.ACTION_MOVE -> f(b.first, b.second, (e.rawX - sx).toInt(), (e.rawY - sy).toInt())
            }
            true
        }
    }

    private fun layout(notify: Boolean = true) {
        w = w.coerceIn(minS, sw - hs * 2); h = h.coerceIn(minS, sh - hs * 2)
        x = x.coerceIn(hs, sw - w - hs); y = y.coerceIn(hs, sh - h - hs)
        fp.x = x; fp.y = y; fp.width = w; fp.height = h
        mp.x = x - hs; mp.y = y - hs
        rp.x = x + w; rp.y = y + h
        wm.updateViewLayout(frame, fp); wm.updateViewLayout(move, mp); wm.updateViewLayout(resize, rp)
        if (notify) onChange()
    }

    fun remove() { for (v in listOf(frame, move, resize)) try { wm.removeView(v) } catch (_: Exception) {} }
}
