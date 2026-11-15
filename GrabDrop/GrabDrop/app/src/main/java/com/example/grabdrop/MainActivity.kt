package com.example.grabdrop

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import java.io.File
import kotlin.concurrent.thread

/** UI only. Camera, gestures and transfers live in GestureService; shared state lives in Hub. */
class MainActivity : ComponentActivity() {
    private lateinit var status: TextView
    private lateinit var handLabel: TextView
    private lateinit var fileList: TextView
    private lateinit var logView: TextView
    private lateinit var badge: TextView
    private lateinit var codeView: TextView
    private lateinit var bar: ProgressBar
    private lateinit var toggle: Button
    private lateinit var receiveBtn: Button

    private val picker = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        uris.forEach { Hub.items.add(it to nameOf(it)) }
        if (uris.isNotEmpty()) Hub.say("${Hub.items.size} file(s) ready. Open palm → fist to grab.") else refresh()
    }
    private val perms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (granted(Manifest.permission.CAMERA)) GestureService.start(this) else Hub.say("Camera permission is needed")
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun startWatching() {
        val need = mutableListOf<String>()
        if (!granted(Manifest.permission.CAMERA)) need.add(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) need.add(Manifest.permission.POST_NOTIFICATIONS)
        if (need.isEmpty()) GestureService.start(this) else perms.launch(need.toTypedArray())
    }

    private fun cmd(action: String) {
        if (!Hub.running) { Hub.say("Tap Start watching first"); return }
        startService(Intent(this, GestureService::class.java).setAction(action))
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val cBg = Color.parseColor(if (dark) "#14161A" else "#F6F7F9")
        val cCard = Color.parseColor(if (dark) "#1E2127" else "#FFFFFF")
        val cText = Color.parseColor(if (dark) "#ECEEF1" else "#1A1D21")
        val cMuted = Color.parseColor(if (dark) "#9AA1AD" else "#6B7280")
        val cAccent = Color.parseColor(if (dark) "#818CF8" else "#4F46E5")
        val cOk = Color.parseColor(if (dark) "#4ADE80" else "#16A34A")
        window.decorView.setBackgroundColor(cBg)
        @Suppress("DEPRECATION") run { window.statusBarColor = cBg }

        fun text(t: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
            text = t; textSize = size; setTextColor(color); if (bold) setTypeface(Typeface.DEFAULT_BOLD)
        }
        fun button(t: String, filled: Boolean, f: () -> Unit) = Button(this).apply {
            text = t; isAllCaps = false; textSize = 14f; stateListAnimator = null
            setTextColor(if (filled) Color.WHITE else cAccent)
            background = GradientDrawable().apply {
                cornerRadius = px(10).toFloat()
                if (filled) setColor(cAccent) else { setColor(Color.TRANSPARENT); setStroke(px(1), cAccent) }
            }
            setOnClickListener { f() }
        }
        fun LinearLayout.put(v: View, top: Int = 8) = addView(v, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(top) })

        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(16), px(40), px(16), px(90)) }
        fun card(title: String): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(cCard); cornerRadius = px(14).toFloat() }
            elevation = px(2).toFloat(); setPadding(px(14), px(14), px(14), px(14))
            addView(text(title, 16f, cText, true))
            content.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(12) })
        }

        content.addView(text("✋ Grab & Drop", 22f, cText, true))

        // Connection
        val c1 = card("Connection")
        status = text("", 15f, cText, true); c1.put(status)
        c1.put(text("Phone → laptop: open palm, then fist. Laptop → phone: copy files on the laptop, fist there, then fist → open palm here. Every transfer needs your approval.", 13f, cMuted))
        codeView = text("", 16f, cAccent, true).apply { visibility = View.GONE; setTextIsSelectable(true) }
        c1.put(codeView)
        toggle = button("Start watching", true) { if (Hub.running) GestureService.stop(this) else startWatching() }
        c1.put(toggle, 10)

        // Files
        val c2 = card("Files")
        val row = LinearLayout(this)
        fun addBtn(t: String, filled: Boolean, f: () -> Unit) =
            button(t, filled, f).also { row.addView(it, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = px(6) }) }
        addBtn("📁 Choose", true) { picker.launch("*/*") }
        addBtn("Grab", false) { cmd(GestureService.GRAB) }
        addBtn("Cancel", false) { cmd(GestureService.CANCEL) }
        receiveBtn = addBtn("Receive", false) { cmd(GestureService.FETCH) }
        c2.put(row)
        fileList = text("", 14f, cText); c2.put(fileList)
        c2.put(CheckBox(this).apply {
            text = "Keep a copy after sending"; setTextColor(cMuted); buttonTintList = ColorStateList.valueOf(cAccent)
            setOnCheckedChangeListener { _, v -> Hub.keepCopy = v }
        }, 4)
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; visibility = View.GONE; progressTintList = ColorStateList.valueOf(cOk)
        }
        c2.put(bar)

        // Gestures
        val c3 = card("Hand gestures")
        handLabel = text("", 16f, cText, true); c3.put(handLabel)
        val holdText = text("Hold time: 0.5 s", 13f, cMuted); c3.put(holdText)
        c3.put(SeekBar(this).apply {
            max = 13; progress = 3
            progressTintList = ColorStateList.valueOf(cAccent); thumbTintList = ColorStateList.valueOf(cAccent)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) {
                    Hub.holdMs = 200L + p * 100L; holdText.text = "Hold time: ${Hub.holdMs / 1000.0} s"
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }, 0)

        // Activity
        val c4 = card("Activity")
        logView = text("", 13f, cMuted); c4.put(logView)

        badge = text("", 15f, Color.WHITE, true).apply {
            setPadding(px(16), px(8), px(16), px(8)); visibility = View.GONE
            background = GradientDrawable().apply { setColor(cOk); cornerRadius = px(20).toFloat() }
        }
        setContentView(FrameLayout(this).apply {
            addView(ScrollView(this@MainActivity).apply { addView(content) }, FrameLayout.LayoutParams(-1, -1))
            addView(badge, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, px(16), px(16)) })
        })

        handleShare(intent)
        if (b == null && !Hub.running) startWatching()
    }

    override fun onStart() { super.onStart(); Hub.onChange = { runOnUiThread { refresh() } }; refresh() }
    override fun onStop() { Hub.onChange = null; super.onStop() }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handleShare(intent) }

    private fun refresh() {
        status.text = Hub.status
        handLabel.text = Hub.handText
        logView.text = Hub.logText()
        fileList.text = if (Hub.items.isEmpty()) "No files chosen" else Hub.items.joinToString("\n") { icon(it.second) + "  " + it.second }
        codeView.text = Hub.codeText; codeView.visibility = if (Hub.codeText.isEmpty()) View.GONE else View.VISIBLE
        bar.visibility = if (Hub.progress >= 0) View.VISIBLE else View.GONE; bar.progress = Hub.progress.coerceAtLeast(0)
        toggle.text = if (Hub.running) "Stop watching" else "Start watching"
        receiveBtn.text = if (Hub.remoteName != null) "Receive ●" else "Receive"
        val n = Hub.carrying?.size
        badge.visibility = if (n != null) View.VISIBLE else View.GONE
        badge.text = "✊ Carrying " + (n ?: 0)
    }

    private fun nameOf(u: Uri): String =
        contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: u.lastPathSegment ?: "file"

    private fun icon(n: String): String = when (n.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg", "png", "gif", "webp", "heic" -> "🖼"
        "mp4", "mkv", "mov", "webm" -> "🎬"
        "mp3", "wav", "m4a", "aac" -> "🎵"
        "pdf" -> "📕"
        else -> "📄"
    }

    private fun handleShare(i: Intent?) {
        if (i?.action != Intent.ACTION_SEND) return
        @Suppress("DEPRECATION") val u = i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) ?: return
        thread {
            val f = File(cacheDir, nameOf(u))
            contentResolver.openInputStream(u)?.use { a -> f.outputStream().use { a.copyTo(it) } }
            Hub.items.add(Uri.fromFile(f) to f.name); Hub.say("Shared file added: " + f.name)
        }
    }
}
