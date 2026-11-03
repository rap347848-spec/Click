package com.mine.app

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.DisplayMetrics
import android.view.*
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

class OverlayService : Service() {
    private val SC = 2          // تصغير الالتقاط للنصف لسرعة المعالجة
    private val THR = 10        // فرق اللون لكل قناة (حساسية فائقة)
    private val MINPIX = 2      // أقل عدد بكسلات متغيرة لاعتبارها حدثاً

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var bg: Handler
    private lateinit var wm: WindowManager
    private var proj: MediaProjection? = null
    private var reader: ImageReader? = null
    private var vd: VirtualDisplay? = null
    private var mon: FrameBox? = null
    private var clk: FrameBox? = null
    private lateinit var bar: LinearLayout
    private lateinit var barP: WindowManager.LayoutParams
    private lateinit var fab: TextView
    private lateinit var fabP: WindowManager.LayoutParams
    private lateinit var toggleBtn: TextView
    private lateinit var countTv: TextView
    private var dens = 1f
    private var cw = 0
    private var ch = 0
    @Volatile private var active = false
    @Volatile private var triggering = false
    @Volatile private var ref: IntArray? = null
    private var refW = 0
    private var clicks = 1
    @Volatile private var remaining = 1
    private var vertical = false

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        if (i == null || proj != null) return START_NOT_STICKY
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("mine", "ماين", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "mine").setContentTitle("ماين يعمل")
            .setSmallIcon(android.R.drawable.ic_menu_view).build()
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(1, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(1, n)

        val t = HandlerThread("mine-bg").also { it.start() }
        bg = Handler(t.looper)
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dm = DisplayMetrics(); @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(dm)
        dens = dm.density
        cw = dm.widthPixels / SC; ch = dm.heightPixels / SC

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        proj = mpm.getMediaProjection(i.getIntExtra("code", 0), i.getParcelableExtra("data")!!)
        proj!!.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, ui)
        reader = ImageReader.newInstance(cw, ch, PixelFormat.RGBA_8888, 2)
        vd = proj!!.createVirtualDisplay("mine", cw, ch, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, null)
        reader!!.setOnImageAvailableListener({ r ->
            val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try { process(img) } finally { img.close() }
        }, bg)

