package com.sih.itantra.wifidirect

/**
 * Binary wire format for text messages sent over the Wi-Fi Direct link.
 *
 * Layout (all multi-byte fields big-endian):
 * ```
 * byte 0   : version (hi 4 bits) | type (lo 4 bits)
 *            type 0 = NORMAL, 1 = ALERT, 2 = ACK, 3-15 = ignore
 * byte 1   : src   (node id)
 * byte 2   : dst   (node id, 0xFF = broadcast)
 * byte 3   : lang  (0-9 used; field allows 0-255)
 * byte 4   : seq   (per-sender sequence, wraps at 256)
 * byte 5   : len   (payload length in bytes, 0-247)
 * byte 6.. : payload (UTF-8 text, `len` bytes)
 * last 2   : crc16 CCITT-FALSE, big-endian, over bytes [0 .. 5+len]
 * ```
 *
 * The `len` field makes each frame self-describing, so a reader on a TCP stream can frame
 * packets without any extra length prefix: read the 6-byte header, then `len + 2` more bytes.
 */
object Packet {

    const val VERSION = 1

    const val TYPE_NORMAL = 0
    const val TYPE_ALERT = 1
    const val TYPE_ACK = 2

    const val BROADCAST_DST = 0xFF

    /** Header is fixed 6 bytes; trailer is a 2-byte CRC. */
    const val HEADER_LEN = 6
    const val CRC_LEN = 2

    /** Max UTF-8 payload bytes that fit in the single-byte `len` field's usable range. */
    const val MAX_PAYLOAD = 247

    /** A decoded, CRC-verified frame. */
    data class Frame(
        val version: Int,
        val type: Int,
        val src: Int,
        val dst: Int,
        val lang: Int,
        val seq: Int,
        val payload: ByteArray,
    ) {
        val text: String get() = String(payload, Charsets.UTF_8)
    }

    /**
     * Encode a message into its wire bytes.
     * @throws IllegalArgumentException if the UTF-8 payload exceeds [MAX_PAYLOAD].
     */
    fun encode(
        type: Int,
        src: Int,
        dst: Int,
        lang: Int,
        seq: Int,
        text: String,
    ): ByteArray {
        val payload = text.toByteArray(Charsets.UTF_8)
        require(payload.size <= MAX_PAYLOAD) {
            "payload ${payload.size} B exceeds max $MAX_PAYLOAD B"
        }
        val len = payload.size
        val frame = ByteArray(HEADER_LEN + len + CRC_LEN)
        frame[0] = (((VERSION and 0x0F) shl 4) or (type and 0x0F)).toByte()
        frame[1] = src.toByte()
        frame[2] = dst.toByte()
        frame[3] = lang.toByte()
        frame[4] = seq.toByte()
        frame[5] = len.toByte()
        System.arraycopy(payload, 0, frame, HEADER_LEN, len)
        // CRC over bytes [0 .. 5+len] inclusive == the first HEADER_LEN + len bytes.
        val crc = crc16(frame, 0, HEADER_LEN + len)
        frame[HEADER_LEN + len] = ((crc ushr 8) and 0xFF).toByte()
        frame[HEADER_LEN + len + 1] = (crc and 0xFF).toByte()
        return frame
    }

    /**
     * Decode a complete frame. Returns null if the buffer is malformed or the CRC doesn't match,
     * so a corrupt packet is dropped rather than shown as garbage.
     */
    fun decode(frame: ByteArray): Frame? {
        if (frame.size < HEADER_LEN + CRC_LEN) return null
        val len = frame[5].toInt() and 0xFF
        if (frame.size != HEADER_LEN + len + CRC_LEN) return null

        val expected = crc16(frame, 0, HEADER_LEN + len)
        val actual = ((frame[HEADER_LEN + len].toInt() and 0xFF) shl 8) or
            (frame[HEADER_LEN + len + 1].toInt() and 0xFF)
        if (expected != actual) return null

        return Frame(
            version = (frame[0].toInt() and 0xF0) ushr 4,
            type = frame[0].toInt() and 0x0F,
            src = frame[1].toInt() and 0xFF,
            dst = frame[2].toInt() and 0xFF,
            lang = frame[3].toInt() and 0xFF,
            seq = frame[4].toInt() and 0xFF,
            payload = frame.copyOfRange(HEADER_LEN, HEADER_LEN + len),
        )
    }

    /**
     * Split [text] into pieces whose UTF-8 encodings each fit within [maxBytes], so a message too
     * long for one frame goes out as several. Breaks at whitespace where it can, so the receiver's
     * TTS speaks whole words and natural fragments rather than syllables cut in half.
     *
     * This matters most for scripts like Devanagari, where every character is ~3 UTF-8 bytes and a
     * single sentence can blow past the 247-byte [MAX_PAYLOAD] the single-byte `len` field allows.
     * A lone "word" longer than [maxBytes] (a long URL, or a script without spaces) is hard-split
     * on codepoint boundaries so a frame never carries half a character.
     */
    fun splitUtf8(text: String, maxBytes: Int = MAX_PAYLOAD): List<String> {
        require(maxBytes > 0) { "maxBytes must be positive" }
        if (text.toByteArray(Charsets.UTF_8).size <= maxBytes) {
            return if (text.isEmpty()) emptyList() else listOf(text)
        }

        val chunks = ArrayList<String>()
        val current = StringBuilder()
        var currentBytes = 0

        fun flush() {
            val piece = current.toString().trim()
            if (piece.isNotEmpty()) chunks.add(piece)
            current.setLength(0)
            currentBytes = 0
        }

        // Each token is a run of non-space plus the whitespace trailing it, so spacing survives.
        for (token in Regex("\\S+\\s*").findAll(text).map { it.value }) {
            val tokenBytes = token.toByteArray(Charsets.UTF_8).size
            if (tokenBytes > maxBytes) {
                flush()
                chunks += hardSplit(token, maxBytes)
                continue
            }
            if (currentBytes + tokenBytes > maxBytes) flush()
            current.append(token)
            currentBytes += tokenBytes
        }
        flush()
        return chunks
    }

    /** Split on codepoint boundaries so no frame ever carries a partial UTF-8 character. */
    private fun hardSplit(text: String, maxBytes: Int): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var bytes = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val ch = String(Character.toChars(cp))
            val chBytes = ch.toByteArray(Charsets.UTF_8).size
            if (bytes + chBytes > maxBytes && sb.isNotEmpty()) {
                out.add(sb.toString())
                sb.setLength(0)
                bytes = 0
            }
            sb.append(ch)
            bytes += chBytes
            i += Character.charCount(cp)
        }
        if (sb.isNotEmpty()) out.add(sb.toString().trim().ifEmpty { sb.toString() })
        return out
    }

    /**
     * CRC-16/CCITT-FALSE: polynomial 0x1021, init 0xFFFF, no input/output reflection, no final
     * XOR. Computed over [length] bytes of [data] starting at [offset].
     */
    fun crc16(data: ByteArray, offset: Int, length: Int): Int {
        var crc = 0xFFFF
        for (i in offset until offset + length) {
            crc = crc xor ((data[i].toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1
                crc = crc and 0xFFFF
            }
        }
        return crc and 0xFFFF
    }
}
