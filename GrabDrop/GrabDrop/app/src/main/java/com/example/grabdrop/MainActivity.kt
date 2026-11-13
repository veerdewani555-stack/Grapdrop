package com.example.grabdrop

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
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
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.concurrent.thread
import kotlin.math.hypot

// Kept so the older GestureService.kt (no longer used) still compiles.
object Carry { @Volatile var shared: File? = null }
object Debug {
    @Volatile var running = false
    @Volatile var frames = 0
    @Volatile var hand = "-"
    @Volatile var error = ""
}

class MainActivity : ComponentActivity() {
    private companion object {
        const val OPEN_MIN = 1.55   // average finger length / palm size above this = open palm
        const val FIST_MAX = 1.30   // below this = fist
        const val WINDOW_MS = 5000L
        const val CARRY_MS = 60_000L
    }

    private val items = CopyOnWriteArrayList<Pair<Uri, String>>()
    @Volatile private var carrying: List<Pair<Uri, String>>? = null
    private var server: MultiServer? = null
    private var regListener: NsdManager.RegistrationListener? = null
    @Volatile private var carryUntil = 0L
    @Volatile private var holdMs = 500L

    private lateinit var status: TextView
    private lateinit var label: TextView
    private lateinit var info: TextView
    private lateinit var fileList: TextView
    private lateinit var holdText: TextView
    private lateinit var logView: TextView
    private lateinit var badge: TextView
    private lateinit var codeView: TextView
    private lateinit var bar: ProgressBar
    private lateinit var preview: PreviewView
    private lateinit var keep: CheckBox
    private val lines = ArrayDeque<String>()

    private var landmarker: HandLandmarker? = null
    private val exec = Executors.newSingleThreadExecutor()
    private var lastFrame = 0L
    private var frames = 0
    private var candidate: String? = null
    private var since = 0L
    private var confirmed: String? = null
    private var lastOpen = 0L

