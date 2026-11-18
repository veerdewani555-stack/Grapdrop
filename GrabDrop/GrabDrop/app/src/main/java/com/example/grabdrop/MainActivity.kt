package com.example.grabdrop

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
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
import androidx.core.graphics.ColorUtils
import java.io.File
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

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

// ===== Neon palette, OrbView and GridView (previously OrbView.kt) =====

object Neon {
    val bg = Color.parseColor("#04060D")
    val panel = Color.parseColor("#0E1426")
    val panel2 = Color.parseColor("#140F2E")
    val cyan = Color.parseColor("#22D3EE")
    val violet = Color.parseColor("#8B5CF6")
    val pink = Color.parseColor("#F472B6")
    val green = Color.parseColor("#34D399")
    val amber = Color.parseColor("#FBBF24")
    val text = Color.parseColor("#E6F1FF")
    val muted = Color.parseColor("#7C8DB0")
    fun alpha(color: Int, a: Float): Int = (color and 0x00FFFFFF) or ((a.coerceIn(0f, 1f) * 255).toInt() shl 24)
}

private const val TAU = (2.0 * PI).toFloat()

/**
 * The animated centre piece. Idle = slow rotating rings. Hand shapes tint and squeeze/expand the core.
 * fx("grab") = rings collapse into the core. fx("sent"/"drop") = shockwave + particle burst + check mark.
 * fx("incoming") = ripples converge. Carrying = orbiting particles + 60 s countdown arc.
 */
class OrbView(ctx: Context) : View(ctx) {
    enum class Mode { IDLE, CARRYING, INCOMING }

    var running = false
    var hand: String? = null          // "fist", "open" or null
    var progress = -1                 // 1..100 while a transfer is running
    var count = 0
    var centerText = "OFFLINE"
    var mode = Mode.IDLE
        set(v) { if (v == Mode.CARRYING && field != Mode.CARRYING) carryStart = SystemClock.uptimeMillis(); field = v }

    private class Fx(val kind: String, val t0: Long, val dur: Long)
    private class Spark(val ang: Float, val speed: Float, val size: Float, val color: Int)

    private val fxs = mutableListOf<Fx>()
    private var sparks: List<Spark> = emptyList()
    private var sparkT0 = 0L
    private var carryStart = 0L
    private var coreScale = 1f
    private var accent = Neon.cyan
    private val start = SystemClock.uptimeMillis()
    private val dp = ctx.resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()
    private val check = Path()
    private val bold = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    private val dashes = arrayOf(
        DashPathEffect(floatArrayOf(30f * dp, 16f * dp), 0f),
        DashPathEffect(floatArrayOf(6f * dp, 10f * dp), 0f),
        DashPathEffect(floatArrayOf(60f * dp, 24f * dp, 8f * dp, 24f * dp), 0f)
    )
    private val rings = arrayOf(floatArrayOf(0.84f, 22f, 2.5f), floatArrayOf(0.68f, -34f, 2f), floatArrayOf(0.53f, 50f, 3f))

    private fun easeOut(x: Float) = 1f - (1f - x) * (1f - x) * (1f - x)

