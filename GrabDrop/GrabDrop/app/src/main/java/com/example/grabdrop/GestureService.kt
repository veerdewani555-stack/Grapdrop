package com.example.grabdrop

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.MediaStore
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.hypot

class GestureService : LifecycleService() {
    private lateinit var landmarker: HandLandmarker
    private val exec = Executors.newSingleThreadExecutor()
    private var lastFrame = 0L
    private var candidate: String? = null
    private var since = 0L
    private var confirmed: String? = null
    private var lastOpen = 0L
    private var server: FileServer? = null
    private var regListener: NsdManager.RegistrationListener? = null
    private var carryUntil = 0L

    private companion object {
        const val HOLD_MS = 500L      // how long a pose must be held
        const val WINDOW_MS = 5000L   // open palm -> fist must happen within this time
        const val CARRY_MS = 60_000L  // carried item expires after a minute
    }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("gd", "Grab & Drop", NotificationManager.IMPORTANCE_LOW))
        val n = notification("Watching for your hand…")
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        else startForeground(1, n)

        landmarker = HandLandmarker.createFromOptions(this, HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath("hand_landmarker.task").build())
            .setRunningMode(RunningMode.IMAGE).setNumHands(1).build())

        val f = ProcessCameraProvider.getInstance(this)
        f.addListener({
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            analysis.setAnalyzer(exec) { analyze(it) }
            f.get().unbindAll()
            f.get().bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun notification(text: String): Notification =
        Notification.Builder(this, "gd").setContentTitle("Grab & Drop").setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_share).setOngoing(true).build()

    private fun setNote(t: String) =
        getSystemService(NotificationManager::class.java).notify(1, notification(t))

    // ---- gesture detection ----
    private fun analyze(p: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastFrame < 100) { p.close(); return }   // ~10 fps is plenty
        lastFrame = now
        try {
            val m = Matrix().apply { postRotate(p.imageInfo.rotationDegrees.toFloat()) }
            val raw = p.toBitmap()
            val bmp = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
            val g = landmarker.detect(BitmapImageBuilder(bmp).build()).landmarks().firstOrNull()?.let { classify(it) }
            onPose(g, now)
        } catch (_: Exception) {
        } finally { p.close() }
        if (server != null && now > carryUntil) stopCarry()
    }

    private fun classify(lm: List<NormalizedLandmark>): String? {
        fun d(a: NormalizedLandmark, b: NormalizedLandmark) = hypot((a.x() - b.x()).toDouble(), (a.y() - b.y()).toDouble())
        var ext = 0
        for ((tip, pip) in listOf(8 to 6, 12 to 10, 16 to 14, 20 to 18))
            if (d(lm[tip], lm[0]) > d(lm[pip], lm[0]) * 1.1) ext++
        return if (ext >= 4) "open" else if (ext <= 1) "fist" else null
    }

    private fun onPose(g: String?, now: Long) {
        if (g != candidate) { candidate = g; since = now }
        if (candidate != null && now - since >= HOLD_MS && candidate != confirmed) {
            confirmed = candidate
            if (confirmed == "fist" && now - lastOpen < WINDOW_MS && server == null) grab(now)
        }
        if (candidate == null && now - since > 1500) confirmed = null
        if (confirmed == "open") lastOpen = now
    }

    // ---- grab: pick the item, serve it, advertise it on the Wi-Fi ----
    private fun grab(now: Long) {
        val (uri, name) = Carry.shared?.let { Uri.fromFile(it) to it.name } ?: latestPhoto() ?: run {
            setNote("Nothing to grab (no photos found)"); return
        }
        val token = UUID.randomUUID().toString().take(8)
        val srv = FileServer(contentResolver, uri, name, token) { stopCarry() }.also { it.start() }
        server = srv; carryUntil = now + CARRY_MS

        val info = NsdServiceInfo().apply {
            serviceName = "GrabDrop-" + Build.MODEL; serviceType = "_grabdrop._tcp."; port = srv.port
            setAttribute("t", token); setAttribute("n", name)
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) {}
            override fun onRegistrationFailed(i: NsdServiceInfo, e: Int) {}
            override fun onServiceUnregistered(i: NsdServiceInfo) {}
            override fun onUnregistrationFailed(i: NsdServiceInfo, e: Int) {}
        }
        regListener = l
        getSystemService(Context.NSD_SERVICE).let { (it as NsdManager).registerService(info, NsdManager.PROTOCOL_DNS_SD, l) }
        buzz(); setNote("✊ Carrying $name — drop it on your laptop")
    }

    private fun stopCarry() {
        try { regListener?.let { (getSystemService(Context.NSD_SERVICE) as NsdManager).unregisterService(it) } } catch (_: Exception) {}
        regListener = null; server?.stop(); server = null
        setNote("Watching for your hand…")
    }

    private fun latestPhoto(): Pair<Uri, String>? {
        val c = contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME), null, null, "date_added DESC") ?: return null
        c.use { if (it.moveToFirst()) return Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, it.getLong(0).toString()) to it.getString(1) }
        return null
    }

    private fun buzz() {
        val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 80, 40, 80), -1))
    }

    override fun onDestroy() {
        stopCarry(); landmarker.close(); exec.shutdown(); super.onDestroy()
    }
}
