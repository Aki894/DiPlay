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
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** One explicit local Binder binding per CarPlay sink. The receiver's opt-in controls routing. */
internal class CarLifePcmBridge(context: Context?, private val report: (String) -> Unit) : Closeable {
    private val app = context?.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val routes = ConcurrentHashMap.newKeySet<Route>()
    @Volatile private var remote: IBinder? = null
    @Volatile private var closed = false
    private var bound = false
    private val connection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) { remote = service }
        override fun onServiceDisconnected(name: ComponentName) { remote = null; routes.forEach { it.disconnect() } }
        override fun onBindingDied(name: ComponentName) {
            remote = null
            routes.forEach { it.disconnect() }
            if (bound) { runCatching { app?.unbindService(this) }; bound = false }
            main.postDelayed(bind, 2000)
        }
        override fun onNullBinding(name: ComponentName) { onBindingDied(name) }
    }
    private val bind: Runnable = object : Runnable {
        override fun run() {
            if (closed || bound || app == null) return
            bound = runCatching {
                app.bindService(Intent().setComponent(ComponentName(
                    "com.projection.car", "com.projection.car.CarPlayAudioBridgeService",
                )), connection, Context.BIND_AUTO_CREATE)
            }.getOrDefault(false)
            if (!bound) main.postDelayed(this, 2000)
        }
    }
    init { main.post(bind) }
    fun route(audioType: String): Route = Route(audioType).also { routes.add(it) }

    private fun <T> transact(service: IBinder, code: Int, fill: (Parcel) -> Unit, read: (Parcel) -> T): T {
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR); fill(data)
            check(service.transact(code, data, reply, 0)) { "Unsupported bridge protocol" }
            reply.readException()
            return read(reply)
        } finally { data.recycle(); reply.recycle() }
    }

    private data class Block(val output: ParcelFileDescriptor.AutoCloseOutputStream, val pcm: ByteArray)

    inner class Route(private val audioType: String) : Closeable {
        private val owner = Binder()
        private val queue = LinkedBlockingQueue<Block>(6)
        @Volatile private var output: ParcelFileDescriptor.AutoCloseOutputStream? = null
        @Volatile private var routeClosed = false
        private var lastCheck = 0L
        private var normalizer: BridgePcmNormalizer? = null
        private var rate = 0
        private var channels = 0
        private var dropped = 0L
        private var reportedFailure = false
        private val worker = Thread({
            try {
                while (!routeClosed) {
                    val bytes = queue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                    if (output !== bytes.output) continue
                    try { bytes.output.write(bytes.pcm) }
                    catch (_: Exception) { disconnect(bytes.output) }
                }
            } catch (_: InterruptedException) { /* Renderer teardown. */ }
        }, "carlife-pcm-writer").apply { isDaemon = true; start() }

        /** Decoder worker only. Bounded backpressure preserves contiguous samples. */
        @Synchronized fun write(data: ByteArray, offset: Int, length: Int, sampleRate: Int, channelCount: Int): Boolean {
            if (closed || routeClosed) return false
            if (sampleRate !in 8000..48000 || channelCount !in 1..2) return false
            if (rate != sampleRate || channels != channelCount) {
                disconnect()
                rate = sampleRate; channels = channelCount
                normalizer = BridgePcmNormalizer(rate, channels)
                lastCheck = 0
            }
            val service = remote
            if (service == null) { disconnect(); return false }
            val now = SystemClock.elapsedRealtime()
            if (now - lastCheck >= 500) {
                lastCheck = now
                val ready = runCatching {
                    transact(service, IBinder.FIRST_CALL_TRANSACTION + 1, {}, { it.readInt() != 0 })
                }.getOrDefault(false)
                if (!ready) { disconnect(); return false }
                if (output == null) {
                    try {
                        val fd = transact(service, IBinder.FIRST_CALL_TRANSACTION, {
                            it.writeStrongBinder(owner); it.writeString(audioType)
                        }, {
                            if (it.readInt() == 0) null else ParcelFileDescriptor.CREATOR.createFromParcel(it)
                        }) ?: return false
                        output = ParcelFileDescriptor.AutoCloseOutputStream(fd)
                        normalizer = BridgePcmNormalizer(rate, channels)
                        reportedFailure = false
                        report("CarLife bridge: opened type=$audioType $rate/$channels -> 48000/stereo PCM16")
                    } catch (error: Exception) {
                        if (!reportedFailure) report("CarLife bridge unavailable: ${error.javaClass.simpleName}")
                        reportedFailure = true
                        disconnect(); return false
                    }
                }
            }
            val target = output ?: return false
            val converted = normalizer!!.convert(data, offset, length)
            // Small blocks bound the pending duration at 120ms and avoid a giant decoder burst.
            for (start in converted.indices step 3840) {
                val block = converted.copyOfRange(start, minOf(start + 3840, converted.size))
                // Keep sample order. The car's sample clock provides backpressure
                // through its bounded mixer and pipe, as AudioTrack did locally.
                val accepted = try { queue.offer(Block(target, block), 250, TimeUnit.MILLISECONDS) }
                    catch (_: InterruptedException) { Thread.currentThread().interrupt(); false }
                if (!accepted) {
                    report("CarLife bridge: PCM consumer stalled; restoring local playback")
                    disconnect(target); return false
                }
            }
            return true
        }

        @Synchronized fun disconnect(expected: ParcelFileDescriptor.AutoCloseOutputStream? = null) {
            if (expected != null && output !== expected) return
            val old = output
            output = null
            queue.clear()
            runCatching { old?.close() }
        }
        override fun close() {
            routeClosed = true
            disconnect()
            worker.interrupt()
            val service = remote
            if (service != null) runCatching {
                transact(service, IBinder.FIRST_CALL_TRANSACTION + 2, { it.writeStrongBinder(owner) }, {})
            }
            routes.remove(this)
        }
    }
    override fun close() {
        closed = true
        main.removeCallbacks(bind)
        routes.toList().forEach { it.close() }
        main.post {
            if (bound) runCatching { app?.unbindService(connection) }
            bound = false; remote = null
        }
    }
    private companion object { const val DESCRIPTOR = "com.projection.car.PcmBridge.v1" }
}
