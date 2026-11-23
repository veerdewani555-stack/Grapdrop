package com.example.grabdrop

import android.content.Context
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

    /** Devices currently carrying files for us: (device name, file label). Shown on the radar. */
    @Volatile var nearby: List<Pair<String, String>> = emptyList()

    /** One finished transfer. dir = "in" or "out". uri is only set for received files we can reopen. */
    class Entry(val dir: String, val name: String, val mime: String, val uri: String?, val peer: String, val time: Long)
    val history = CopyOnWriteArrayList<Entry>()   // newest first
    @Volatile var historyVersion = 0

    fun addHistory(ctx: Context, e: Entry) {
        history.add(0, e)
        while (history.size > 40) history.removeAt(history.size - 1)
        historyVersion++
        try {
            ctx.getSharedPreferences("grabdrop", Context.MODE_PRIVATE).edit().putString("history",
                history.joinToString("\u0002") { listOf(it.dir, it.name, it.mime, it.uri ?: "", it.peer, it.time.toString()).joinToString("\u0001") }).apply()
        } catch (_: Exception) {}
        onChange?.invoke()
    }

    fun loadHistory(ctx: Context) {
        if (history.isNotEmpty()) return
        val raw = ctx.getSharedPreferences("grabdrop", Context.MODE_PRIVATE).getString("history", "") ?: ""
        raw.split("\u0002").filter { it.isNotBlank() }.forEach {
            val f = it.split("\u0001")
            if (f.size >= 6) history.add(Entry(f[0], f[1], f[2], f[3].ifBlank { null }, f[4], f[5].toLongOrNull() ?: 0L))
        }
        historyVersion++
    }

    /** Devices the user chose "Always" for: persistent device id -> display name. */
    val trusted = java.util.concurrent.ConcurrentHashMap<String, String>()
    @Volatile var trustedVersion = 0

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("grabdrop", Context.MODE_PRIVATE)

    /** This phone's own persistent id; sent with every request so other devices can recognise it. */
    fun myId(ctx: Context): String {
        val p = prefs(ctx)
        return p.getString("myid", null) ?: java.util.UUID.randomUUID().toString().also { p.edit().putString("myid", it).apply() }
    }

    private fun saveTrusted(ctx: Context) {
        prefs(ctx).edit().putString("trusted", trusted.entries.joinToString("\u0002") { it.key + "\u0001" + it.value }).apply()
    }

    fun loadTrusted(ctx: Context) {
        if (trusted.isNotEmpty()) return
        (prefs(ctx).getString("trusted", "") ?: "").split("\u0002").filter { it.isNotBlank() }.forEach {
            val f = it.split("\u0001")
            if (f.size >= 2) trusted[f[0]] = f[1]
        }
        trustedVersion++
    }

    fun trust(ctx: Context, id: String, name: String) { trusted[id] = name; saveTrusted(ctx); trustedVersion++; onChange?.invoke() }
    fun forget(ctx: Context, id: String) { trusted.remove(id); saveTrusted(ctx); trustedVersion++; onChange?.invoke() }

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
