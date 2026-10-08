package com.shilapi.xcertplay.board

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.io.File

/** Receive as the APK; the existing root helper performs the privileged confirmation. */
class BoardPairingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context,intent: Intent) {
        try {
            if(intent.action!=BluetoothDevice.ACTION_PAIRING_REQUEST) return
            val device=intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
            val variant=intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT,-1)
            val queued=queue(context,device.address,variant,SystemClock.elapsedRealtime())
            Log.i("WuKongProvision","Pairing request variant=$variant queued=$queued")
        } catch(e: Exception) { Log.w("WuKongProvision","Pairing request could not be queued",e) }
    }
    companion object {
        // Numeric comparison/consent only, inside the explicitly opened 120s window.
        // PIN entry and passkey entry continue to require their actual input.
        internal fun queue(context: Context,address: String,variant: Int,now: Long): Boolean {
            if(variant!=2 && variant!=3) return false
            if(!Regex("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}").matches(address)) return false
            val until=runCatching { File(context.filesDir,"pairing-window").readText().trim().toLong() }.getOrDefault(0L)
            if(now<0 || until<=now || until-now>120000) return false
            val file=File.createTempFile("pair-request-",".tmp",context.filesDir)
            try {
                file.writeText(JSONObject().put("address",address).put("variant",variant).put("until",until).toString())
                check(file.renameTo(File(context.filesDir,"pairing-request"))) { "Could not queue pairing request" }
            } finally { file.delete() }
            return true
        }
        fun status(context: Context): JSONObject {
            val now=SystemClock.elapsedRealtime()
            val until=runCatching { File(context.filesDir,"pairing-window").readText().trim().toLong() }.getOrDefault(0L)
            val remaining=(until-now).coerceIn(0,120000)
            return JSONObject().put("windowOpen",remaining>0).put("remainingSeconds",(remaining+999)/1000)
                .put("result",runCatching { File(context.filesDir,"pairing-result").readText().take(256) }.getOrDefault("not requested"))
        }
    }
}
