package com.shilapi.xcertplay.board

import android.content.*
import android.os.*
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.atomic.AtomicBoolean

/** Private local control channel, separately versioned from the existing media pipe protocol. */
object CarLifeControl {
    private const val DESCRIPTOR="com.projection.car.Board.v1"
    @Volatile private var cached=JSONObject().put("state","not queried")
    private val refreshPending=AtomicBoolean(false)
    // Binder transact has no caller timeout. Bound the waiting HTTP request and
    // the worker count even if the other application stops answering.
    private val worker=ThreadPoolExecutor(1,1,0L,TimeUnit.MILLISECONDS,ArrayBlockingQueue<Runnable>(1),
        java.util.concurrent.ThreadFactory { r -> Thread(r,"board-car-control").apply { isDaemon=true } })
    fun status(context: Context): JSONObject = cached
    fun refresh(context: Context) {
        if(!refreshPending.compareAndSet(false,true)) return
        try {
            worker.execute {
                try { cached=callDirect(context,"status") }
                catch(e: Exception) { cached=JSONObject().put("state","control unavailable").put("error",e.javaClass.simpleName) }
                finally { refreshPending.set(false) }
            }
        } catch(_: java.util.concurrent.RejectedExecutionException) { refreshPending.set(false) }
    }
    fun call(context: Context,command: String,config: JSONObject=JSONObject()): JSONObject {
        val task=try { worker.submit<JSONObject> { callDirect(context,command,config) } }
        catch(_: java.util.concurrent.RejectedExecutionException) {
            return JSONObject().put("state","control busy").put("accepted",false)
        }
        return try { task.get(4,TimeUnit.SECONDS) }
        catch(_: java.util.concurrent.TimeoutException) {
            // Do not interrupt an in-flight Binder operation or replay a command.
            // Remove a queued command; an already running command may still complete.
            task.cancel(false); worker.purge()
            JSONObject().put("state","control timeout").put("outcome","unknown; check status")
        } catch(e: Exception) {
            task.cancel(false); worker.purge()
            JSONObject().put("state","control unavailable").put("error",e.javaClass.simpleName)
        }
    }
    private fun callDirect(context: Context,command: String,config: JSONObject=JSONObject()): JSONObject {
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

