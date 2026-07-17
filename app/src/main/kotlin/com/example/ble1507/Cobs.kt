package com.example.ble1507

import java.io.ByteArrayOutputStream

object Cobs {
    fun encodeFrame(input: ByteArray): ByteArray {
        val output = ByteArrayOutputStream(input.size + 2)
        var codeIndex = 0
        var code = 1
        output.write(0)

        for (value in input) {
            if (value.toInt() == 0) {
                rewriteCode(output, codeIndex, code)
                codeIndex = output.size()
                output.write(0)
                code = 1
            } else {
                output.write(value.toInt())
                code++
                if (code == 0xFF) {
                    rewriteCode(output, codeIndex, code)
                    codeIndex = output.size()
                    output.write(0)
                    code = 1
                }
            }
        }

        rewriteCode(output, codeIndex, code)
        return output.toByteArray() + byteArrayOf(0)
    }

    fun decodeFrame(frame: ByteArray): ByteArray {
        var end = frame.size
        if (end > 0 && frame[end - 1].toInt() == 0) {
            end--
        }

        val output = ByteArrayOutputStream(end)
        var index = 0
        while (index < end) {
            val code = frame[index].toInt() and 0xFF
            if (code == 0 || index + code > end + 1) {
                throw IllegalArgumentException("Invalid COBS frame")
            }
            index++
            for (i in 1 until code) {
                if (index >= end) {
                    throw IllegalArgumentException("Truncated COBS frame")
                }
                output.write(frame[index].toInt())
                index++
            }
            if (code < 0xFF && index < end) {
                output.write(0)
            }
        }
        return output.toByteArray()
    }

    private fun rewriteCode(output: ByteArrayOutputStream, codeIndex: Int, code: Int) {
        val buffer = output.toByteArray()
        buffer[codeIndex] = code.toByte()
        output.reset()
        output.write(buffer, 0, buffer.size)
    }
}