package com.shilapi.xcertplay.board

import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import org.json.JSONArray
import java.security.MessageDigest
import java.util.UUID

/** Control plane only; never receives media or executes a caller-supplied command line. */
class BoardWebServer(private val service: BoardService,lan: Boolean) : NanoHTTPD(if(lan) "0.0.0.0" else "127.0.0.1",8765) {
    private val token=BoardConfig.token(service).toByteArray()
    private val lock=Any()
    @Volatile private var pending: String?=null
    private var previous: BoardConfig?=null
    private val timer=java.util.concurrent.ScheduledThreadPoolExecutor(1)
    private var rollback: java.util.concurrent.ScheduledFuture<*>?=null
    private val prefs=service.getSharedPreferences("board",0)
    override fun stop() { super.stop(); rollback?.cancel(false); timer.shutdownNow() }
    override fun serve(session: IHTTPSession): Response {
        try {
            if(session.uri=="/" && session.method==Method.GET) return response(Response.Status.OK,"text/html",
                service.assets.open("index.html").bufferedReader().use { it.readText() })
            if(!MessageDigest.isEqual(token,session.headers["authorization"].orEmpty().removePrefix("Bearer ").toByteArray()))
                return json(Response.Status.UNAUTHORIZED,JSONObject().put("error","Management token required"))
            val origin=session.headers["origin"]
            if(origin!=null && java.net.URI(origin).rawAuthority != session.headers["host"])
                return json(Response.Status.FORBIDDEN,JSONObject().put("error","Cross-origin request rejected"))
            val body=if(session.method==Method.POST) {
                require(session.headers["content-type"].orEmpty().startsWith("application/json")) { "JSON required" }
                require((session.headers["content-length"]?.toIntOrNull() ?: -1) in 0..8192) { "Invalid request size" }
                val parsed=HashMap<String,String>(); session.parseBody(parsed)
                JSONObject(parsed["postData"] ?: "{}")
            } else JSONObject()
            val result=when {
                session.method==Method.GET && session.uri=="/api/v1/status" -> {
                    CarLifeControl.call(service,"status")
                    service.status().put("configRevision",prefs.getInt("revision",0)).put("pendingConfig",pending)
                }
                session.method==Method.GET && session.uri=="/api/v1/config" -> BoardConfig.load(service).json().put("revision",prefs.getInt("revision",0))
                session.method==Method.GET && session.uri=="/api/v1/logs" -> JSONObject().put("lines",service.log.json())
                session.method==Method.GET && session.uri=="/api/v1/diagnostics/export" -> return response(Response.Status.OK,"text/plain",
                    service.status().toString(2)+"\n"+service.log.export())
                session.method==Method.GET && session.uri=="/api/v1/phones" -> {
                    if (service.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                        throw SecurityException("Bluetooth permission missing; run board provisioning")
                    val adapter=service.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter
                    JSONObject().put("phones",JSONArray(adapter?.bondedDevices.orEmpty().map { JSONObject().put("address",it.address).put("name",it.name ?: "") }))
                }
                session.method==Method.POST && session.uri=="/api/v1/config" -> synchronized(lock) {
                    require(pending==null) { "Confirm or restore pending settings first" }
                    require(body.getInt("revision")==prefs.getInt("revision",0)) { "Configuration changed; reload" }
                    val old=BoardConfig.load(service)
                    val nextJson=body.getJSONObject("config")
                    if(!nextJson.has("passphrase")) nextJson.put("passphrase",old.passphrase)
                    val next=BoardConfig.parse(nextJson)
                    previous=old; pending=UUID.randomUUID().toString()
                    check(prefs.edit().putString("previous",old.json(true).toString()).putInt("revision",prefs.getInt("revision",0)+1).commit())
                    BoardConfig.save(service,next); service.configChanged()
                    val lease=pending
                    rollback=timer.schedule({ synchronized(lock) { if(pending==lease) restore() } },60,java.util.concurrent.TimeUnit.SECONDS)
                    JSONObject().put("confirmId",pending).put("expiresSeconds",60).put("webBindingNeedsServiceRestart",old.webLan!=next.webLan)
                }
                session.method==Method.POST && session.uri=="/api/v1/config/confirm" -> synchronized(lock) {
                    require(body.getString("confirmId")==pending && pending!=null) { "No matching pending configuration" }
                    rollback?.cancel(false); pending=null; previous=null; prefs.edit().remove("previous").commit()
                    JSONObject().put("ok",true)
                }
                session.method==Method.POST && session.uri=="/api/v1/config/restore" -> synchronized(lock) { restore(); JSONObject().put("ok",true) }
                session.method==Method.POST && session.uri.startsWith("/api/v1/session/") -> {
                    val command=session.uri.substringAfterLast('/')
                    require(command in setOf("start","stop","reconnect"))
                    val side=body.optString("side","both"); require(side in setOf("phone","car","both"))
                    val car=if(side!="phone") CarLifeControl.call(service,command) else JSONObject()
                    if(side!="car") service.request(command)
                    JSONObject().put("accepted",true).put("car",car)
                }
                session.method==Method.POST && session.uri=="/api/v1/car/config" -> CarLifeControl.call(service,"config",body)
                session.method==Method.POST && session.uri=="/api/v1/phones/forget" -> {
                    val address=body.getString("address")
                    require(Regex("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}").matches(address))
                    service.request("stop")
                    service.maintenance("forget:$address")
                    JSONObject().put("accepted",true).put("phoneSessionStopped",true)
                }
                session.method==Method.POST && session.uri=="/api/v1/maintenance" -> {
                    service.maintenance(body.getString("action")); JSONObject().put("accepted",true)
                }
                else -> return json(Response.Status.NOT_FOUND,JSONObject().put("error","Unknown endpoint"))
            }
            return json(Response.Status.OK,result)
        } catch(e: Exception) { return json(Response.Status.BAD_REQUEST,JSONObject().put("error",e.message?.take(256) ?: "Request failed")) }
    }
    private fun restore() {
        val old=previous ?: prefs.getString("previous",null)?.let { BoardConfig.parse(JSONObject(it)) }
        old?.let { BoardConfig.save(service,it); service.configChanged() }
        rollback?.cancel(false); pending=null; previous=null
        prefs.edit().remove("previous").putInt("revision",prefs.getInt("revision",0)+1).commit()
    }
    private fun json(status: Response.Status,value: JSONObject)=response(status,"application/json",value.toString())
    private fun response(status: Response.Status,mime: String,text: String)=newFixedLengthResponse(status,mime,text).apply {
        addHeader("Cache-Control","no-store"); addHeader("X-Content-Type-Options","nosniff")
        addHeader("Content-Security-Policy","default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; frame-ancestors 'none'")
    }
}