    private val picker = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        uris.forEach { items.add(it to nameOf(it)) }
        renderList()
        if (uris.isNotEmpty()) say("${items.size} file(s) ready. Make a fist to grab them.")
    }
    private val camPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) initAndStart() else label.text = "Camera permission is needed"
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        // same colours as the web page (light + dark)
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
        fun LinearLayout.put(v: View, top: Int = 8) =
            addView(v, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(top) })

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(px(16), px(40), px(16), px(90))
        }
        fun card(title: String): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(cCard); cornerRadius = px(14).toFloat() }
            elevation = px(2).toFloat(); setPadding(px(14), px(14), px(14), px(14))
            addView(text(title, 16f, cText, true))
            content.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(12) })
        }

        content.addView(text("✋ Grab & Drop", 22f, cText, true))
        content.put(View(this), 8)

        // ---- Connection card ----
        val c1 = card("Connection")
        status = text("Ready. Choose files, then make a fist to grab.", 15f, cText, true)
        c1.put(status)
        c1.put(text("Run receiver.py on your laptop (same Wi-Fi). It should find this phone by itself. If it does not, type the code that appears here while you are carrying.", 13f, cMuted))
        c1.put(text("This phone: GrabDrop-" + Build.MODEL, 13f, cMuted), 4)
        codeView = text("", 16f, cAccent, true).apply { visibility = View.GONE; setTextIsSelectable(true) }
        c1.put(codeView, 8)

        // ---- Files card ----
        val c2 = card("Files")
        val row = LinearLayout(this)
        fun addBtn(t: String, filled: Boolean, f: () -> Unit) =
            row.addView(button(t, filled, f), LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = px(6) })
        addBtn("📁 Choose", true) { picker.launch("*/*") }
        addBtn("Grab", false) { grab() }
        addBtn("Cancel", false) { stopCarry("Cancelled") }
        c2.put(row)
        val zone = text("Choose files, or use Share → Grab & Drop from any app", 13f, cMuted).apply {
            gravity = Gravity.CENTER; setPadding(px(12), px(14), px(12), px(14))
            background = GradientDrawable().apply {
                cornerRadius = px(12).toFloat(); setColor(Color.TRANSPARENT)
                setStroke(px(2), cMuted, px(8).toFloat(), px(4).toFloat())
            }
        }
        c2.put(zone)
        fileList = text("No files chosen", 14f, cText)
        c2.put(fileList)
        keep = CheckBox(this).apply {
            text = "Keep a copy after sending"; setTextColor(cMuted); buttonTintList = ColorStateList.valueOf(cAccent)
        }
        c2.put(keep, 4)
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; visibility = View.GONE; progressTintList = ColorStateList.valueOf(cOk)
        }
        c2.put(bar)

        // ---- Gestures card ----
        val c3 = card("Hand gestures")
        label = TextView(this).apply {
            textSize = 16f; setTextColor(Color.WHITE); setBackgroundColor(0x99000000.toInt())
            setPadding(px(10), px(4), px(10), px(4)); text = "starting…"
        }
        preview = PreviewView(this).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE }
        val frame = FrameLayout(this).apply {
            background = GradientDrawable().apply { setColor(Color.BLACK); cornerRadius = px(12).toFloat() }
            clipToOutline = true
            addView(preview, FrameLayout.LayoutParams(-1, -1))
            addView(label, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(px(8), px(8), 0, 0) })
        }
        c3.addView(frame, LinearLayout.LayoutParams(-1, px(250)).apply { topMargin = px(8) })
        holdText = text("Hold time: 0.5 s", 13f, cMuted)
        c3.put(holdText)
        val holdBar = SeekBar(this).apply {
            max = 13; progress = 3
            progressTintList = ColorStateList.valueOf(cAccent); thumbTintList = ColorStateList.valueOf(cAccent)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) {
                    holdMs = 200L + p * 100L; holdText.text = "Hold time: ${holdMs / 1000.0} s"
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        c3.put(holdBar, 0)
        info = text("", 13f, cMuted)
        c3.put(info, 4)
        c3.put(text("Open palm → fist (hold) = grab.\nOn the laptop: fist → open palm = drop.", 13f, cMuted), 4)

        // ---- Activity card ----
        val c4 = card("Activity")
        logView = text("Nothing yet", 13f, cMuted)
        c4.put(logView)

        badge = text("✊ Carrying", 15f, Color.WHITE, true).apply {
            setPadding(px(16), px(8), px(16), px(8)); visibility = View.GONE
            background = GradientDrawable().apply { setColor(cOk); cornerRadius = px(20).toFloat() }
        }
        val root = FrameLayout(this).apply {
            addView(ScrollView(this@MainActivity).apply { addView(content) }, FrameLayout.LayoutParams(-1, -1))
            addView(badge, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, px(16), px(16)) })
        }
        setContentView(root)

        handleShare(intent)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) initAndStart()
        else camPerm.launch(Manifest.permission.CAMERA)
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handleShare(intent) }

    private fun say(msg: String, log: Boolean = true) {
        status.text = msg
        if (!log) return
        lines.addFirst(java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date()) + "  " + msg)
        while (lines.size > 8) lines.removeLast()
        logView.text = lines.joinToString("\n")
    }

    private fun localIp(): String? = try {
        java.net.NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
            .firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address && it.isSiteLocalAddress }?.hostAddress
    } catch (_: Exception) { null }

    private fun updateBadge() {
        badge.visibility = if (carrying != null) View.VISIBLE else View.GONE
        badge.text = "✊ Carrying " + (carrying?.size ?: 0)
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

    private fun renderList() {
        fileList.text = if (items.isEmpty()) "No files chosen" else items.joinToString("\n") { icon(it.second) + "  " + it.second }
    }

    private fun handleShare(i: Intent?) {
        if (i?.action != Intent.ACTION_SEND) return
        @Suppress("DEPRECATION") val u = i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) ?: return
        thread {
            val f = File(cacheDir, nameOf(u))
            contentResolver.openInputStream(u)?.use { a -> f.outputStream().use { a.copyTo(it) } }
            runOnUiThread { items.add(Uri.fromFile(f) to f.name); renderList(); say("Shared file added: " + f.name) }
        }
    }

    // ---------- camera + hand detection ----------
    private fun initAndStart() {
        label.text = "loading hand model…"
        thread {
            try {
                landmarker = HandLandmarker.createFromOptions(this, HandLandmarker.HandLandmarkerOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetPath("hand_landmarker.task").build())
                    .setRunningMode(RunningMode.VIDEO).setNumHands(1)
                    .setMinHandDetectionConfidence(0.3f).setMinHandPresenceConfidence(0.3f)
                    .setMinTrackingConfidence(0.3f).build())
                runOnUiThread { startCamera() }
            } catch (e: Throwable) { runOnUiThread { label.text = "Hand model failed: ${e.message}" } }
        }
    }

    private fun startCamera() {
        val fut = ProcessCameraProvider.getInstance(this)
        fut.addListener({
            try {
                val provider = fut.get()
                val prev = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                analysis.setAnalyzer(exec) { analyze(it) }
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, prev, analysis)
                label.text = "show your hand"
            } catch (e: Throwable) { label.text = "Camera error: ${e.message}" }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun score(lm: List<NormalizedLandmark>, w: Int, h: Int): Double {
        fun d(a: Int, b: Int) = hypot((lm[a].x() - lm[b].x()) * w.toDouble(), (lm[a].y() - lm[b].y()) * h.toDouble())
        val palm = d(0, 9).coerceAtLeast(1e-6)
        return listOf(8, 12, 16, 20).map { d(it, 0) / palm }.average()
    }

    private fun analyze(p: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastFrame < 80) { p.close(); return }
        lastFrame = now; frames++
        try {
            val lm = landmarker ?: return
            val raw = p.toBitmap()
            val m = Matrix().apply { postRotate(p.imageInfo.rotationDegrees.toFloat()) }
            val bmp = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
            val hand = lm.detectForVideo(BitmapImageBuilder(bmp).build(), SystemClock.uptimeMillis()).landmarks().firstOrNull()
            var g: String? = null
            val text: String
            if (hand == null) text = "no hand"
            else {
                val s = score(hand, bmp.width, bmp.height)
                g = if (s > OPEN_MIN) "open" else if (s < FIST_MAX) "fist" else null
                text = (if (g == "open") "🖐 open" else if (g == "fist") "✊ fist" else "hand (unsure)") + "  " + "%.2f".format(s)
            }
            onPose(g, now)
            runOnUiThread { label.text = text; info.text = "Camera frames: $frames" }
        } catch (e: Throwable) {
            runOnUiThread { info.text = "Error: $e" }
        } finally { p.close() }
    }

    private fun onPose(g: String?, now: Long) {
        if (g != candidate) { candidate = g; since = now }
        if (candidate != null && now - since >= holdMs && candidate != confirmed) {
            confirmed = candidate
            if (confirmed == "fist" && now - lastOpen < WINDOW_MS && carrying == null) runOnUiThread { grab() }
        }
        if (candidate == null && now - since > 1500) confirmed = null
        if (confirmed == "open") lastOpen = now
        if (carrying != null && now > carryUntil) runOnUiThread { stopCarry("Time ran out") }
    }

    // ---------- grab: serve the files and announce them on the Wi-Fi ----------
    private fun grab() {
        if (carrying != null) return
        if (items.isEmpty()) { say("Choose files first"); return }
        val list = items.toList()
        val token = UUID.randomUUID().toString().take(8)
        try {
            val srv = MultiServer(contentResolver, list, token,
                { done, total -> runOnUiThread { bar.progress = done * 100 / total } }) {
                runOnUiThread { stopCarry("✅ Sent ${list.size} file(s)", true) }
            }
            srv.start()
            server = srv; carrying = list; carryUntil = SystemClock.elapsedRealtime() + CARRY_MS
            val svc = NsdServiceInfo().apply {
                serviceName = "GrabDrop-" + Build.MODEL; serviceType = "_grabdrop._tcp."; port = srv.port
                setAttribute("t", token)
                setAttribute("n", (list[0].second + if (list.size > 1) " +" + (list.size - 1) else "").take(60))
            }
            val l = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(i: NsdServiceInfo) { runOnUiThread { say("Announced on Wi-Fi as " + i.serviceName, false) } }
                override fun onRegistrationFailed(i: NsdServiceInfo, e: Int) { runOnUiThread { say("Wi-Fi announce failed (code $e). Use the code below.") } }
                override fun onServiceUnregistered(i: NsdServiceInfo) {}
                override fun onUnregistrationFailed(i: NsdServiceInfo, e: Int) {}
            }
            regListener = l
            (getSystemService(Context.NSD_SERVICE) as NsdManager).registerService(svc, NsdManager.PROTOCOL_DNS_SD, l)
            val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 80, 40, 80), -1))
            bar.progress = 0; bar.visibility = View.VISIBLE; updateBadge()
            codeView.text = "Laptop code:  " + (localIp() ?: "?") + ":" + srv.port + "/" + token
            codeView.visibility = View.VISIBLE
            say("✊ Carrying ${list.size} file(s). Drop on your laptop.")
        } catch (e: Throwable) { stopCarry("Grab failed: ${e.message}") }
    }

    private fun stopCarry(msg: String, remove: Boolean = false) {
        val c = carrying
        try { regListener?.let { (getSystemService(Context.NSD_SERVICE) as NsdManager).unregisterService(it) } } catch (_: Exception) {}
        regListener = null; server?.stop(); server = null; carrying = null
        if (remove && c != null && !keep.isChecked) { items.removeAll(c); renderList() }
        bar.visibility = View.GONE; codeView.visibility = View.GONE; updateBadge()
        say(msg)
    }

    override fun onDestroy() {
        stopCarry("Stopped"); landmarker?.close(); exec.shutdown(); super.onDestroy()
    }
}

