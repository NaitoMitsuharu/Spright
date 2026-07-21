package com.example.ble1507

import android.util.Log
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal const val TOUCHDESIGNER_IMU_INTERVAL_MS = 17L

enum class TouchDesignerConnectionStatus {
    Disconnected,
    Connecting,
    Connected,
    Error,
}

data class TouchDesignerConnectionState(
    val status: TouchDesignerConnectionStatus = TouchDesignerConnectionStatus.Disconnected,
    val message: String = "未接続",
)

data class TouchDesignerMotionFrame(
    val attitude: AttitudeEstimate,
    val speed: Float,
    val color: Int,
)

/** Newline-delimited UTF-8 JSON contract consumed by the TouchDesigner TCP server. */
internal object TouchDesignerProtocol {
    fun connectionTest(timestampMs: Long): String {
        return "{\"cmd\":\"connection_test\",\"timestamp\":$timestampMs}\n"
    }

    fun imu(timestampMs: Long, frame: TouchDesignerMotionFrame): String? {
        val attitude = frame.attitude
        if (
            !attitude.rollDeg.isFinite() ||
            !attitude.pitchDeg.isFinite() ||
            !attitude.yawDeg.isFinite() ||
            !frame.speed.isFinite()
        ) {
            return null
        }
        val hex = String.format(
            Locale.US,
            "#%02X%02X%02X",
            frame.color shr 16 and 0xFF,
            frame.color shr 8 and 0xFF,
            frame.color and 0xFF,
        )
        val values = String.format(
            Locale.US,
            "%.6f,%.6f,%.6f,%.6f,%s",
            attitude.rollDeg,
            attitude.pitchDeg,
            attitude.yawDeg,
            frame.speed.coerceAtLeast(0f),
            hex,
        )
        return "{\"cmd\":\"send_imu_values\",\"timestamp\":$timestampMs," +
            "\"data\":{\"imu_values\":\"$values\"}}\n"
    }
}

internal fun isValidTouchDesignerHost(host: String): Boolean {
    val value = host.trim()
    val ipv4 = value.split('.')
    if (ipv4.size == 4) {
        return ipv4.all { part ->
            part.isNotEmpty() && part.all(Char::isDigit) && part.toIntOrNull() in 0..255
        }
    }
    if (!value.contains(':')) return false
    return runCatching { InetAddress.getByName(value) is Inet6Address }.getOrDefault(false)
}

/**
 * Non-blocking producer / single-writer TCP client.
 *
 * IMU and BLE callbacks only replace atomic snapshots. A dedicated network
 * executor sends the newest attitude at no more than 60 Hz and the current
 * color in the same message, so a slow or absent TouchDesigner server cannot
 * stall either sensor processing or BLE writes.
 */
class TouchDesignerTcpClient(
    private val onStateChanged: (TouchDesignerConnectionState) -> Unit,
) : AutoCloseable {
    private data class SequencedMotionFrame(val sequence: Long, val value: TouchDesignerMotionFrame)

    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "spright-touchdesigner").apply { priority = Thread.NORM_PRIORITY }
    }
    private val motionSequence = AtomicLong(0L)
    private val latestMotionFrame = AtomicReference<SequencedMotionFrame?>(null)
    private val latestColor = AtomicReference(0xFFFFFFFF.toInt())

    @Volatile
    private var closed = false
    private var socket: Socket? = null
    private var writer: BufferedWriter? = null
    private var lastSentMotionSequence = -1L

    init {
        executor.scheduleWithFixedDelay(
            ::sendLatestMotionFrame,
            0L,
            TOUCHDESIGNER_IMU_INTERVAL_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    fun connect(host: String, port: Int) {
        val normalizedHost = host.trim()
        val validationError = when {
            !isValidTouchDesignerHost(normalizedHost) -> "IPアドレスが正しくありません"
            port !in 1..65535 -> "Portは1〜65535で入力してください"
            else -> null
        }
        if (validationError != null) {
            publish(TouchDesignerConnectionStatus.Error, validationError)
            return
        }
        if (closed) return
        publish(TouchDesignerConnectionStatus.Connecting, "$normalizedHost:$port 接続テスト中")
        executor.execute {
            closeSocket()
            try {
                val newSocket = Socket().apply {
                    tcpNoDelay = true
                    keepAlive = true
                    connect(InetSocketAddress(normalizedHost, port), CONNECT_TIMEOUT_MS)
                }
                socket = newSocket
                writer = BufferedWriter(OutputStreamWriter(newSocket.getOutputStream(), StandardCharsets.UTF_8))
                lastSentMotionSequence = -1L
                if (!write(TouchDesignerProtocol.connectionTest(System.currentTimeMillis()))) {
                    return@execute
                }
                publish(TouchDesignerConnectionStatus.Connected, "接続")
            } catch (error: Throwable) {
                Log.w(TAG, "Connection to TouchDesigner failed", error)
                closeSocket()
                publish(TouchDesignerConnectionStatus.Error, "TouchDesignerへ接続できません")
            }
        }
    }

    fun disconnect() {
        if (closed) return
        executor.execute {
            closeSocket()
            publish(TouchDesignerConnectionStatus.Disconnected, "未接続")
        }
    }

    fun updateMotion(frame: TouchDesignerMotionFrame) {
        val attitude = frame.attitude
        if (
            closed ||
            !attitude.rollDeg.isFinite() ||
            !attitude.pitchDeg.isFinite() ||
            !attitude.yawDeg.isFinite() ||
            !frame.speed.isFinite()
        ) {
            return
        }
        latestColor.set(frame.color)
        latestMotionFrame.set(SequencedMotionFrame(motionSequence.incrementAndGet(), frame))
    }

    fun updateColor(color: Int) {
        if (!closed) latestColor.set(color)
    }

    private fun sendLatestMotionFrame() {
        val snapshot = latestMotionFrame.get() ?: return
        if (snapshot.sequence == lastSentMotionSequence) return
        val message = TouchDesignerProtocol.imu(System.currentTimeMillis(), snapshot.value) ?: return
        if (write(message)) {
            lastSentMotionSequence = snapshot.sequence
        }
    }

    private fun write(message: String): Boolean {
        val activeSocket = socket
        val activeWriter = writer
        if (
            closed || activeSocket == null || activeWriter == null ||
            !activeSocket.isConnected || activeSocket.isClosed
        ) {
            return false
        }
        return try {
            activeWriter.write(message)
            activeWriter.flush()
            true
        } catch (error: Throwable) {
            Log.w(TAG, "TouchDesigner TCP write failed", error)
            closeSocket()
            publish(TouchDesignerConnectionStatus.Error, "TouchDesigner送信が切断されました")
            false
        }
    }

    private fun closeSocket() {
        runCatching { writer?.close() }
        runCatching { socket?.close() }
        writer = null
        socket = null
    }

    private fun publish(status: TouchDesignerConnectionStatus, message: String) {
        if (!closed) onStateChanged(TouchDesignerConnectionState(status, message))
    }

    override fun close() {
        if (closed) return
        closed = true
        executor.execute(::closeSocket)
        executor.shutdown()
        runCatching { executor.awaitTermination(1L, TimeUnit.SECONDS) }
        if (!executor.isTerminated) executor.shutdownNow()
    }

    private companion object {
        const val TAG = "SprightTouchDesigner"
        const val CONNECT_TIMEOUT_MS = 3_000
    }
}
