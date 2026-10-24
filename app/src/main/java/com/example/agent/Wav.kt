package com.example.agent

import java.nio.ByteBuffer
import java.nio.ByteOrder

object Wav {
    /** Оборачивает сырой PCM в WAV-файл (по умолчанию 16 кГц, моно, 16 бит). */
    fun wrap(pcm: ByteArray, rate: Int = 16000, channels: Int = 1, bits: Int = 16): ByteArray {
        val byteRate = rate * channels * bits / 8
        val b = ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()); b.putInt(36 + pcm.size); b.put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()); b.putInt(16); b.putShort(1); b.putShort(channels.toShort())
        b.putInt(rate); b.putInt(byteRate)
        b.putShort((channels * bits / 8).toShort()); b.putShort(bits.toShort())
        b.put("data".toByteArray()); b.putInt(pcm.size); b.put(pcm)
        return b.array()
    }
}
