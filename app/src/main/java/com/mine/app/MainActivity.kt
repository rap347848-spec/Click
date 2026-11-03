package com.mine.app

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (Build.VERSION.SDK_INT >= 33)
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1)
        val l = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(48, 48, 48, 48)
        }
        l.addView(TextView(this).apply { text = "ماين"; textSize = 32f; gravity = Gravity.CENTER })
        fun btn(t: String, f: () -> Unit) = l.addView(Button(this).apply { text = t; setOnClickListener { f() } })
        btn("1) السماح بالعرض فوق التطبيقات") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        btn("2) تفعيل خدمة النقر (إمكانية الوصول)") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        btn("3) تشغيل المراقب") {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "فعّل إذن العرض فوق التطبيقات أولاً", Toast.LENGTH_LONG).show()
            } else {
                val m = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                startActivityForResult(m.createScreenCaptureIntent(), 100)
            }
        }
        setContentView(l)
    }

    override fun onActivityResult(rq: Int, rc: Int, data: Intent?) {
        super.onActivityResult(rq, rc, data)
        if (rq == 100 && rc == RESULT_OK && data != null) {
            val i = Intent(this, OverlayService::class.java).putExtra("code", rc).putExtra("data", data)
            startForegroundService(i)
            moveTaskToBack(true)
        }
    }
}
