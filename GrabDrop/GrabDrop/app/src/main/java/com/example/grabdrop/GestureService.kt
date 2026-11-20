package com.example.grabdrop

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.MediaStore
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.hypot

/**
 * Owns the camera, gesture detection, sending (phone -> laptop) and receiving (laptop -> phone).
 * Runs as a camera foreground service, so it keeps working with the app in the background.
 * NOTE: Android 11+ only lets a camera service START while the app is visible (the Start button does that).
 */
class GestureService : LifecycleService() {
    companion object {
        const val GRAB = "grab"; const val CANCEL = "cancel"; const val FETCH = "fetch"; const val STOP = "stop"
        const val OPEN_MIN = 1.55; const val FIST_MAX = 1.30
        const val WINDOW_MS = 5000L; const val CARRY_MS = 60_000L
        const val CH_RUN = "run"; const val CH_ASK = "ask"
        const val SVC_PHONE = "_grabdrop._tcp."
        const val SVC_PC = "_grabdrop-pc._tcp."
        fun start(c: Context) = ContextCompat.startForegroundService(c, Intent(c, GestureService::class.java))
        fun stop(c: Context) { c.stopService(Intent(c, GestureService::class.java)) }
    }

    private data class Remote(val host: String, val port: Int, val token: String, val label: String, val device: String)

    private val main = Handler(Looper.getMainLooper())
    private val exec = Executors.newSingleThreadExecutor()
    private val nsd by lazy { getSystemService(Context.NSD_SERVICE) as NsdManager }
    private var landmarker: HandLandmarker? = null
    private var started = false

    // gesture state (analysis thread only)
    private var lastFrame = 0L
    private var candidate: String? = null
    private var since = 0L
    private var confirmed: String? = null
    private var lastOpen = 0L
    private var lastFist = 0L

    // sending
    private var server: MultiServer? = null
    private var regListener: NsdManager.RegistrationListener? = null
    @Volatile private var ownName = ""   // our own announced name, so we never 'receive' from ourselves
    @Volatile private var carryUntil = 0L

    // receiving
    private val discoveries = mutableListOf<NsdManager.DiscoveryListener>()
    private val remotes = ConcurrentHashMap<String, Remote>()
    private val pending = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    @Volatile private var fetching = false

