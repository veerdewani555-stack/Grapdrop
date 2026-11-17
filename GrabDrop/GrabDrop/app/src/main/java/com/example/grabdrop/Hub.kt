package com.example.grabdrop

import android.net.Uri
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/** State shared between MainActivity (UI) and GestureService (camera, gestures, transfers). */
object Hub {
    val items = CopyOnWriteArrayList<Pair<Uri, String>>()
    @Volatile var carrying: List<Pair<Uri, String>>? = null
    @Volatile var running = false
    @Volatile var holdMs = 500L
    @Volatile var keepCopy = false
    @Volatile var status = "Ready. Choose files, then open palm → fist to grab."
    @Volatile var handText = "service stopped"
    @Volatile var codeText = ""
    @Volatile var progress = -1
    @Volatile var remoteName: String? = null   // another device is carrying something for us
    @Volatile var onChange: (() -> Unit)? = null
    /** Animation events for the UI: grab, sent, drop, incoming, ask. */
    @Volatile var onFx: ((String) -> Unit)? = null

    private val log = ArrayDeque<String>()

    fun say(msg: String, addToLog: Boolean = true) {
        status = msg
        if (addToLog) synchronized(log) {
            log.addFirst(SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date()) + "  " + msg)
            while (log.size > 8) log.removeLast()
        }
        val fx = when {
            msg.startsWith("✊ Carrying") -> "grab"
            msg.startsWith("✅ Sent") -> "sent"
            msg.startsWith("✅ Received") -> "drop"
            msg.startsWith("📲") || msg.startsWith("💻") -> "incoming"
            msg.startsWith("🔐") -> "ask"
            else -> null
        }
        if (fx != null) onFx?.invoke(fx)
        onChange?.invoke()
    }

    fun logText(): String = synchronized(log) { log.joinToString("\n") }.ifEmpty { "Nothing yet" }
}
