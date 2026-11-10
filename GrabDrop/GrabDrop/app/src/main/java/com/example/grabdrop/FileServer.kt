package com.example.grabdrop

import android.content.ContentResolver
import android.net.Uri
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/** Tiny one-file HTTP server. Only answers requests that carry the secret token. */
class FileServer(
    private val cr: ContentResolver, private val uri: Uri, private val name: String,
    private val token: String, private val onServed: () -> Unit
) {
    private val socket = ServerSocket(0)
    val port get() = socket.localPort

    fun start() = thread(isDaemon = true) {
        try { while (true) handle(socket.accept()) } catch (_: Exception) {}
    }

    private fun handle(c: Socket) = c.use { s ->
        val reader = s.getInputStream().bufferedReader()
        val first = reader.readLine() ?: return@use
        while (true) { val l = reader.readLine(); if (l.isNullOrEmpty()) break }
        val out = s.getOutputStream()
        if (!first.contains("t=$token")) {
            out.write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); return@use
        }
        val mime = cr.getType(uri) ?: "application/octet-stream"
        out.write("HTTP/1.1 200 OK\r\nContent-Type: $mime\r\nContent-Disposition: attachment; filename=\"$name\"\r\nConnection: close\r\n\r\n".toByteArray())
        cr.openInputStream(uri)?.use { it.copyTo(out) }
        out.flush()
        onServed()
    }

    fun stop() { try { socket.close() } catch (_: Exception) {} }
}