/** Serves /list and /file?i=N, only to requests that carry the secret token. */
class MultiServer(
    private val cr: ContentResolver, private val files: List<Pair<Uri, String>>,
    private val token: String, private val onProgress: (Int, Int) -> Unit, private val onAllServed: () -> Unit
) {
    private val socket = ServerSocket(0)
    private val served = java.util.Collections.synchronizedSet(mutableSetOf<Int>())
    val port get() = socket.localPort

    fun start() = thread(isDaemon = true) {
        try { while (true) { val c = socket.accept(); thread(isDaemon = true) { handle(c) } } } catch (_: Exception) {}
    }

    private fun handle(c: Socket) = c.use { s ->
        val reader = s.getInputStream().bufferedReader()
        val first = reader.readLine() ?: return@use
        while (true) { val l = reader.readLine(); if (l.isNullOrEmpty()) break }
        val out = s.getOutputStream()
        fun reply(code: String, body: String = "") {
            val b = body.toByteArray()
            out.write("HTTP/1.1 $code\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: ${b.size}\r\nConnection: close\r\n\r\n".toByteArray())
            out.write(b)
        }
        if (!first.contains("t=$token")) { reply("403 Forbidden"); return@use }
        if (first.startsWith("GET /list")) { reply("200 OK", files.joinToString("\n") { it.second }); return@use }
        val i = Regex("i=(\\d+)").find(first)?.groupValues?.get(1)?.toInt() ?: 0
        if (i >= files.size) { reply("404 Not Found"); return@use }
        val (uri, name) = files[i]
        val mime = cr.getType(uri) ?: "application/octet-stream"
        out.write("HTTP/1.1 200 OK\r\nContent-Type: $mime\r\nContent-Disposition: attachment; filename=\"${name.replace("\"", "")}\"\r\nConnection: close\r\n\r\n".toByteArray())
        cr.openInputStream(uri)?.use { it.copyTo(out) }
        out.flush()
        served.add(i)
        onProgress(served.size, files.size)
        if (served.size >= files.size) onAllServed()
    }

    fun stop() { try { socket.close() } catch (_: Exception) {} }
}
