package com.shilapi.xcertplay.board

import android.content.Context
import org.json.JSONArray
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Bounded metadata. Media callbacks never wait for storage and never retain payloads. */
class BoardLog(c: Context) {
    private val lines = ArrayDeque<String>()
    private var bytes = 0
    private val file = File(c.filesDir,"board.log")
    private val queue = ArrayBlockingQueue<String>(256)
    private val closed = AtomicBoolean()
    val dropped = AtomicLong()
    private val redact = Regex("(?i)(passphrase|password|token|key)=[^ ,;]+")
    init {
        // Preserve a bounded tail from the previous process for post-crash diagnostics.
        runCatching {
            if(file.isFile) java.io.RandomAccessFile(file,"r").use { input ->
                val start=(input.length()-64*1024).coerceAtLeast(0)
                input.seek(start)
                val tail=ByteArray((input.length()-start).toInt())
                input.readFully(tail)
                tail.toString(Charsets.UTF_8).lineSequence().drop(if(start>0)1 else 0).filter { it.isNotBlank() }.forEach {
                    val line=it.take(2048);lines.addLast(line);bytes+=line.length*2
                }
            }
        }
    }
    private val writer = Thread({
        while (!closed.get() || queue.isNotEmpty()) {
            val line = queue.poll(250,TimeUnit.MILLISECONDS) ?: continue
            runCatching {
                if (file.length() > 2 * 1024 * 1024) {
                    File(file.parentFile,"board.log.2").delete()
                    File(file.parentFile,"board.log.1").renameTo(File(file.parentFile,"board.log.2"))
                    file.renameTo(File(file.parentFile,"board.log.1"))
                }
                file.appendText("$line\n")
            }.onFailure { android.util.Log.w("WuKongBridge","Metadata storage unavailable") }
        }
    },"board-metadata").apply { isDaemon=true; start() }
    @Synchronized fun add(message: String) {
        if (closed.get() || message.startsWith("TRACE ")) return
        val line = "${System.currentTimeMillis()} ${message.take(1024).replace(redact,"$1=<redacted>")}"
        lines.addLast(line); bytes += line.length * 2
        while (bytes > 256 * 1024) bytes -= lines.removeFirst().length * 2
        android.util.Log.i("WuKongBridge",line)
        if (!queue.offer(line)) dropped.incrementAndGet()
    }
    fun close() { closed.set(true) }
    @Synchronized fun json() = JSONArray(lines.toList().takeLast(100))
    @Synchronized fun export() = lines.joinToString("\n")
}
