package com.shilapi.xcertplay.board

import android.content.*
import android.os.*
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Private local control channel, separately versioned from the existing media pipe protocol. */
object CarLifeControl {
    private const val DESCRIPTOR="com.projection.car.Board.v1"
    @Volatile private var cached=JSONObject().put("state","not queried")
    fun status(context: Context): JSONObject = cached
    fun call(context: Context,command: String,config: JSONObject=JSONObject()): JSONObject {
        val connected=CountDownLatch(1)
        var binder: IBinder?=null
        val connection=object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName,service: IBinder) { binder=service; connected.countDown() }
            override fun onServiceDisconnected(name: ComponentName) { binder=null }
            override fun onNullBinding(name: ComponentName) { connected.countDown() }
        }
        val bound=context.bindService(Intent().setComponent(ComponentName("com.projection.car","com.projection.car.BoardSessionService")),connection,Context.BIND_AUTO_CREATE)
        if(!bound) return JSONObject().put("state","not installed or board service unavailable").also { cached=it }
        return try {
            if(!connected.await(3,TimeUnit.SECONDS)) return JSONObject().put("state","control timeout")
            val service=binder ?: return JSONObject().put("state","control unavailable")
            val data=Parcel.obtain(); val reply=Parcel.obtain()
            try {
                data.writeInterfaceToken(DESCRIPTOR); data.writeString(command); data.writeString(config.toString())
                check(service.transact(IBinder.FIRST_CALL_TRANSACTION,data,reply,0))
                reply.readException()
                JSONObject(reply.readString() ?: "{}").also { cached=it }
            } finally { data.recycle(); reply.recycle() }
        } finally { context.unbindService(connection) }
    }
}
