package com.itantra.relay.transport

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Test backend: echoes every sent frame straight back to [incoming].
 *
 * This lets you exercise the whole encode → send → receive → decode path (and the
 * UI) before any real radio or a second phone exists. Replace with
 * BluetoothRfcommTransport for Backend A.
 */
class LoopbackTransport : Transport {

    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 32)
    override val incoming = _incoming.asSharedFlow()

    override suspend fun send(frame: ByteArray) {
        _incoming.emit(frame)
    }

    override fun start() {}

    override fun stop() {}
}