    override fun onCreate() { super.onCreate(); Hub.running = true }

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(i, flags, startId)
        when (i?.action) {
            STOP -> { stopSelf(); return START_NOT_STICKY }
            GRAB -> if (started) grab() else stopSelf()
            CANCEL -> if (started) stopCarry("Cancelled") else stopSelf()
            FETCH -> if (started) fetch() else stopSelf()
            else -> if (!started) {
                started = true
                try { goForeground() } catch (e: Exception) { fail("Could not start camera service: ${e.message}"); return START_NOT_STICKY }
                initAndStart()
            }
        }
        return START_NOT_STICKY
    }

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_RUN, "Gesture watching", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_ASK, "Transfer requests", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, GestureService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, CH_RUN).setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Grab & Drop is watching for gestures").setContentIntent(open)
            .addAction(0, "Stop", stop).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        else startForeground(1, n)
    }

    private fun fail(msg: String) { Hub.say(msg); stopSelf() }

    // ---------- camera + hand detection ----------
    private fun initAndStart() {
        Hub.handText = "loading hand model…"; Hub.onChange?.invoke()
        thread {
            try {
                landmarker = HandLandmarker.createFromOptions(this, HandLandmarker.HandLandmarkerOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetPath("hand_landmarker.task").build())
                    .setRunningMode(RunningMode.VIDEO).setNumHands(1)
                    .setMinHandDetectionConfidence(0.3f).setMinHandPresenceConfidence(0.3f)
                    .setMinTrackingConfidence(0.3f).build())
                main.post { startCamera() }
            } catch (e: Throwable) { main.post { fail("Hand model failed: ${e.message}") } }
        }
    }

    private fun startCamera() {
        val fut = ProcessCameraProvider.getInstance(this)
        fut.addListener({
            try {
                val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                analysis.setAnalyzer(exec) { analyze(it) }
                fut.get().apply { unbindAll(); bindToLifecycle(this@GestureService, CameraSelector.DEFAULT_FRONT_CAMERA, analysis) }
                Hub.handText = "show your hand"; Hub.say("Watching for gestures", false)
                startDiscovery()
            } catch (e: Throwable) { fail("Camera error: ${e.message}") }
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
        lastFrame = now
        try {
            val lm = landmarker ?: return
            val raw = p.toBitmap()
            val bmp = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height,
                Matrix().apply { postRotate(p.imageInfo.rotationDegrees.toFloat()) }, true)
            val hand = lm.detectForVideo(BitmapImageBuilder(bmp).build(), SystemClock.uptimeMillis()).landmarks().firstOrNull()
            var g: String? = null
            if (hand == null) Hub.handText = "no hand"
            else {
                val s = score(hand, bmp.width, bmp.height)
                g = if (s > OPEN_MIN) "open" else if (s < FIST_MAX) "fist" else null
                Hub.handText = (if (g == "open") "🖐 open" else if (g == "fist") "✊ fist" else "hand (unsure)") + "  %.2f".format(s)
            }
            onPose(g, now)
            Hub.onChange?.invoke()
        } catch (e: Throwable) { Hub.handText = "Error: $e" } finally { p.close() }
    }

    private fun onPose(g: String?, now: Long) {
        if (g != candidate) { candidate = g; since = now }
        if (candidate != null && now - since >= Hub.holdMs && candidate != confirmed) {
            confirmed = candidate
            // phone -> laptop: open palm, then fist
            if (confirmed == "fist" && now - lastOpen < WINDOW_MS && Hub.carrying == null && Hub.items.isNotEmpty()) main.post { grab() }
            // laptop -> phone: fist, then open palm
            if (confirmed == "open" && now - lastFist < WINDOW_MS && Hub.carrying == null && remotes.isNotEmpty()) main.post { fetch() }
        }
        if (candidate == null && now - since > 1500) confirmed = null
        if (confirmed == "open") lastOpen = now
        if (confirmed == "fist") lastFist = now
        if (Hub.carrying != null && now > carryUntil) main.post { stopCarry("Time ran out") }
    }

    // ---------- phone -> laptop ----------
    private fun grab() {
        if (Hub.carrying != null) return
        val list = Hub.items.toList()
        if (list.isEmpty()) { Hub.say("Choose files first"); return }
        val token = UUID.randomUUID().toString().take(8)
        try {
            val srv = MultiServer(contentResolver, list, token, this::ask,
                { done, total -> Hub.progress = done * 100 / total; Hub.onChange?.invoke() }) {
                main.post { stopCarry("✅ Sent ${list.size} file(s)", true) }
            }
            srv.start()
            server = srv; Hub.carrying = list; carryUntil = SystemClock.elapsedRealtime() + CARRY_MS
            val svc = NsdServiceInfo().apply {
                ownName = "GrabDrop-" + Build.MODEL + "-" + token.take(4); serviceName = ownName; serviceType = SVC_PHONE; port = srv.port
                setAttribute("t", token)
                setAttribute("n", (list[0].second + if (list.size > 1) " +" + (list.size - 1) else "").take(60))
            }
            val l = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(i: NsdServiceInfo) { Hub.say("Announced on Wi-Fi as " + i.serviceName, false) }
                override fun onRegistrationFailed(i: NsdServiceInfo, e: Int) { Hub.say("Wi-Fi announce failed (code $e). Use the code below.") }
                override fun onServiceUnregistered(i: NsdServiceInfo) {}
                override fun onUnregistrationFailed(i: NsdServiceInfo, e: Int) {}
            }
            regListener = l
            nsd.registerService(svc, NsdManager.PROTOCOL_DNS_SD, l)
            vibrate()
            Hub.progress = 0
            Hub.codeText = "Laptop code:  " + (localIp() ?: "?") + ":" + srv.port + "/" + token
            Hub.say("✊ Carrying ${list.size} file(s). Drop on your laptop.")
        } catch (e: Throwable) { stopCarry("Grab failed: ${e.message}") }
    }

    private fun stopCarry(msg: String, remove: Boolean = false) {
        val c = Hub.carrying
        try { regListener?.let { nsd.unregisterService(it) } } catch (_: Exception) {}
        regListener = null; server?.stop(); server = null; Hub.carrying = null
        if (msg.startsWith("✅ Sent") && c != null)
            c.forEach { Hub.addHistory(this, Hub.Entry("out", it.second, "", null, "", System.currentTimeMillis())) }
        if (remove && c != null && !Hub.keepCopy) Hub.items.removeAll(c)
        Hub.progress = -1; Hub.codeText = ""
        Hub.say(msg)
    }

    /** Called from the server thread for each new device. Shows Allow/Deny on a notification; denies on timeout. */
    private fun ask(ip: String, device: String): Boolean {
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            Hub.say("Blocked $device: turn on notifications so you can approve transfers"); return false
        }
        val id = UUID.randomUUID().toString()
        val yes = "com.example.grabdrop.Y$id"; val no = "com.example.grabdrop.N$id"
        val latch = CountDownLatch(1); val ok = AtomicBoolean(false)
        val rcv = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { ok.set(i.action == yes); latch.countDown() }
        }
        ContextCompat.registerReceiver(this, rcv, IntentFilter().apply { addAction(yes); addAction(no) }, ContextCompat.RECEIVER_NOT_EXPORTED)
        fun pi(a: String) = PendingIntent.getBroadcast(this, 0, Intent(a).setPackage(packageName), PendingIntent.FLAG_IMMUTABLE)
        val nid = 1000 + (id.hashCode() and 0xFFFF)
        val n = NotificationCompat.Builder(this, CH_ASK).setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentTitle("Allow \"$device\" to take your files?").setContentText("Request from $ip. Only allow your own device.")
            .setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_CALL)
            .addAction(0, "Allow", pi(yes)).addAction(0, "Deny", pi(no)).setTimeoutAfter(20_000).build()
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(nid, n); vibrate()
        Hub.say("🔐 $device ($ip) is asking to receive. Approve it in the notification.")
        val answered = latch.await(20, TimeUnit.SECONDS)
        try { unregisterReceiver(rcv) } catch (_: Exception) {}
        nm.cancel(nid)
        val allowed = answered && ok.get()
        Hub.say(if (allowed) "Allowed $device" else "Denied $device")
        return allowed
    }

    // ---------- laptop -> phone ----------
    // Look for laptops (_grabdrop-pc) AND other phones (_grabdrop)
    private fun startDiscovery() { discover(SVC_PC); discover(SVC_PHONE) }

    private fun discover(type: String) {
        val l = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(s: NsdServiceInfo) { if (s.serviceName != ownName) enqueue(s) }
            override fun onServiceLost(s: NsdServiceInfo) {
                remotes.remove(s.serviceName); syncRemotes()
            }
            override fun onDiscoveryStarted(t: String) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onStartDiscoveryFailed(t: String, e: Int) { Hub.say("Laptop discovery failed (code $e)") }
            override fun onStopDiscoveryFailed(t: String, e: Int) {}
        }
        discoveries.add(l)
        nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, l)
    }

    private fun syncRemotes() {
        Hub.nearby = remotes.values.map { it.device to it.label }
        Hub.remoteName = remotes.values.firstOrNull()?.label
        Hub.onChange?.invoke()
    }

    // NsdManager resolves one service at a time, so queue them
    private fun enqueue(s: NsdServiceInfo) = synchronized(pending) { pending.addLast(s); resolveNext() }

    @Suppress("DEPRECATION")
    private fun resolveNext() {
        if (resolving) return
        val s = pending.removeFirstOrNull() ?: return
        resolving = true
        nsd.resolveService(s, object : NsdManager.ResolveListener {
            override fun onResolveFailed(i: NsdServiceInfo, e: Int) { synchronized(pending) { resolving = false; resolveNext() } }
            override fun onServiceResolved(i: NsdServiceInfo) {
                val tok = i.attributes["t"]?.let { String(it) }
                val label = i.attributes["n"]?.let { String(it) } ?: "files"
                val host = i.host?.hostAddress
                if (tok != null && host != null && i.serviceName != ownName) {
                    val dev = i.serviceName.removePrefix("GrabDrop-").replace(Regex("-[0-9a-f]{4}$"), "")
                    remotes[i.serviceName] = Remote(host, i.port, tok, label, dev)
                    syncRemotes()
                    Hub.say("📲 $dev is carrying: $label. Fist → open palm, or tap Receive.")
                }
                synchronized(pending) { resolving = false; resolveNext() }
            }
        })
    }

    private fun fetch() {
        if (fetching) return
        val entry = remotes.entries.firstOrNull() ?: run { Hub.say("Nothing to receive (no other device is carrying)"); return }
        fetching = true
        val r = entry.value
        thread {
            try {
                Hub.say("⬇ Asking ${r.label}… allow it on the other device")
                val base = "http://${r.host}:${r.port}"
                val dev = URLEncoder.encode(Build.MODEL, "UTF-8")
                val names = open("$base/list?t=${r.token}&d=$dev", 40_000).inputStream.bufferedReader().use { it.readText() }
                    .split("\n").filter { it.isNotBlank() }
                names.forEachIndexed { i, n ->
                    val c = open("$base/file?i=$i&t=${r.token}", 120_000)
                    val mimeStr = (c.contentType ?: "application/octet-stream").substringBefore(';')
                    val saved = c.inputStream.use { save(n, mimeStr, it) }
                    Hub.addHistory(this@GestureService, Hub.Entry("in", n, mimeStr, saved, r.device, System.currentTimeMillis()))
                    Hub.progress = (i + 1) * 100 / names.size; Hub.onChange?.invoke()
                }
                remotes.remove(entry.key); syncRemotes()
                vibrate(); Hub.say("✅ Received ${names.size} file(s) in Downloads/GrabDrop")
            } catch (e: Exception) { Hub.say("Receive failed: ${e.message}") }
            finally { Hub.progress = -1; fetching = false; Hub.onChange?.invoke() }
        }
    }

    private fun open(url: String, readMs: Int): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 8000; readTimeout = readMs }
            .also { if (it.responseCode != 200) error("other device answered ${it.responseCode} (denied?)") }

    private fun save(rawName: String, mime: String, input: InputStream): String? {
        val name = rawName.substringAfterLast('/').substringAfterLast('\\').ifBlank { "file" }
        if (Build.VERSION.SDK_INT >= 29) {
            val v = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime.substringBefore(';'))
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/GrabDrop")
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: error("could not create file")
            contentResolver.openOutputStream(uri)?.use { input.copyTo(it) }
            return uri.toString()
        } else {
            val dir = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "GrabDrop").apply { mkdirs() }
            File(dir, name).outputStream().use { input.copyTo(it) }
            return null
        }
    }

    // ---------- helpers ----------
    private fun vibrate() {
        try { (getSystemService(Context.VIBRATOR_SERVICE) as Vibrator).vibrate(VibrationEffect.createWaveform(longArrayOf(0, 80, 40, 80), -1)) } catch (_: Exception) {}
    }

    private fun localIp(): String? = try {
        java.net.NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
            .firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address && it.isSiteLocalAddress }?.hostAddress
    } catch (_: Exception) { null }

    override fun onDestroy() {
        stopCarry("Stopped")
        discoveries.forEach { try { nsd.stopServiceDiscovery(it) } catch (_: Exception) {} }
        landmarker?.close(); exec.shutdown()
        Hub.running = false; Hub.handText = "service stopped"; Hub.remoteName = null; Hub.nearby = emptyList()
        Hub.say("Stopped watching")
        super.onDestroy()
    }
}
