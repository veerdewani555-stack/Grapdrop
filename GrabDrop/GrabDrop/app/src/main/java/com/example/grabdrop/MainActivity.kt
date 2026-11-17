package com.example.grabdrop

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.MotionEvent
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

/** Futuristic UI only. Camera, gestures and transfers live in GestureService; shared state lives in Hub. */
class MainActivity : ComponentActivity() {
    private lateinit var orb: OrbView
    private lateinit var flash: View
    private lateinit var subtitle: TextView
    private lateinit var status: TextView
    private lateinit var handLabel: TextView
    private lateinit var fileList: TextView
    private lateinit var logView: TextView
    private lateinit var codeView: TextView
    private lateinit var bar: ProgressBar
    private lateinit var toggle: Button
    private lateinit var receiveBtn: Button
    private var cardIndex = 0

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
        @Suppress("DEPRECATION") run { window.statusBarColor = Neon.bg; window.navigationBarColor = Neon.bg }
        window.decorView.setBackgroundColor(Neon.bg)
        val mono = Typeface.MONOSPACE
        val monoBold = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)

        fun text(t: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
            text = t; textSize = size; setTextColor(color); typeface = if (bold) monoBold else mono
        }
        fun button(t: String, primary: Boolean, f: () -> Unit) = Button(this).apply {
            text = t; isAllCaps = false; textSize = 13f; typeface = monoBold; stateListAnimator = null
            setTextColor(if (primary) Neon.bg else Neon.cyan)
            val colors = if (primary) intArrayOf(Neon.cyan, Neon.violet)
                         else intArrayOf(Neon.alpha(Neon.cyan, 0.08f), Neon.alpha(Neon.violet, 0.08f))
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, colors).apply {
                cornerRadius = px(12).toFloat()
                if (!primary) setStroke(px(1), Neon.alpha(Neon.cyan, 0.7f))
            }
            setOnTouchListener { v, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.94f).scaleY(0.94f).setDuration(80).start()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
                }
                false
            }
            setOnClickListener { f() }
        }
        fun LinearLayout.put(v: View, top: Int = 8) = addView(v, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(top) })

        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(16), px(40), px(16), px(40)) }

        fun card(title: String): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(Neon.alpha(Neon.panel, 0.88f), Neon.alpha(Neon.panel2, 0.88f))).apply {
                cornerRadius = px(18).toFloat(); setStroke(px(1), Neon.alpha(Neon.cyan, 0.28f))
            }
            setPadding(px(16), px(14), px(16), px(16))
            addView(text("▍" + title.uppercase(), 12f, Neon.cyan, true).apply { letterSpacing = 0.18f })
            content.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(14) })
            alpha = 0f; translationY = px(28).toFloat()
            animate().alpha(1f).translationY(0f).setStartDelay(200L + 150L * cardIndex++).setDuration(500).start()
        }

        // ---- header + orb ----
        content.put(text("GRAB & DROP", 26f, Neon.text, true).apply { letterSpacing = 0.22f; gravity = Gravity.CENTER }, 0)
        subtitle = text("", 11f, Neon.muted).apply { letterSpacing = 0.2f; gravity = Gravity.CENTER }
        content.put(subtitle, 2)
        orb = OrbView(this)
        content.addView(orb, LinearLayout.LayoutParams(-1, px(320)).apply { topMargin = px(6) })
        status = text("", 14f, Neon.text, true).apply { gravity = Gravity.CENTER }
        content.put(status, 4)
        handLabel = text("", 12f, Neon.muted).apply { gravity = Gravity.CENTER }
        content.put(handLabel, 2)
        content.put(View(this), 10)

        // ---- link ----
        val c1 = card("Link")
        c1.put(text("Phone → any device: open palm, then fist. Receive on another device: fist, then open palm. Every transfer needs approval.", 12f, Neon.muted))
        codeView = text("", 15f, Neon.cyan, true).apply { visibility = View.GONE; setTextIsSelectable(true) }
        c1.put(codeView)
        toggle = button("Start watching", true) { if (Hub.running) GestureService.stop(this) else startWatching() }
        c1.put(toggle, 12)

        // ---- payload ----
        val c2 = card("Payload")
        val row = LinearLayout(this)
        fun addBtn(t: String, primary: Boolean, f: () -> Unit) =
            button(t, primary, f).also { row.addView(it, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = px(6) }) }
        addBtn("Choose", true) { picker.launch("*/*") }
        addBtn("Grab", false) { cmd(GestureService.GRAB) }
        addBtn("Cancel", false) { cmd(GestureService.CANCEL) }
        receiveBtn = addBtn("Receive", false) { cmd(GestureService.FETCH) }
        c2.put(row, 10)
        fileList = text("", 13f, Neon.text); c2.put(fileList, 12)
        c2.put(CheckBox(this).apply {
            text = "Keep a copy after sending"; typeface = mono; textSize = 12f
            setTextColor(Neon.muted); buttonTintList = ColorStateList.valueOf(Neon.cyan)
            setOnCheckedChangeListener { _, v -> Hub.keepCopy = v }
        }, 4)
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; visibility = View.GONE; progressTintList = ColorStateList.valueOf(Neon.green)
        }
        c2.put(bar)

        // ---- gesture sensor ----
        val c3 = card("Gesture sensor")
        val holdText = text("Hold time: 0.5 s", 12f, Neon.muted); c3.put(holdText, 10)
        c3.put(SeekBar(this).apply {
            max = 13; progress = 3
            progressTintList = ColorStateList.valueOf(Neon.cyan); thumbTintList = ColorStateList.valueOf(Neon.pink)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) {
                    Hub.holdMs = 200L + p * 100L; holdText.text = "Hold time: ${Hub.holdMs / 1000.0} s"
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }, 0)

        // ---- activity log ----
        val c4 = card("Activity log")
        logView = text("", 11.5f, Neon.muted); c4.put(logView, 8)

        flash = View(this)
        setContentView(FrameLayout(this).apply {
            addView(GridView(this@MainActivity), FrameLayout.LayoutParams(-1, -1))
            addView(ScrollView(this@MainActivity).apply { isVerticalScrollBarEnabled = false; addView(content) }, FrameLayout.LayoutParams(-1, -1))
            addView(flash, FrameLayout.LayoutParams(-1, -1))
        })
        flash.alpha = 0f

        handleShare(intent)
        if (b == null && !Hub.running) startWatching()
    }

    override fun onStart() {
        super.onStart()
        Hub.onChange = { runOnUiThread { refresh() } }
        Hub.onFx = { kind -> runOnUiThread { playFx(kind) } }
        refresh()
    }

    override fun onStop() { Hub.onChange = null; Hub.onFx = null; super.onStop() }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handleShare(intent) }

    /** Orb animation + screen-edge flash + a small bounce of the orb. */
    private fun playFx(kind: String) {
        orb.fx(kind)
        val col = when (kind) { "grab" -> Neon.pink; "sent" -> Neon.cyan; "drop", "incoming" -> Neon.green; else -> Neon.amber }
        flash.setBackgroundColor(col)
        flash.alpha = if (kind == "grab" || kind == "drop") 0.30f else 0.15f
        flash.animate().alpha(0f).setDuration(600).start()
        if (kind == "grab" || kind == "drop" || kind == "sent") {
            orb.animate().scaleX(1.07f).scaleY(1.07f).setDuration(120)
                .withEndAction { orb.animate().scaleX(1f).scaleY(1f).setDuration(320).start() }.start()
        }
    }

    private fun refresh() {
        val running = Hub.running
        subtitle.text = if (running) "●  LINK ONLINE" else "○  LINK OFFLINE"
        subtitle.setTextColor(if (running) Neon.green else Neon.muted)
        status.text = Hub.status
        handLabel.text = Hub.handText
        logView.text = Hub.logText()
        fileList.text = if (Hub.items.isEmpty()) "No files chosen" else Hub.items.joinToString("\n") { icon(it.second) + "  " + it.second }
        codeView.text = Hub.codeText; codeView.visibility = if (Hub.codeText.isEmpty()) View.GONE else View.VISIBLE
        bar.visibility = if (Hub.progress > 0) View.VISIBLE else View.GONE; bar.progress = Hub.progress.coerceAtLeast(0)
        toggle.text = if (running) "Stop watching" else "Start watching"
        receiveBtn.text = if (Hub.remoteName != null) "Receive ●" else "Receive"

        val n = Hub.carrying?.size
        orb.running = running
        orb.hand = when { Hub.handText.contains("fist") -> "fist"; Hub.handText.contains("open") -> "open"; else -> null }
        orb.mode = when { n != null -> OrbView.Mode.CARRYING; Hub.remoteName != null -> OrbView.Mode.INCOMING; else -> OrbView.Mode.IDLE }
        orb.progress = if (Hub.progress > 0) Hub.progress else -1
        orb.count = n ?: 0
        orb.centerText = when {
            !running -> "OFFLINE"
            Hub.progress > 0 -> "${Hub.progress}%"
            n != null -> "CARRY $n"
            Hub.remoteName != null -> "INCOMING"
            orb.hand == "fist" -> "FIST"
            orb.hand == "open" -> "PALM"
            else -> "READY"
        }
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
