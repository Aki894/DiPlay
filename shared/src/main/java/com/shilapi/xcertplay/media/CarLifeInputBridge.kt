package com.shilapi.xcertplay.media

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Small HID batches polled off the UI thread; bound to the controller, not its Activity. */
class CarLifeInputBridge(context: Context, private val available: () -> Boolean, private val canPoll: () -> Boolean,
    private val dispatch: (List<IntArray>) -> Unit) : Closeable {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val owner = Binder()
    @Volatile private var remote: IBinder? = null
    @Volatile private var closed = false
    private var bound = false
    private var reportedInput = false
    private var lastErrorAt = 0L
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) { remote = service }
        override fun onServiceDisconnected(name: ComponentName) { remote = null }
        override fun onBindingDied(name: ComponentName) {
            remote = null
            if (bound) { runCatching { app.unbindService(this) }; bound = false }
            main.postDelayed(bind, 1000)
        }
        override fun onNullBinding(name: ComponentName) = onBindingDied(name)
    }
    private val bind = object : Runnable {
        override fun run() {
            if (closed || bound) return
            bound = runCatching { app.bindService(Intent().setComponent(ComponentName(
                "com.projection.car", "com.projection.car.CarPlayAudioBridgeService")),
                connection, Context.BIND_AUTO_CREATE) }.getOrDefault(false)
            if (!bound) main.postDelayed(this, 2000)
        }
    }
    private fun transact(service: IBinder, close: Boolean = false): List<IntArray> {
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken("com.projection.car.PcmBridge.v1"); data.writeStrongBinder(owner)
            if (!close) data.writeInt(if (available()) 1 else 0)
            check(service.transact(IBinder.FIRST_CALL_TRANSACTION + if (close) 7 else 6, data, reply, 0))
            reply.readException()
            if (close) return emptyList()
            val count = reply.readInt(); require(count in 0..65)
            return List(count) { IntArray(4) { reply.readInt() } }
        } finally { data.recycle(); reply.recycle() }
    }
    private val worker = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "carlife-input-poll").apply { isDaemon = true }
    }
    init {
        main.post(bind)
        worker.scheduleWithFixedDelay({
            if (!closed && canPoll()) remote?.let { service -> runCatching {
                val events = transact(service)
                if (events.isNotEmpty()) {
                    if (!reportedInput && events.any { it[0] != 4 }) {
                        reportedInput = true; android.util.Log.i("CarPlay", "CarLife input: native HID events received")
                    }
                    dispatch(events)
                }
            }.onFailure {
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastErrorAt > 5000) { lastErrorAt = now; android.util.Log.w("CarPlay", "CarLife input bridge: ${it.message}") }
            } }
        }, 0, 16, TimeUnit.MILLISECONDS)
    }
    override fun close() {
        closed = true; main.removeCallbacks(bind)
        val service = remote
        worker.execute { service?.let { runCatching { transact(it, true) } } }
        worker.shutdown()
        main.post { if (bound) runCatching { app.unbindService(connection) }; bound = false; remote = null }
    }
}
