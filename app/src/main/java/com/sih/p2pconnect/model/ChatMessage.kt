package com.sih.p2pconnect.model

/**
 * One message in the connected-session chat, in a UI-friendly shape.
 *
 * Timing is the point of the feature, so each message carries how long the binary work took:
 * [codecMicros] is the local encode time (outgoing) or decode time (incoming), and
 * [roundTripMillis] is the end-to-end time from send until the peer's ACK came back (outgoing
 * only, filled in when the ACK arrives).
 */
data class ChatMessage(
    val id: Long,
    val text: String,
    val outgoing: Boolean,
    /** Packet type: 0 = NORMAL, 1 = ALERT. */
    val type: Int,
    val seq: Int,
    /** Total encoded frame size in bytes (header + payload + CRC). */
    val frameBytes: Int,
    /** Encode (outgoing) or decode (incoming) time in microseconds. */
    val codecMicros: Long,
    /** Send → ACK round-trip in ms; null until the ACK arrives (outgoing only). */
    val roundTripMillis: Long? = null,
    val timestamp: Long = System.currentTimeMillis(),
)