        val sw = dm.widthPixels; val sh = dm.heightPixels
        val s = (dens * 150).toInt()
        mon = FrameBox(this, wm, Color.rgb(0, 120, 255), sw / 2 - s - 20, sh / 3, s, s, sw, sh)
        clk = FrameBox(this, wm, Color.WHITE, sw / 2 + 20, sh / 3, s, s, sw, sh)
        mon!!.onChange = { ref = null }
        buildBar(); buildFab()
        return START_NOT_STICKY
    }

    // ---------- المراقبة ----------
    private fun process(img: Image) {
        val m = mon ?: return
        if (!active || triggering) return
        val pl = img.planes[0]; val buf = pl.buffer; val rs = pl.rowStride; val ps = pl.pixelStride
        val ins = (8 * dens).toInt()
        val x0 = ((m.x + ins) / SC).coerceIn(0, cw - 1); val y0 = ((m.y + ins) / SC).coerceIn(0, ch - 1)
        val x1 = ((m.x + m.w - ins) / SC).coerceIn(0, cw); val y1 = ((m.y + m.h - ins) / SC).coerceIn(0, ch)
        val rw = x1 - x0; val rh = y1 - y0
        if (rw < 4 || rh < 4) return
        val cur = IntArray(rw * rh)
        for (r in 0 until rh) {
            var o = (y0 + r) * rs + x0 * ps
            for (c in 0 until rw) {
                cur[r * rw + c] = ((buf.get(o).toInt() and 255) shl 16) or
                    ((buf.get(o + 1).toInt() and 255) shl 8) or (buf.get(o + 2).toInt() and 255)
                o += ps
            }
        }
        val rf = ref
        if (rf == null || rf.size != cur.size || refW != rw) { ref = cur; refW = rw; return }
        var n = 0
        for (k in cur.indices) {
            val a = cur[k]; val b = rf[k]
            if (abs((a shr 16 and 255) - (b shr 16 and 255)) > THR ||
                abs((a shr 8 and 255) - (b shr 8 and 255)) > THR ||
                abs((a and 255) - (b and 255)) > THR) {
                if (++n >= MINPIX) { triggering = true; ui.post { doClicks() }; return }
            }
        }
    }

    // كل اكتشاف = نقرة واحدة؛ العدد = كم مرة تتكرر الدورة (اكتشاف ثم نقرة)، وبعد انتهائه يتوقف حتى تشغّله
    private fun doClicks() {
        val c = clk ?: return
        val s = ClickService.inst
        if (s == null) Toast.makeText(this, "فعّل خدمة النقر من الإعدادات", Toast.LENGTH_SHORT).show()
        else s.tap((c.x + c.w / 2).toFloat(), (c.y + c.h / 2).toFloat())
        remaining--
        if (remaining <= 0) setActive(false)
        else { countTv.text = "$remaining"; ui.postDelayed({ ref = null; triggering = false }, 700) }
    }

    // ---------- الواجهة ----------
    private fun setActive(a: Boolean) {
        active = a; ref = null; triggering = false
        remaining = clicks; countTv.text = "$clicks"
        toggleBtn.text = if (a) "⏸" else "▶"
        fab.text = if (a) "⏸" else "▶"
        (fab.background as GradientDrawable).setColor(if (a) Color.rgb(200, 40, 40) else Color.rgb(30, 150, 60))
    }

    private fun tv(t: String, size: Float = 22f) = TextView(this).apply {
        text = t; textSize = size; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        val p = (10 * dens).toInt(); setPadding(p, p / 2, p, p / 2)
    }

    private fun buildBar() {
        bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply { setColor(Color.argb(220, 30, 30, 30)); cornerRadius = 20 * dens }
        }
        val grip = tv("☰")
        toggleBtn = tv("▶").also { it.setOnClickListener { setActive(!active) } }
        countTv = tv("1")
        val minus = tv("−").also { it.setOnClickListener { clicks = (clicks - 1).coerceAtLeast(1); countTv.text = "$clicks"; if (active) remaining = clicks } }
        val plus = tv("+").also { it.setOnClickListener { clicks = (clicks + 1).coerceAtMost(10); countTv.text = "$clicks"; if (active) remaining = clicks } }
        val rot = tv("⇄").also {
            it.setOnClickListener {
                vertical = !vertical
                bar.orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
                wm.updateViewLayout(bar, barP)
            }
        }
        val close = tv("✕").also { it.setOnClickListener { stopSelf() } }
        for (v in listOf(grip, toggleBtn, minus, countTv, plus, rot, close)) bar.addView(v)
        barP = lp(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            (20 * dens).toInt(), (80 * dens).toInt(), true)
        wm.addView(bar, barP)
        dragWin(grip, bar, barP)
    }

    private fun buildFab() {
        val s = (56 * dens).toInt()
        fab = tv("▶", 24f).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.rgb(30, 150, 60)) }
        }
        fabP = lp(s, s, (20 * dens).toInt(), (200 * dens).toInt(), true)
        wm.addView(fab, fabP)
        dragWin(fab, fab, fabP) { setActive(!active) }
    }

    private fun dragWin(h: View, w: View, p: WindowManager.LayoutParams, onTap: (() -> Unit)? = null) {
        var sx = 0f; var sy = 0f; var ox = 0; var oy = 0; var moved = false
        h.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = p.x; oy = p.y; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - sx).toInt(); val dy = (e.rawY - sy).toInt()
                    if (abs(dx) > 10 || abs(dy) > 10) moved = true
                    if (moved) { p.x = ox + dx; p.y = oy + dy; wm.updateViewLayout(w, p) }
                }
                MotionEvent.ACTION_UP -> if (!moved) onTap?.invoke()
            }
            true
        }
    }

    override fun onDestroy() {
        active = false
        mon?.remove(); clk?.remove()
        for (v in listOf(bar, fab)) try { wm.removeView(v) } catch (_: Exception) {}
        vd?.release(); reader?.close(); proj?.stop()
        super.onDestroy()
    }
}
