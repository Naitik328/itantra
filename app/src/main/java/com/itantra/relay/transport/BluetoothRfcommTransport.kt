package com.itantra.relay.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import com.itantra.relay.protocol.WireFrame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlin.coroutines.coroutineContext

/**
 * Backend A — phone ↔ phone over Bluetooth Classic (SPP / RFCOMM), insecure so no
 * pairing dialog is needed.
 *
 * By default it **hosts**: a loop that listens and re-accepts connections forever,
 * so reconnecting always works. [join] switches to client for one connection and
 * then falls back to hosting when it ends.
 */
class BluetoothRfcommTransport(
    private val adapter: BluetoothAdapter,
    private val scope: CoroutineScope,
) : Transport {

    enum class Status { IDLE, LISTENING, CONNECTING, CONNECTED, ERROR }

    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    override val incoming = _incoming.asSharedFlow()

    private val _status = MutableStateFlow(Status.IDLE)
    val status = _status.asStateFlow()

    private val _peer = MutableStateFlow<String?>(null)
    val peer = _peer.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    private var job: Job? = null
    private var serverSocket: BluetoothServerSocket? = null
    private var socket: BluetoothSocket? = null
    private var output: OutputStream? = null
    private val writeMutex = Mutex()

    init {
        host() // always host from creation
    }

    /** Host: listen and re-accept connections in a loop. */
    fun host() {
        stop()
        _error.value = null
        job = scope.launch(Dispatchers.IO) { hostLoop() }
    }

    /** Connect out to a discovered [device]; resumes hosting when it ends. */
    fun join(device: BluetoothDevice) {
        stop()
        _error.value = null
        job = scope.launch(Dispatchers.IO) {
            _status.value = Status.CONNECTING
            runCatching { adapter.cancelDiscovery() }
            val s = connect(device)
            if (s == null) {
                fail("Couldn't connect. Make sure the other phone has iTantra open.")
                delay(1200)
                if (coroutineContext.isActive) resumeHost()
                return@launch
            }
            serve(s)
            if (coroutineContext.isActive) resumeHost()
        }
    }

    override fun start() {}

    override fun stop() {
        job?.cancel()
        job = null
        runCatching { socket?.close() }
        runCatching { serverSocket?.close() }
        socket = null
        serverSocket = null
        output = null
        if (_status.value != Status.ERROR) _status.value = Status.IDLE
    }

    override suspend fun send(frame: ByteArray) {
        val out = output ?: return
        writeMutex.withLock {
            withContext(Dispatchers.IO) {
                out.write(frame)
                out.flush()
            }
        }
    }

    private fun resumeHost() {
        job = scope.launch(Dispatchers.IO) { hostLoop() }
    }

    @SuppressLint("MissingPermission")
    private suspend fun hostLoop() {
        var server: BluetoothServerSocket? = null
        try {
            server = adapter.listenUsingInsecureRfcommWithServiceRecord(SERVICE_NAME, SPP_UUID)
            serverSocket = server
            while (coroutineContext.isActive) {
                _status.value = Status.LISTENING
                val accepted = server.accept() // blocks; throws when the socket is closed by stop()
                serve(accepted)                // CONNECTED until disconnect, then loop and accept again
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (coroutineContext.isActive) _status.value = Status.IDLE
        } finally {
            runCatching { server?.close() }
            if (serverSocket === server) serverSocket = null
        }
    }

    /** Insecure connect by UUID, with the reflection channel-1 fallback for flaky SDP. */
    @SuppressLint("MissingPermission")
    private fun connect(device: BluetoothDevice): BluetoothSocket? {
        try {
            val s = device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
            s.connect()
            return s
        } catch (_: Exception) {
            // fall through to the reflection fallback
        }
        return try {
            val m = device.javaClass.getMethod("createInsecureRfcommSocket", Int::class.javaPrimitiveType)
            val s = m.invoke(device, 1) as BluetoothSocket
            s.connect()
            s
        } catch (_: Exception) {
            null
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun serve(s: BluetoothSocket) {
        socket = s
        output = s.outputStream
        _peer.value = runCatching { s.remoteDevice?.name ?: s.remoteDevice?.address }.getOrNull()
        _error.value = null
        _status.value = Status.CONNECTED
        readLoop(s.inputStream)
        output = null
        _peer.value = null
        runCatching { s.close() }
        if (socket === s) socket = null
    }

    /** Reassemble length-delimited wire frames from the byte stream. */
    private suspend fun readLoop(input: InputStream) {
        val data = DataInputStream(input)
        val header = ByteArray(WireFrame.HEADER_SIZE)
        try {
            while (true) {
                data.readFully(header)
                val len = header[5].toInt() and 0xFF
                val frame = ByteArray(WireFrame.HEADER_SIZE + len + WireFrame.CRC_SIZE)
                System.arraycopy(header, 0, frame, 0, WireFrame.HEADER_SIZE)
                data.readFully(frame, WireFrame.HEADER_SIZE, len + WireFrame.CRC_SIZE)
                _incoming.emit(frame)
            }
        } catch (_: IOException) {
            // peer closed / link lost
        }
    }

    private fun fail(message: String) {
        _error.value = message
        _status.value = Status.ERROR
    }

    companion object {
        /** Standard Serial Port Profile UUID — both phones (and the scan filter) use it. */
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        const val SERVICE_NAME = "iTantraRelay"
    }
}
