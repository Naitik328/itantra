package com.itantra.relay.protocol

/**
 * iTantra wire frame — identical over Bluetooth, Wi-Fi and LoRa.
 *
 * This is the team contract. Freeze the byte layout in Week 1: once fixed, the AI
 * team, this app, and Jai's LoRa firmware can all build against it independently.
 *
 * Byte layout (big-endian, header is 6 bytes + payload + 2-byte CRC):
 *
 *   [0] ver (high nibble, 4b) | type (low nibble, 4b)
 *   [1] src   (0..255)
 *   [2] dst   (0..255, 0xFF = broadcast)
 *   [3] lang  (language id, 0..255)
 *   [4] seq   (rolling sequence number, 0..255)
 *   [5] len   (payload length in bytes, 0..255)
 *   [6 .. 6+len-1] payload  (UTF-8 text, ~one sentence)
 *   [.. +2] crc16           (CRC-16/CCITT-FALSE over every byte before the CRC)
 */
enum class FrameType(val code: Int) {
    NORMAL(0),
    ALERT(1),
    ACK(2);

    companion object {
        fun from(code: Int): FrameType = entries.firstOrNull { it.code == code } ?: NORMAL
    }
}

data class WireFrame(
    val version: Int = PROTOCOL_VERSION,
    val type: FrameType = FrameType.NORMAL,
    val src: Int,
    val dst: Int,
    val lang: Int,
    val seq: Int,
    val payload: ByteArray,
) {
    /** Payload decoded as UTF-8 text. */
    val text: String get() = String(payload, Charsets.UTF_8)

    companion object {
        const val PROTOCOL_VERSION = 1
        const val BROADCAST = 0xFF
        const val HEADER_SIZE = 6
        const val CRC_SIZE = 2
        const val MAX_PAYLOAD = 255

        /** Build a NORMAL/ALERT text frame, checking the payload fits in one frame. */
        fun ofText(
            text: String,
            src: Int,
            dst: Int,
            lang: Int,
            seq: Int,
            type: FrameType = FrameType.NORMAL,
        ): WireFrame {
            val bytes = text.toByteArray(Charsets.UTF_8)
            require(bytes.size <= MAX_PAYLOAD) {
                "payload too long: ${bytes.size} > $MAX_PAYLOAD bytes"
            }
            return WireFrame(PROTOCOL_VERSION, type, src, dst, lang, seq and 0xFF, bytes)
        }
    }

    // ByteArray needs structural equality spelled out.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WireFrame) return false
        return version == other.version && type == other.type && src == other.src &&
            dst == other.dst && lang == other.lang && seq == other.seq &&
            payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = version
        result = 31 * result + type.hashCode()
        result = 31 * result + src
        result = 31 * result + dst
        result = 31 * result + lang
        result = 31 * result + seq
        result = 31 * result + payload.contentHashCode()
        return result
    }
}
