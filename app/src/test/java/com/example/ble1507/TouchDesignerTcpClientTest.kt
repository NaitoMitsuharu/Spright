package com.example.ble1507

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchDesignerTcpClientTest {
    @Test
    fun streamCadencesStayWithinTheRequestedLimits() {
        assertTrue(TOUCHDESIGNER_IMU_INTERVAL_MS >= 17L)
    }

    @Test
    fun imuProtocolMatchesSpresenseDroidPracticeContract() {
        val message = TouchDesignerProtocol.imu(
            timestampMs = 1_725_000_123_456L,
            frame = TouchDesignerMotionFrame(
                attitude = AttitudeEstimate(12.5f, -3.25f, 179.0f),
                speed = 1.75f,
                color = 0xFF03A7EF.toInt(),
            ),
        )

        assertEquals(
            "{\"cmd\":\"send_imu_values\",\"timestamp\":1725000123456," +
                "\"data\":{\"imu_values\":\"12.500000,-3.250000,179.000000,1.750000,#03A7EF\"}}\n",
            message,
        )
        assertEquals(1, message?.count { it == '\n' })
    }

    @Test
    fun imuProtocolRejectsNonFiniteAngles() {
        assertNull(
            TouchDesignerProtocol.imu(
                1L,
                TouchDesignerMotionFrame(AttitudeEstimate(Float.NaN, 0f, 0f), 0f, 0xFFFFFFFF.toInt()),
            ),
        )
        assertNull(
            TouchDesignerProtocol.imu(
                1L,
                TouchDesignerMotionFrame(
                    AttitudeEstimate(0f, Float.POSITIVE_INFINITY, 0f),
                    0f,
                    0xFFFFFFFF.toInt(),
                ),
            ),
        )
    }

    @Test
    fun validatesNumericIpAddressesAndRejectsHostnames() {
        assertTrue(isValidTouchDesignerHost("192.168.1.100"))
        assertTrue(isValidTouchDesignerHost("::1"))
        assertFalse(isValidTouchDesignerHost("192.168.1.999"))
        assertFalse(isValidTouchDesignerHost("touchdesigner.local"))
        assertFalse(isValidTouchDesignerHost(""))
    }

    @Test
    fun tcpClientWritesUnifiedMotionStreamWithoutRepeatingAnUnchangedFrame() {
        ServerSocket(0).use { server ->
            val lines = LinkedBlockingQueue<String>()
            val serverFinished = CountDownLatch(1)
            val serverThread = Thread {
                runCatching {
                    server.accept().use { socket ->
                        val reader = BufferedReader(
                            InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8),
                        )
                        while (!Thread.currentThread().isInterrupted) {
                            val line = reader.readLine() ?: break
                            lines.offer(line)
                        }
                    }
                }
                serverFinished.countDown()
            }.apply {
                name = "touchdesigner-test-server"
                start()
            }
            val connected = CountDownLatch(1)
            val client = TouchDesignerTcpClient { state ->
                if (state.status == TouchDesignerConnectionStatus.Connected) connected.countDown()
            }
            try {
                client.updateColor(0xFF1234AB.toInt())
                client.connect("127.0.0.1", server.localPort)
                assertTrue("TCP client did not connect", connected.await(2L, TimeUnit.SECONDS))
                client.updateMotion(
                    TouchDesignerMotionFrame(
                        attitude = AttitudeEstimate(1.5f, -2.5f, 3.5f),
                        speed = 0.42f,
                        color = 0xFF1234AB.toInt(),
                    ),
                )

                val received = mutableListOf<String>()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2L)
                while (System.nanoTime() < deadline && received.none { it.contains("send_imu_values") }) {
                    lines.poll(100L, TimeUnit.MILLISECONDS)?.let(received::add)
                }

                assertTrue(received.any {
                    it.startsWith("{\"cmd\":\"send_imu_values\",\"timestamp\":") &&
                        it.endsWith("\"data\":{\"imu_values\":\"1.500000,-2.500000,3.500000,0.420000,#1234AB\"}}")
                })

                // With no new sample, the coalescing IMU stream must not repeat it.
                Thread.sleep(120L)
                while (true) lines.poll()?.let(received::add) ?: break
                assertEquals(1, received.count { it.contains("send_imu_values") })
            } finally {
                client.close()
                serverThread.interrupt()
                serverFinished.await(1L, TimeUnit.SECONDS)
            }
        }
    }
}