    fun fx(kind: String) {
        val now = SystemClock.uptimeMillis()
        when (kind) {
            "grab" -> fxs.add(Fx(kind, now, 900))
            "sent", "drop" -> {
                fxs.add(Fx(kind, now, 1700))
                val r = Random(now)
                val col = if (kind == "drop") Neon.green else Neon.cyan
                sparks = List(60) { Spark(r.nextFloat() * TAU, 0.35f + r.nextFloat() * 0.9f, 1.5f + r.nextFloat() * 3.5f, if (r.nextInt(3) == 0) Neon.violet else col) }
                sparkT0 = now
            }
            "incoming" -> fxs.add(Fx(kind, now, 1800))
            "ask" -> fxs.add(Fx(kind, now, 1300))
        }
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val now = SystemClock.uptimeMillis()
        val t = (now - start) / 1000f
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f * 0.94f
        val dim = if (running) 1f else 0.35f
        fxs.removeAll { now - it.t0 > it.dur }

        val target = when {
            mode == Mode.CARRYING -> Neon.pink
            mode == Mode.INCOMING -> Neon.green
            hand == "fist" -> Neon.pink
            else -> Neon.cyan
        }
        accent = ColorUtils.blendARGB(accent, target, 0.12f)
        val ts = when { !running -> 0.9f; hand == "fist" -> 0.8f; hand == "open" -> 1.15f; else -> 1f }
        coreScale += (ts - coreScale) * 0.15f
        val coreR = r * 0.25f * coreScale * (1f + 0.04f * sin(t * 3f))

        // soft glow
        p.style = Paint.Style.FILL
        val pulse = 0.8f + 0.2f * sin(t * 2f)
        for (i in 0..5) {
            p.color = Neon.alpha(accent, (0.10f - i * 0.014f) * dim * pulse)
            c.drawCircle(cx, cy, r * (0.36f + i * 0.11f), p)
        }

        // tick ring
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.5f * dp
        p.color = Neon.alpha(accent, 0.55f * dim)
        c.save(); c.rotate(t * 6f, cx, cy)
        for (i in 0 until 90) {
            val len = if (i % 5 == 0) 10f * dp else 5f * dp
            c.drawLine(cx, cy - r, cx, cy - r + len, p)
            c.rotate(4f, cx, cy)
        }
        c.restore()

        // dashed rotating rings
        for (i in 0..2) {
            p.strokeWidth = rings[i][2] * dp
            p.pathEffect = dashes[i]
            p.color = Neon.alpha(if (i == 1) Neon.violet else accent, (0.5f + i * 0.15f) * dim)
            c.save(); c.rotate(t * rings[i][1], cx, cy)
            c.drawCircle(cx, cy, r * rings[i][0] * (1f + 0.012f * sin(t * 1.5f + i)), p)
            c.restore()
        }
        p.pathEffect = null

        // carry countdown (60 s)
        if (mode == Mode.CARRYING) {
            val frac = (1f - (now - carryStart) / 60000f).coerceIn(0f, 1f)
            p.strokeWidth = 4f * dp; p.strokeCap = Paint.Cap.ROUND
            p.color = Neon.alpha(Neon.pink, 0.9f)
            box.set(cx - r * 0.95f, cy - r * 0.95f, cx + r * 0.95f, cy + r * 0.95f)
            c.drawArc(box, -90f, 360f * frac, false, p)
            p.strokeCap = Paint.Cap.BUTT
        }

        // transfer progress arc
        if (progress > 0) {
            p.strokeWidth = 7f * dp; p.strokeCap = Paint.Cap.ROUND
            box.set(cx - r * 0.40f, cy - r * 0.40f, cx + r * 0.40f, cy + r * 0.40f)
            p.color = Neon.alpha(Neon.green, 0.15f); c.drawArc(box, 0f, 360f, false, p)
            p.color = Neon.alpha(Neon.green, 1f); c.drawArc(box, -90f, 3.6f * progress, false, p)
            p.strokeCap = Paint.Cap.BUTT
        }

        p.style = Paint.Style.FILL
        // orbiting particles while carrying
        if (mode == Mode.CARRYING) {
            val n = count.coerceIn(1, 8) + 2
            for (i in 0 until n) {
                val a = t * 1.6f + i * (TAU / n)
                val x = cx + cos(a) * r * 0.53f
                val y = cy + sin(a) * r * 0.53f
                p.color = Neon.alpha(Neon.pink, 0.25f); c.drawCircle(x, y, 9f * dp, p)
                p.color = Neon.alpha(Color.WHITE, 0.95f); c.drawCircle(x, y, 3.5f * dp, p)
            }
        }
        // particles streaming inward when another device is carrying
        if (mode == Mode.INCOMING) {
            for (i in 0 until 14) {
                val f = (t * 0.45f + i / 14f) % 1f
                val rr = r * (1f - f * 0.78f)
                val a = i * (TAU / 14f) + t * 0.2f
                p.color = Neon.alpha(Neon.green, sin(f * PI.toFloat()) * 0.9f)
                c.drawCircle(cx + cos(a) * rr, cy + sin(a) * rr, 3f * dp, p)
            }
        }

        // core
        p.color = Neon.alpha(accent, 0.12f * dim); c.drawCircle(cx, cy, coreR * 1.6f, p)
        p.color = Neon.alpha(accent, 0.28f * dim); c.drawCircle(cx, cy, coreR * 1.25f, p)
        p.color = Neon.alpha(accent, 0.95f * dim); c.drawCircle(cx, cy, coreR, p)
        p.color = Neon.alpha(Color.WHITE, 0.88f * dim); c.drawCircle(cx, cy, coreR * 0.8f, p)

        // one-shot effects
        var checking = false
        for (f in fxs) {
            val pr = ((now - f.t0).toFloat() / f.dur).coerceIn(0f, 1f)
            val e = easeOut(pr)
            when (f.kind) {
                "grab" -> {
                    p.style = Paint.Style.STROKE; p.strokeWidth = 3f * dp
                    for (k in 0..2) {
                        val r0 = r * (1f - k * 0.14f)
                        p.color = Neon.alpha(Neon.pink, (1f - pr) * 0.9f)
                        c.drawCircle(cx, cy, r0 + (coreR * 1.1f - r0) * e, p)
                    }
                    p.style = Paint.Style.FILL
                    val s = sin(pr * PI.toFloat())
                    p.color = Neon.alpha(Color.WHITE, s * 0.8f)
                    c.drawCircle(cx, cy, coreR * (1f + 0.7f * s), p)
                }
                "sent", "drop" -> {
                    val col = if (f.kind == "drop") Neon.green else Neon.cyan
                    p.style = Paint.Style.STROKE
                    for (k in 0..1) {
                        val q = ((pr - k * 0.18f) / (1f - k * 0.18f)).coerceIn(0f, 1f)
                        p.strokeWidth = (1f + 6f * (1f - q)) * dp
                        p.color = Neon.alpha(col, (1f - q) * 0.9f)
                        c.drawCircle(cx, cy, coreR + (r * 1.12f - coreR) * easeOut(q), p)
                    }
                    checking = true
                    val ca = (sin(pr * PI.toFloat()) * 2f).coerceIn(0f, 1f)
                    check.reset()
                    check.moveTo(cx - coreR * 0.38f, cy + coreR * 0.02f)
                    check.lineTo(cx - coreR * 0.10f, cy + coreR * 0.32f)
                    check.lineTo(cx + coreR * 0.42f, cy - coreR * 0.30f)
                    p.strokeWidth = 6f * dp; p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND
                    p.color = Neon.alpha(col, ca)
                    c.drawPath(check, p)
                    p.strokeCap = Paint.Cap.BUTT
                }
                "incoming" -> {
                    p.style = Paint.Style.STROKE; p.strokeWidth = 2.5f * dp
                    for (k in 0..2) {
                        val pk = (pr * 1.6f - k * 0.3f).coerceIn(0f, 1f)
                        p.color = Neon.alpha(Neon.green, sin(pk * PI.toFloat()) * 0.7f)
                        c.drawCircle(cx, cy, r * 1.1f + (coreR - r * 1.1f) * pk, p)
                    }
                }
                "ask" -> {
                    p.style = Paint.Style.STROKE; p.strokeWidth = 3f * dp
                    p.color = Neon.alpha(Neon.amber, (1f - pr) * 0.85f)
                    c.drawCircle(cx, cy, r * (0.5f + 0.1f * sin(pr * PI.toFloat() * 4f)), p)
                }
            }
        }

        // particle burst
        val sp = ((now - sparkT0) / 1700f)
        if (sp in 0f..1f) {
            p.style = Paint.Style.FILL
            val d = easeOut(sp)
            for (s in sparks) {
                p.color = Neon.alpha(s.color, 1f - sp)
                c.drawCircle(cx + cos(s.ang) * r * s.speed * 1.25f * d, cy + sin(s.ang) * r * s.speed * 1.25f * d, s.size * dp * (1f - sp * 0.5f), p)
            }
        }

        // label in the core
        if (!checking) {
            p.style = Paint.Style.FILL; p.textAlign = Paint.Align.CENTER; p.typeface = bold
            p.textSize = coreR * 0.30f
            p.color = Neon.alpha(Neon.bg, 0.9f)
            c.drawText(centerText, cx, cy - (p.descent() + p.ascent()) / 2f, p)
        }
        postInvalidateOnAnimation()
    }
}

