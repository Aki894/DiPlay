package com.shilapi.xcertplay

import android.content.Context
import java.io.File

/** The ordinary app never asks for privileged LOCAL_MAC_ADDRESS access. */
internal object DiPlayBluetooth {
    fun localAddress(context: Context): String? = runCatching {
        File(context.filesDir,"board-bluetooth-address").readText().trim().takeIf {
            Regex("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}").matches(it) &&
                !it.startsWith("02:00:00:00:00:") && it != "00:00:00:00:00:00"
        }
    }.getOrNull()
}
