package com.itantra.relay.transport

import android.content.Context
import com.itantra.relay.protocol.FrameType
import com.itantra.relay.protocol.WireCodec
import com.itantra.relay.protocol.WireFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicInteger

/**
 * Process-wide holder for the single [WifiDirectTransport] so the foreground
 * [RelayService] and the UI share one instance and one long-lived scope. The
 * service keeps the process alive, so the transport survives backgrounding.
 */
object RelayHub {

    /** The preset SOS broadcast (must fit in one 255-byte frame). */
    const val SOS_TEXT = "⚠️ SOS — Emergency! Need help now."

    /** An alert we received, surfaced to the UI for the full-screen overlay. */
    data class ReceivedAlert(val text: String, val fromSrc: Int, val at: Long)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val seq = AtomicInteger(0)

    @Volatile private var _transport: WifiDirectTransport? = null
    @Volatile private var registered = false

    val transport: WifiDirectTransport? get() = _transport

    private val _latestAlert = MutableStateFlow<ReceivedAlert?>(null)
    val latestAlert = _latestAlert.asStateFlow()

    /** Get (creating on first call) the shared transport bound to the process scope. */
    fun getOrCreate(context: Context): WifiDirectTransport =
        _transport ?: WifiDirectTransport(context.applicationContext, scope).also { _transport = it }

    /** Start advertising/discovery exactly once for the process. */
    fun ensureRegistered(context: Context, userName: String) {
        val t = getOrCreate(context)
        if (!registered) {
            registered = true
            t.register(userName)
        }
    }

    fun setAlert(a: ReceivedAlert?) { _latestAlert.value = a }

    private fun srcFor(name: String) = name.hashCode() and 0xFF
    private fun nextSeq() = seq.getAndIncrement() and 0xFF

    /** Encoded ALERT frame carrying the preset SOS text. */
    fun sosBytes(userName: String): ByteArray =
        WireCodec.encode(
            WireFrame.ofText(SOS_TEXT, srcFor(userName), WireFrame.BROADCAST, 0, nextSeq(), FrameType.ALERT),
        )

    /** Encoded ACK frame a receiver sends back after showing an alert. */
    fun ackBytes(userName: String): ByteArray =
        WireCodec.encode(
            WireFrame.ofText("ack", srcFor(userName), WireFrame.BROADCAST, 0, nextSeq(), FrameType.ACK),
        )
}
