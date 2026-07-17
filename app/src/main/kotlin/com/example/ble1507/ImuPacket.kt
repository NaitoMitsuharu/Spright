package com.example.ble1507

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

data class ImuPacket(
    val timestamp: Long,
    val temp: Float,
    val gx: Float,
    val gy: Float,
    val gz: Float,
    val ax: Float,
    val ay: Float,
    val az: Float,
) {
    fun toDisplayString(): String = String.format(
        Locale.US,
        "timestamp: %d\n" +
            "temp: %.2f C\n" +
            "gyro:  x %.6f   y %.6f   z %.6f\n" +
            "accel: x %.6f   y %.6f   z %.6f",
        timestamp,
        temp,
        gx,
        gy,
        gz,
        ax,
        ay,
        az,
    )

    companion object {
        const val TYPE_IMU_SAMPLE = 0x01
        private const val RAW_SIZE = 33

        fun parse(raw: ByteArray): ImuPacket {
            if (raw.size < RAW_SIZE || (raw[0].toInt() and 0xFF) != TYPE_IMU_SAMPLE) {
                throw IllegalArgumentException("Not an IMU sample packet")
            }

            val buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
            buffer.get()
            return ImuPacket(
                timestamp = buffer.int.toLong() and 0xFFFFFFFFL,
                temp = buffer.float,
                gx = buffer.float,
                gy = buffer.float,
                gz = buffer.float,
                ax = buffer.float,
                ay = buffer.float,
                az = buffer.float,
            )
        }
    }
}