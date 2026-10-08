package com.shilapi.xcertplay.board

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.*
import com.shilapi.xcertplay.*
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.media.CarLifeVideoBridge
import com.shilapi.xcertplay.network.CarPlayVpnService
import com.shilapi.xcertplay.orchestration.*
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Service owns every session resource. No Activity, display or browser is required. */
class BoardService : Service() {
    private val thread = HandlerThread("board-session")
    private lateinit var actor: Handler
    lateinit var log: BoardLog; private set
    @Volatile private var controller: CarPlayController? = null
    @Volatile var sink: AndroidMediaSink? = null; private set
    @Volatile private var desired = false
    @Volatile private var state = "idle"
    @Volatile private var error = ""
    @Volatile private var generation = 0
    @Volatile private var target: CarLifeVideoBridge.Target? = null
    private var discovery: CarLifeVideoBridge? = null
    private var web: BoardWebServer? = null
    private lateinit var wake: PowerManager.WakeLock
    private var retiring: CarPlayController? = null
    private var attempts = 0
    private val retry = Runnable { if (desired) replaceSession() }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate(); instance = this
        thread.start(); actor = Handler(thread.looper); log = BoardLog(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("board_bridge","CarPlay bridge",NotificationManager.IMPORTANCE_LOW))
        updateForeground(null)
        wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"WuKong:Session")
        wake.setReferenceCounted(false)
        BoardConfig.restoreUnconfirmed(this)
        web = BoardWebServer(this,BoardConfig.load(this).webLan).also { it.start(5000,false) }
        discovery = CarLifeVideoBridge(this,onTargetChanged = { next -> actor.post {
            if (target != next) { target = next; if (desired) replaceSession() }
        } })
        log.add("Service ready; preview disabled; API on port 8765")
    }
    private fun updateForeground(config: BoardConfig?) {
        val notification = Notification.Builder(this,"board_bridge")
            .setSmallIcon(android.R.drawable.stat_notify_sync).setContentTitle("WuKong CarPlay Bridge")
            .setContentText("Background bridge and local management").setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Use explicit types: service boot must not activate location/microphone
            // before provisioning has granted their runtime permissions.
            var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            if (config?.microphone == true) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            if (config?.wireless == true && Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2)
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            startForeground(1,notification,types)
            log.add("Foreground service types=0x" + types.toString(16))
        } else startForeground(1,notification)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val command = intent?.getStringExtra("command")
        when (command) {
            "stop" -> request("stop")
            "reconnect" -> request("reconnect")
            "manage" -> Unit
            else -> if (command == "start" || getSharedPreferences("board",0).getBoolean("requested",BoardConfig.load(this).autoStart)) request("start")
        }
        return START_STICKY
    }
    fun request(command: String) {
        require(command in setOf("start","stop","reconnect"))
        actor.post {
            desired = command != "stop"
            getSharedPreferences("board",0).edit().putBoolean("requested",desired).apply()
            actor.removeCallbacks(retry)
            if (desired) replaceSession() else { closeSession(); updateForeground(null); state = "idle" }
        }
    }
    fun configChanged() { actor.post { if (desired) replaceSession() } }
    private fun missingPermissions(c: BoardConfig): List<String> {
        val needed = mutableListOf<String>()
        if (c.microphone) needed += Manifest.permission.RECORD_AUDIO
        if (c.wireless) needed += listOf(Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.NEARBY_WIFI_DEVICES)
        // Boot and web requests have no visible Activity. Android 11+ also
        // restricts location access for foreground services started in background.
        if (c.wireless && Build.VERSION.SDK_INT in 29..32)
            needed += Manifest.permission.ACCESS_BACKGROUND_LOCATION
        return needed.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
    }
    private fun replaceSession() {
        actor.removeCallbacks(retry); closeSession()
        if (!desired) return
        retiring?.let {
            if (!it.awaitClosed(5000)) { fail("Previous transport teardown pending"); return }
            retiring=null
        }
        val cfg = BoardConfig.load(this)
        val missing = missingPermissions(cfg)
        if (missing.isNotEmpty()) { fail("Permissions missing: ${missing.joinToString()}"); return }
        if (!cfg.wireless && CarPlayVpnService.prepare(this) != null) { fail("VPN not provisioned; run board installer"); return }
        try {
            updateForeground(cfg)
            DiPlayBootstrap.ensure(this)
            val identity = AirPlayPersistence.loadIdentity(this)
            val deviceId = DiPlayBootstrap.deviceId(identity)
            val bluetoothAddress=DiPlayBluetooth.localAddress(this)
            if (cfg.wireless && bluetoothAddress==null) {
                fail("Bluetooth controller address unavailable; waiting for board provisioning");return
            }
            val size = target
            val width = size?.width ?: cfg.width; val height = size?.height ?: cfg.height
            val fps = size?.fps ?: cfg.fps
            val gen = ++generation
            val renderer = AndroidMediaSink(context = this,videoWidth = width,videoHeight = height,
                forwardingOnly = true,onAudioDiagnostic = log::add)
            sink = renderer
            val air = AirPlayConfig(deviceName="WuKongPi",deviceId=deviceId,
                btMac=bluetoothAddress ?: deviceId,sourceVersion="950.7.1",
                main=AirPlayDisplayConfig(width,height,fps=fps,primaryInputDevice=3,safeArea=AirPlayInsets()),
                microphone=cfg.microphone,manufacturer="WuKongPi",model="Hi-Zero",icons=emptyList())
            val runtime = CarPlayRuntimeConfig(mfiTarget=MfiTarget.LOCAL,
                identification=Iap2IdentificationConfig("WuKongPi","Hi-Zero","WuKongPi",
                    "WUKONG-"+deviceId.replace(":",""),"1.0","1.0",3),
                label="WuKongPi",hostName="wukong-"+deviceId.replace(":","").lowercase(),
                hostMac=deviceId.split(":").map { it.toInt(16).toByte() }.toByteArray(),
                transport=if(cfg.wireless) CarPlayTransport.WIRELESS else CarPlayTransport.WIRED,
                wirelessBluetoothDeviceAddress=cfg.phone.ifBlank { null },
                wirelessHotspotMode=WirelessHotspotMode.valueOf(cfg.hotspotMode),wifiP2pPreferredChannel=cfg.p2pChannel,
                manualHotspotSsid=cfg.ssid,manualHotspotPassphrase=cfg.passphrase,
                manualHotspotBand=ManualHotspotBand.valueOf(cfg.band))
            val listener = object : AirPlaySessionListener {
                override fun onSessionActive(session: AirPlaySession) { actor.post { if(gen==generation) { state="active"; error=""; attempts=0 } } }
                override fun onSessionEnded(session: AirPlaySession) { actor.post { if(gen==generation && desired) fail("CarPlay session ended") } }
                override fun onTransportError(message: String) { actor.post { if(gen==generation && desired) fail(message) } }
                override fun onDebugLog(message: String) { log.add(message) }
            }
            controller = CarPlayController(this,runtime,air,identity,AirPlayPersistence.loadPairings(this) { id,key ->
                AirPlayPersistence.savePairing(this,id,key) },listener,CarPlayMediaEngine(renderer,cfg.microphone),
                reportStatus={ next -> actor.post { if(gen==generation) {
                    state=next.javaClass.simpleName
                    if(next is CarPlayStatus.Failed) fail(next.message, !next.wifiResetRequired)
                    if(next is CarPlayStatus.HotspotReady) log.add("Hotspot ready address=${next.address} backend=${next.backend} band=${next.band}")
                } } },loadPairRecord={ AirPlayPersistence.loadLockdownRecord(this) },
                savePairRecord={ AirPlayPersistence.saveLockdownRecord(this,it) },clearPairRecord={ AirPlayPersistence.clearLockdownRecord(this) })
            wake.acquire(30*60*1000L)
            actor.postDelayed(renewWake,10*60*1000L)
            state="starting"; error=""
            controller!!.start()
            log.add("Session generation=$gen ${width}x$height fps=$fps wireless=${cfg.wireless} forwardOnly=true")
        } catch (e: Exception) { closeSession(); fail("${e.javaClass.simpleName}: ${e.message}") }
    }
    private val renewWake = object : Runnable { override fun run() {
        if(desired && controller!=null) { wake.acquire(30*60*1000L); actor.postDelayed(this,10*60*1000L) }
    } }
    private fun fail(message: String, retryAllowed: Boolean = true) {
        error=message.take(1024); state="error"; log.add(error)
        actor.removeCallbacks(retry)
        if(desired && retryAllowed) actor.postDelayed(retry,(5000L shl attempts.coerceAtMost(3)).coerceAtMost(30000L))
        attempts++
    }
    private fun closeSession() {
        generation++
        actor.removeCallbacks(renewWake)
        controller?.let {
            runCatching { it.close() }
            if (!it.awaitClosed(5000)) retiring=it
        }; controller=null
        sink?.let { runCatching { it.close() } }; sink=null
        if(wake.isHeld) wake.release()
    }
    fun status(): JSONObject {
        val memory = android.os.Debug.MemoryInfo().also { android.os.Debug.getMemoryInfo(it) }
        val stats=sink?.mediaStats() ?: longArrayOf(0,0,0,0)
        return JSONObject().put("version",BuildConfig.VERSION_NAME).put("state",state).put("error",error)
            .put("requested",desired).put("generation",generation).put("uptimeMs",SystemClock.elapsedRealtime())
            .put("pssKiB",memory.totalPss).put("heapUsedBytes",Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory())
            .put("metadataDropped",log.dropped.get()).put("heapLimitBytes",Runtime.getRuntime().maxMemory()).put("videoFrames",stats[0]).put("videoBytes",stats[1])
            .put("videoDecoders",stats[2]).put("audioStreams",stats[3]).put("preview",stats[2]>0)
            .put("carTarget",target?.let { "${it.width}x${it.height}@${it.fps}" } ?: "not connected")
            .put("permissionsMissing",JSONArray(missingPermissions(BoardConfig.load(this))))
            .put("carLife",CarLifeControl.status(this))
            .put("maintenance",runCatching { File(filesDir,"maintenance-result").readText().take(256) }.getOrDefault("root helper not yet used"))
    }
    fun maintenance(action: String) {
        require(action in setOf("reboot","pair","pair-stop","display-off","display-on") || Regex("forget:(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}").matches(action))
        File(filesDir,"maintenance-request").writeText(action)
        log.add("Maintenance requested: $action")
    }
    override fun onDestroy() {
        instance=null; web?.stop(); discovery?.close(); discovery=null
        actor.removeCallbacksAndMessages(null)
        actor.post { closeSession(); log.close(); thread.quitSafely() }
        super.onDestroy()
    }
    companion object { @Volatile var instance: BoardService? = null; private set }
}

class BoardBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context,intent: Intent) {
        if(intent.action==Intent.ACTION_BOOT_COMPLETED) context.startForegroundService(Intent(context,BoardService::class.java).putExtra("command","boot"))
    }
}
class BoardControlActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val command=intent.getStringExtra("command") ?: "start"
        if(command in setOf("start","stop","reconnect","manage")) startForegroundService(Intent(this,BoardService::class.java).putExtra("command",command))
        finish()
    }
}
class BoardUsbActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if(intent.action==android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED &&
            !BoardConfig.load(this).wireless && getSharedPreferences("board",0).getBoolean("requested",BoardConfig.load(this).autoStart)) {
            startForegroundService(Intent(this,BoardService::class.java).putExtra("command","start"))
        }
        finish()
    }
}
