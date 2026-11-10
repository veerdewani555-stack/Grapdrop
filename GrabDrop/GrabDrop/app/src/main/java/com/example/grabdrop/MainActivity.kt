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

class MainActivity : ComponentActivity() {
    private lateinit var status: TextView

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
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(48, 96, 48, 48)
            addView(TextView(this@MainActivity).apply { text = "✋ Grab & Drop"; textSize = 26f })
            addView(status); addView(start); addView(stop)
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
