package com.itantra.relay.protocol

/**
 * CRC-16/CCITT-FALSE — polynomial 0x1021, init 0xFFFF, no reflection, xor-out 0x0000.
 *
 * Small, well-known, and cheap enough to run on the ESP32 side too, so Jai's
 * firmware can compute the identical checksum. Keep this algorithm in lock-step
 * across the phone app and the LoRa board.
 */
object Crc16 {
    fun compute(data: ByteArray, length: Int = data.size): Int {
        var crc = 0xFFFF
        for (i in 0 until length) {
            crc = crc xor ((data[i].toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1
                crc = crc and 0xFFFF
            }
        }
        return crc and 0xFFFF
    }
}
