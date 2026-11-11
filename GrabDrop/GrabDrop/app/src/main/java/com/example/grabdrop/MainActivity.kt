package com.example.grabdrop

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import java.io.File
import kotlin.concurrent.thread

/** Shared between the activity (Share target) and the service. */
object Carry { @Volatile var shared: File? = null }

/** Live info shown on the app screen so we can see what the camera sees. */
object Debug {
    @Volatile var running = false
    @Volatile var frames = 0
    @Volatile var hand = "-"
    @Volatile var error = ""
}

class MainActivity : ComponentActivity() {
    private lateinit var status: TextView
    private lateinit var info: TextView
    private val h = android.os.Handler(android.os.Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            info.text = "Service: " + (if (Debug.running) "running" else "off") +
                "\nCamera frames: " + Debug.frames + "\nHand seen: " + Debug.hand +
                (if (Debug.error.isNotEmpty()) "\nERROR: " + Debug.error else "")
            h.postDelayed(this, 400)
        }
    }
    override fun onResume() { super.onResume(); h.post(tick) }
    override fun onPause() { super.onPause(); h.removeCallbacks(tick) }

    private val askPerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r[Manifest.permission.CAMERA] == true) {
            ContextCompat.startForegroundService(this, Intent(this, GestureService::class.java))
            status.text = "✅ Running. Show an open palm, then a fist, to the front camera."
        } else status.text = "Camera permission is required."
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        status = TextView(this).apply { textSize = 16f; text = "Tap Start. Then grab with your hand." }
        val start = Button(this).apply { text = "Start Grab & Drop"; setOnClickListener { requestAndStart() } }
        val stop = Button(this).apply {
            text = "Stop"
            setOnClickListener { stopService(Intent(this@MainActivity, GestureService::class.java)); status.text = "Stopped." }
        }
        info = TextView(this).apply { textSize = 15f; setPadding(0, 40, 0, 0) }
        val test = Button(this).apply {
            text = "Test grab (no gesture)"
            setOnClickListener { ContextCompat.startForegroundService(this@MainActivity, Intent(this@MainActivity, GestureService::class.java).setAction("GRAB")) }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(48, 96, 48, 48)
            addView(TextView(this@MainActivity).apply { text = "✋ Grab & Drop"; textSize = 26f })
            addView(status); addView(start); addView(test); addView(stop); addView(info)
        })
        handleShare(intent)
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handleShare(intent) }

    private fun requestAndStart() {
        val p = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33) { p += Manifest.permission.POST_NOTIFICATIONS; p += Manifest.permission.READ_MEDIA_IMAGES }
        else p += Manifest.permission.READ_EXTERNAL_STORAGE
        askPerms.launch(p.toTypedArray())
    }

    /** Anything you "Share → Grab & Drop" becomes the item that the next grab carries. */
    private fun handleShare(i: Intent?) {
        if (i?.action != Intent.ACTION_SEND) return
        @Suppress("DEPRECATION") val u = i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) ?: return
        thread {
            val f = File(cacheDir, u.lastPathSegment?.substringAfterLast('/') ?: "shared")
            contentResolver.openInputStream(u)?.use { a -> f.outputStream().use { a.copyTo(it) } }
            Carry.shared = f
            runOnUiThread { status.text = "Ready to carry: ${f.name}" }
        }
    }
}