/** Dark sci-fi background: drifting grid, twinkling stars, and a slow scan line. */
class GridView(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val start = SystemClock.uptimeMillis()
    private val dp = ctx.resources.displayMetrics.density
    private val stars = Random(7).let { r -> List(70) { floatArrayOf(r.nextFloat(), r.nextFloat(), r.nextFloat() * TAU, 0.5f + r.nextFloat() * 1.5f) } }

    override fun onDraw(c: Canvas) {
        val t = (SystemClock.uptimeMillis() - start) / 1000f
        val w = width.toFloat(); val h = height.toFloat()
        p.style = Paint.Style.STROKE; p.strokeWidth = 1f
        p.color = Neon.alpha(Neon.cyan, 0.06f)
        val step = 36f * dp
        var x = (t * 6f * dp) % step
        while (x < w) { c.drawLine(x, 0f, x, h, p); x += step }
        var y = (t * 10f * dp) % step
        while (y < h) { c.drawLine(0f, y, w, y, p); y += step }
        p.style = Paint.Style.FILL
        for (s in stars) {
            p.color = Neon.alpha(Neon.text, 0.12f + 0.35f * (0.5f + 0.5f * sin(t * s[3] + s[2])))
            c.drawCircle(s[0] * w, s[1] * h, 1.2f * dp, p)
        }
        p.style = Paint.Style.STROKE; p.strokeWidth = 2f * dp
        p.color = Neon.alpha(Neon.cyan, 0.06f)
        val sy = ((t * 0.12f) % 1f) * h
        c.drawLine(0f, sy, w, sy, p)
        postInvalidateOnAnimation()
    }
}
