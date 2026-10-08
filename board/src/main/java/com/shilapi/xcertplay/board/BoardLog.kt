package com.shilapi.xcertplay.board

import android.content.Context
import org.json.JSONArray
import java.io.File
import java.util.ArrayDeque

/** Bounded metadata only. No raw protocol payloads, pairing keys or authentication assets. */
class BoardLog(c: Context) {
    private val lines = ArrayDeque<String>()
    private var bytes = 0
    private val file = File(c.filesDir,"board.log")
    @Synchronized fun add(message: String) {
        if (message.startsWith("TRACE ")) return
        val line = "${System.currentTimeMillis()} ${message.take(1024).replace(Regex("(?i)(passphrase|password|token|key)=[^ ,;]+"),"$1=<redacted>")}"
        lines.addLast(line); bytes += line.length * 2
        while (bytes > 256 * 1024) bytes -= lines.removeFirst().length * 2
        android.util.Log.i("WuKongBridge",line)
        // Metadata rate is low; rotate on a worker supplied by the session service.
        if (file.length() > 2 * 1024 * 1024) {
            File(cPath(),"board.log.2").delete()
            File(cPath(),"board.log.1").renameTo(File(cPath(),"board.log.2"))
            file.renameTo(File(cPath(),"board.log.1"))
        }
        runCatching { file.appendText("$line\n") }
    }
    fun close() = Unit
    private fun cPath() = file.parentFile!!
    @Synchronized fun json() = JSONArray(lines.toList().takeLast(100))
    @Synchronized fun export() = lines.joinToString("\n")
}
