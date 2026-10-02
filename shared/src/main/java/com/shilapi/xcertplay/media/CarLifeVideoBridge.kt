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
import com.shilapi.xcertplay.airplay.VideoCodec
import java.io.Closeable
import java.io.DataOutputStream
import java.util.ArrayDeque

/** Local CarLife display discovery and bounded H.264 forwarding. No video-sized Binder parcels. */
class CarLifeVideoBridge(
    context: Context,
    private val width: Int = 0,
    private val height: Int = 0,
    private val report: (String) -> Unit = {},
    private val requestKeyFrame: () -> Unit = {},
    private val onTargetChanged: (Target?) -> Unit = {},
) : Closeable {
    data class Target(val width: Int, val height: Int, val fps: Int)
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val owner = Binder()
    private val lock = Object()
    private data class Frame(val target: DataOutputStream, val config: ByteArray?, val bytes: ByteArray, val time: Long)
    private val queue = ArrayDeque<Frame>()
    private var queuedBytes = 0
    @Volatile private var remote: IBinder? = null
    @Volatile private var closed = false
    @Volatile private var ready = false
    @Volatile private var target: Target? = null
    private var bound = false
    private var output: DataOutputStream? = null
    private var config: ByteArray? = null
    private var waitingForKey = true
    private var lastRecovery = -1L
    private var recoverySequence = 0
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            remote = service; main.removeCallbacks(poll); main.post(poll)
        }
        override fun onServiceDisconnected(name: ComponentName) { remote = null; ready = false; disconnect() }
        override fun onBindingDied(name: ComponentName) {
            onServiceDisconnected(name)
            if (bound) { runCatching { app.unbindService(this) }; bound = false }
            main.postDelayed(bind, 2000)
        }
        override fun onNullBinding(name: ComponentName) = onBindingDied(name)
    }
    private val bind = object : Runnable {
        override fun run() {
            if (closed || bound) return
            bound = runCatching { app.bindService(Intent().setComponent(ComponentName(
                "com.projection.car", "com.projection.car.CarPlayAudioBridgeService",
            )), connection, Context.BIND_AUTO_CREATE) }.getOrDefault(false)
            if (!bound) main.postDelayed(this, 2000)
        }
    }
    private fun <T> transact(service: IBinder, code: Int, fill: (Parcel) -> Unit, read: (Parcel) -> T): T {
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken("com.projection.car.PcmBridge.v1"); fill(data)
            check(service.transact(code, data, reply, 0)) { "Video bridge unavailable" }
            reply.readException(); return read(reply)
        } finally { data.recycle(); reply.recycle() }
    }
    private val poll = object : Runnable {
        override fun run() {
            if (closed) return
            val state = remote?.let { service -> runCatching {
                transact(service, IBinder.FIRST_CALL_TRANSACTION + 4, {}, { IntArray(5) { _ -> it.readInt() } })
            }.getOrNull() }
            val next = state?.takeIf { it[1] in 16..4096 && it[2] in 16..4096 }
                ?.let { Target(it[1], it[2], it[3].coerceIn(1, 60)) }
            if (target != next) {
                target = next; disconnect()
                if (next != null) report("CarLife video: car target=${next.width}x${next.height} fps=${next.fps}")
                onTargetChanged(next)
            }
            ready = state != null && state[0] != 0 && next != null && next.width == width && next.height == height
            if (!ready) disconnect()
            if (state != null && recoverySequence != state[4]) { recoverySequence = state[4]; recover() }
            main.postDelayed(this, 500)
        }
    }
    private val worker = Thread({
        while (!closed) {
            val frame = synchronized(lock) {
                while (queue.isEmpty() && !closed) lock.wait()
                if (closed) null else queue.removeFirst().also { queuedBytes -= it.bytes.size }
            } ?: break
            if (synchronized(lock) { output !== frame.target }) continue
            if (SystemClock.elapsedRealtime() - frame.time > 250) {
                synchronized(lock) { queue.clear(); queuedBytes = 0; waitingForKey = true }
                recover(); continue
            }
            try {
                frame.config?.let { frame.target.writeInt(1); frame.target.writeInt(it.size); frame.target.write(it) }
                frame.target.writeInt(2); frame.target.writeInt(frame.bytes.size); frame.target.write(frame.bytes)
            } catch (_: Exception) { disconnect(frame.target) }
        }
    }, "carlife-h264-writer").apply { isDaemon = true; start() }
    init { main.post(bind); main.post(poll) }

    fun configure(codec: VideoCodec, bytes: ByteArray) {
        disconnect()
        synchronized(lock) {
            config = if (codec == VideoCodec.H264) runCatching {
                H264BridgeFrames.config(bytes).also {
                    val actual = H264BridgeFrames.size(it)
                    require(actual[0] == width && actual[1] == height) { "SPS dimensions ${actual[0]}x${actual[1]} differ from $width x $height" }
                }
            }.onFailure { report("CarLife video: config rejected ${it.message}; screen projection fallback") }.getOrNull() else null
        }
        if (codec != VideoCodec.H264) report("CarLife video: H.265 unsupported; reconnect with car target for H.264")
    }
    fun submit(bytes: ByteArray) {
        if (closed || !ready || width == 0 || bytes.size > H264BridgeFrames.MAX_FRAME) return
        val service = remote ?: return
        synchronized(lock) {
            val parameters = config ?: return
            val key = runCatching { H264BridgeFrames.keyFrame(bytes) }.getOrDefault(false)
            if (output == null) {
                val fd = runCatching { transact(service, IBinder.FIRST_CALL_TRANSACTION + 3, {
                    it.writeStrongBinder(owner); it.writeInt(width); it.writeInt(height)
                }, { if (it.readInt() == 0) null else ParcelFileDescriptor.CREATOR.createFromParcel(it) }) }.getOrNull() ?: return
                output = DataOutputStream(ParcelFileDescriptor.AutoCloseOutputStream(fd))
                waitingForKey = true; recover()
                report("CarLife video: pipe opened=$width x $height; waiting for IDR")
            }
            if (waitingForKey && !key) return
            if (queue.size >= 6 || queuedBytes > H264BridgeFrames.MAX_FRAME - bytes.size) {
                queue.clear(); queuedBytes = 0; waitingForKey = true; recover()
                report("CarLife video: queue bounded; waiting for IDR")
                if (!key) return
            }
            waitingForKey = false
            queue.addLast(Frame(output!!, if (key) parameters else null, bytes.copyOf(), SystemClock.elapsedRealtime()))
            queuedBytes += bytes.size; lock.notifyAll()
        }
    }
    private fun recover() {
        synchronized(lock) {
            val now = SystemClock.elapsedRealtime()
            if (width == 0 || (lastRecovery >= 0 && now - lastRecovery < 500)) return
            lastRecovery = now
        }
        requestKeyFrame()
    }
    private fun disconnect(expected: DataOutputStream? = null) {
        val old = synchronized(lock) {
            if (expected != null && output !== expected) return
            output.also { output = null; queue.clear(); queuedBytes = 0; waitingForKey = true }
        }
        runCatching { old?.close() }
        if (old != null) {
            remote?.let { service -> runCatching {
                transact(service, IBinder.FIRST_CALL_TRANSACTION + 5, { it.writeStrongBinder(owner) }, {})
            } }
            report("CarLife video: pipe closed; screen projection fallback")
        }
    }
    override fun close() {
        closed = true; ready = false
        main.removeCallbacks(bind); main.removeCallbacks(poll)
        disconnect(); synchronized(lock) { lock.notifyAll() }
        main.post { if (bound) runCatching { app.unbindService(connection) }; bound = false; remote = null }
    }
}
