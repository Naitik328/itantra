package com.itantra.relay.protocol

/**
 * Serialises a [WireFrame] to bytes and back, with CRC checking on decode.
 * The bytes produced here go, unchanged, over Bluetooth, Wi-Fi or LoRa.
 */
object WireCodec {

    fun encode(f: WireFrame): ByteArray {
        require(f.payload.size <= WireFrame.MAX_PAYLOAD) { "payload too long" }
        val len = f.payload.size
        val out = ByteArray(WireFrame.HEADER_SIZE + len + WireFrame.CRC_SIZE)

        out[0] = (((f.version and 0x0F) shl 4) or (f.type.code and 0x0F)).toByte()
        out[1] = f.src.toByte()
        out[2] = f.dst.toByte()
        out[3] = f.lang.toByte()
        out[4] = f.seq.toByte()
        out[5] = len.toByte()
        System.arraycopy(f.payload, 0, out, WireFrame.HEADER_SIZE, len)

        val crc = Crc16.compute(out, WireFrame.HEADER_SIZE + len)
        out[WireFrame.HEADER_SIZE + len] = ((crc ushr 8) and 0xFF).toByte()
        out[WireFrame.HEADER_SIZE + len + 1] = (crc and 0xFF).toByte()
        return out
    }

    sealed interface DecodeResult {
        data class Ok(val frame: WireFrame) : DecodeResult
        data class Error(val reason: String) : DecodeResult
    }

    fun decode(bytes: ByteArray): DecodeResult {
        if (bytes.size < WireFrame.HEADER_SIZE + WireFrame.CRC_SIZE) {
            return DecodeResult.Error("too short: ${bytes.size} bytes")
        }
        val len = bytes[5].toInt() and 0xFF
        val needed = WireFrame.HEADER_SIZE + len + WireFrame.CRC_SIZE
        if (bytes.size < needed) {
            return DecodeResult.Error("truncated: need $needed, have ${bytes.size}")
        }

        val crcPos = WireFrame.HEADER_SIZE + len
        val got = ((bytes[crcPos].toInt() and 0xFF) shl 8) or (bytes[crcPos + 1].toInt() and 0xFF)
        val calc = Crc16.compute(bytes, crcPos)
        if (got != calc) {
            return DecodeResult.Error("CRC mismatch: got %04X, want %04X".format(got, calc))
        }

        val b0 = bytes[0].toInt() and 0xFF
        val payload = bytes.copyOfRange(WireFrame.HEADER_SIZE, WireFrame.HEADER_SIZE + len)
        val frame = WireFrame(
            version = (b0 ushr 4) and 0x0F,
            type = FrameType.from(b0 and 0x0F),
            src = bytes[1].toInt() and 0xFF,
            dst = bytes[2].toInt() and 0xFF,
            lang = bytes[3].toInt() and 0xFF,
            seq = bytes[4].toInt() and 0xFF,
            payload = payload,
        )
        return DecodeResult.Ok(frame)
    }

    /** Human-readable hex dump, e.g. "10 01 FF 00 05 0B 48 65 …". */
    fun toHex(bytes: ByteArray): String =
        bytes.joinToString(" ") { "%02X".format(it) }
}
