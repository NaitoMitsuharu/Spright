package com.example.ble1507

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TouchDesignerTcpInstrumentedTest {
    @Test
    fun sendsNewlineDelimitedImuAndColorJsonOverTcp() {
        ServerSocket(0).use { server ->
            val lines = LinkedBlockingQueue<String>()
            val serverThread = Thread {
                server.accept().use { socket ->
                    val reader = BufferedReader(
                        InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8),
                    )
                    repeat(8) {
                        reader.readLine()?.let(lines::offer) ?: return@Thread
                    }
                }
            }.apply { start() }
            val connected = CountDownLatch(1)
            val client = TouchDesignerTcpClient { state ->
                if (state.status == TouchDesignerConnectionStatus.Connected) connected.countDown()
            }
            try {
                client.updateColor(0xFF8A2BE2.toInt())
                client.connect("127.0.0.1", server.localPort)
                assertTrue(connected.await(2L, TimeUnit.SECONDS))
                client.updateAttitude(AttitudeEstimate(10f, -20f, 30f))

                val received = mutableListOf<String>()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2L)
                while (
                    System.nanoTime() < deadline &&
                    (received.none { it.contains("send_imu_values") } ||
                        received.none { it.contains("send_color_value") })
                ) {
                    lines.poll(100L, TimeUnit.MILLISECONDS)?.let(received::add)
                }
                assertTrue(received.any { it.contains("\"imu_values\":\"10.0,-20.0,30.0\"") })
                assertTrue(received.any { it.contains("\"color\":\"#8A2BE2\"") })
            } finally {
                client.close()
                server.close()
                serverThread.interrupt()
                serverThread.join(1_000L)
            }
        }
    }
}
