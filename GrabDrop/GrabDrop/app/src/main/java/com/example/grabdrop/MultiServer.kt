package com.example.grabdrop

import android.content.ContentResolver
import android.net.Uri
import java.net.ServerSocket
import java.net.Socket
import java.net.URLConnection
import java.net.URLDecoder
import java.util.Collections
import kotlin.concurrent.thread

/**
 * Serves /list and /file?i=N. A request needs the secret token AND the first request from each
 * device IP must be approved by the user (ask() blocks until they tap Allow/Deny).
 */
class MultiServer(
    private val cr: ContentResolver,
    private val files: List<Pair<Uri, String>>,
    private val token: String,
    private val ask: (ip: String, device: String) -> Boolean,
    private val onProgress: (Int, Int) -> Unit,
    private val onAllServed: () -> Unit
) {
    private val socket = ServerSocket(0)
    private val served = Collections.synchronizedSet(mutableSetOf<Int>())
    private val allowed = Collections.synchronizedSet(mutableSetOf<String>())
    private val denied = Collections.synchronizedSet(mutableSetOf<String>())
    private val askLock = Any()
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
        if (!Regex("[?&]t=$token(&|\\s|$)").containsMatchIn(first)) { reply("403 Forbidden"); return@use }

        val ip = s.inetAddress.hostAddress ?: ""
        if (ip !in allowed) {
            if (ip in denied) { reply("403 Forbidden"); return@use }
            synchronized(askLock) {
                if (ip !in allowed && ip !in denied) {
                    val dev = Regex("[?&]d=([^&\\s]+)").find(first)?.groupValues?.get(1)
                        ?.let { URLDecoder.decode(it, "UTF-8") } ?: ip
                    if (ask(ip, dev)) allowed.add(ip) else denied.add(ip)
                }
            }
            if (ip !in allowed) { reply("403 Forbidden"); return@use }
        }

        if (first.startsWith("GET /list")) { reply("200 OK", files.joinToString("\n") { it.second }); return@use }
        val i = Regex("i=(\\d+)").find(first)?.groupValues?.get(1)?.toInt() ?: 0
        if (i >= files.size) { reply("404 Not Found"); return@use }
        val (uri, name) = files[i]
        val mime = cr.getType(uri) ?: URLConnection.guessContentTypeFromName(name) ?: "application/octet-stream"
        out.write("HTTP/1.1 200 OK\r\nContent-Type: $mime\r\nConnection: close\r\n\r\n".toByteArray())
        cr.openInputStream(uri)?.use { it.copyTo(out) }
        out.flush()
        served.add(i)
        onProgress(served.size, files.size)
        if (served.size >= files.size) onAllServed()
    }

    fun stop() { try { socket.close() } catch (_: Exception) {} }
}
