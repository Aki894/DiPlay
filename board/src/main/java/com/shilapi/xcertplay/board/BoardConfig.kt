package com.shilapi.xcertplay.board

import android.content.Context
import org.json.JSONObject
import java.security.SecureRandom

/** Explicit immutable settings; UI geometry never changes the advertised CarPlay canvas. */
data class BoardConfig(
    val wireless: Boolean = false, val phone: String = "", val hotspotMode: String = "LOCAL_ONLY_HOTSPOT",
    val width: Int = 640, val height: Int = 480, val fps: Int = 30,
    val microphone: Boolean = false, val autoStart: Boolean = true, val webLan: Boolean = false,
    val p2pChannel: Int = 0, val ssid: String = "", val passphrase: String = "", val band: String = "GHZ_2_4",
) {
    init {
        require(width in 320..1920 && height in 240..1080 && width % 2 == 0 && height % 2 == 0)
        require(fps in 10..60)
        require(phone.isEmpty() || Regex("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}").matches(phone))
        require(hotspotMode in setOf("LOCAL_ONLY_HOTSPOT", "WIFI_P2P", "MANUAL"))
        require(band in setOf("AUTO", "GHZ_2_4", "GHZ_5"))
        require(com.shilapi.xcertplay.network.WifiP2pChannels.isValid(p2pChannel))
        require(ssid.toByteArray().size <= 32 && '\u0000' !in ssid)
        require(passphrase.isEmpty() || passphrase.length in 8..63)
        require(hotspotMode != "MANUAL" || (ssid.isNotBlank() && passphrase.length in 8..63))
    }
    fun json(revealSecret: Boolean = false): JSONObject = JSONObject().put("wireless", wireless).put("phone", phone)
        .put("hotspotMode", hotspotMode).put("width", width).put("height", height).put("fps", fps)
        .put("microphone", microphone).put("autoStart", autoStart).put("webLan", webLan).put("p2pChannel", p2pChannel)
        .put("ssid", ssid).put("band", band).also { if (revealSecret) it.put("passphrase", passphrase) }
    companion object {
        fun restoreUnconfirmed(c: Context) {
            val prefs=c.getSharedPreferences("board",0)
            prefs.getString("previous",null)?.let {
                save(c,parse(JSONObject(it)))
                check(prefs.edit().remove("previous").putInt("revision",prefs.getInt("revision",0)+1).commit())
            }
        }
        fun parse(j: JSONObject): BoardConfig {
            val allowed = setOf("wireless","phone","hotspotMode","width","height","fps","microphone","autoStart","webLan","p2pChannel","ssid","passphrase","band")
            require(j.keys().asSequence().all { it in allowed }) { "Unknown setting" }
            return BoardConfig(j.getBoolean("wireless"),j.getString("phone"),j.getString("hotspotMode"),
                j.getInt("width"),j.getInt("height"),j.getInt("fps"),j.getBoolean("microphone"),j.getBoolean("autoStart"),
                j.getBoolean("webLan"),j.getInt("p2pChannel"),j.getString("ssid"),j.getString("passphrase"),j.getString("band"))
        }
        fun load(c: Context): BoardConfig = runCatching {
            parse(JSONObject(c.getSharedPreferences("board",0).getString("config", null) ?: return BoardConfig()))
        }.getOrDefault(BoardConfig())
        fun save(c: Context, config: BoardConfig) {
            check(c.getSharedPreferences("board",0).edit().putString("config",config.json(true).toString()).commit())
        }
        fun token(c: Context): String {
            val file = java.io.File(c.noBackupFilesDir,"web-token")
            if (!file.exists()) {
                val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
                check(file.createNewFile())
                file.setReadable(false,false); file.setReadable(true,true)
                file.writeText(bytes.joinToString("") { "%02x".format(it.toInt() and 255) })
            }
            return file.readText().trim()
        }
    }
}
