package com.sih.p2pconnect.wifidirect

import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * Tiny TCP transport for the connected Wi-Fi Direct session. The group owner listens; the client
 * dials the group owner's fixed address (192.168.49.1). Once the socket is up, frames are read
 * back using the [Packet] header's `len` field for framing — no extra length prefix needed.
 *
 * All three callbacks fire on background threads; the caller is responsible for hopping to the
 * main thread before touching UI state.
 */
class MessageTransport(
    private val onLinkReady: () -> Unit,
    private val onFrame: (ByteArray) -> Unit,
    private val onClosed: (reason: String?) -> Unit,
) {

    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val writeLock = Any()

    @Volatile private var running = false
    @Volatile private var out: OutputStream? = null

    private var serverSocket: ServerSocket? = null
    private var socket: Socket? = null
    private var connectThread: Thread? = null
    private var readerThread: Thread? = null

    fun start(isGroupOwner: Boolean, groupOwnerAddress: String?) {
        if (running) return
        running = true
        connectThread = Thread({
            if (isGroupOwner) runServer() else runClient(groupOwnerAddress)
        }, "p2p-connect").also { it.start() }
    }

    /** Enqueue a frame for sending. Non-blocking; write happens on the IO executor. */
    fun send(frame: ByteArray) {
        if (!running) return
        try {
            ioExecutor.execute {
                val o = out ?: return@execute
                try {
                    synchronized(writeLock) {
                        o.write(frame)
                        o.flush()
                    }
                } catch (e: IOException) {
                    if (running) onClosed(e.message)
                }
            }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            // Executor already shut down during teardown — nothing to send.
        }
    }

    fun stop() {
        running = false
        out = null
        closeQuietly(socket)
        closeQuietly(serverSocket)
        socket = null
        serverSocket = null
        readerThread?.interrupt()
        connectThread?.interrupt()
        readerThread = null
        connectThread = null
        runCatching { ioExecutor.shutdownNow() }
    }

    // -----------------------------------------------------------------------------------------

    private fun runServer() {
        try {
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(PORT))
            serverSocket = ss
            Log.d(TAG, "server listening on $PORT")
            val s = ss.accept() // one client per group
            closeQuietly(ss)
            serverSocket = null
            setupSocket(s)
        } catch (e: IOException) {
            if (running) {
                Log.w(TAG, "server error: ${e.message}")
                onClosed(e.message)
            }
        }
    }

    private fun runClient(groupOwnerAddress: String?) {
        val host = groupOwnerAddress?.takeIf { it.isNotBlank() } ?: DEFAULT_GROUP_OWNER
        var attempt = 0
        while (running && attempt < MAX_CONNECT_ATTEMPTS) {
            try {
                val s = Socket()
                s.connect(InetSocketAddress(host, PORT), CONNECT_TIMEOUT_MS)
                setupSocket(s)
                return
            } catch (e: IOException) {
                attempt++
                Log.d(TAG, "client connect attempt $attempt failed: ${e.message}")
                try {
                    Thread.sleep(CONNECT_RETRY_MS)
                } catch (ie: InterruptedException) {
                    return
                }
            }
        }
        if (running) onClosed("could not reach peer")
    }

    private fun setupSocket(s: Socket) {
        socket = s
        // Disable Nagle so small text frames go out immediately — critical for honest latency.
        runCatching { s.tcpNoDelay = true }
        out = BufferedOutputStream(s.getOutputStream())
        val input = BufferedInputStream(s.getInputStream())
        Log.d(TAG, "link established with ${s.inetAddress?.hostAddress}")
        onLinkReady()
        readerThread = Thread({ readLoop(input) }, "p2p-reader").also { it.start() }
    }

    private fun readLoop(input: InputStream) {
        val header = ByteArray(Packet.HEADER_LEN)
        try {
            while (running) {
                if (!readFully(input, header, 0, Packet.HEADER_LEN)) break
                val len = header[5].toInt() and 0xFF
                val frame = ByteArray(Packet.HEADER_LEN + len + Packet.CRC_LEN)
                System.arraycopy(header, 0, frame, 0, Packet.HEADER_LEN)
                if (!readFully(input, frame, Packet.HEADER_LEN, len + Packet.CRC_LEN)) break
                onFrame(frame)
            }
        } catch (e: IOException) {
            // Falls through to the close notification below.
        }
        if (running) onClosed("link closed")
    }

    /** Read exactly [count] bytes into [buf] at [offset]. Returns false on end-of-stream. */
    private fun readFully(input: InputStream, buf: ByteArray, offset: Int, count: Int): Boolean {
        var read = 0
        while (read < count) {
            val n = input.read(buf, offset + read, count - read)
            if (n < 0) return false
            read += n
        }
        return true
    }

    private fun closeQuietly(c: java.io.Closeable?) {
        try {
            c?.close()
        } catch (e: IOException) {
            // ignore
        }
    }

    companion object {
        private const val TAG = "P2pTransport"
        private const val PORT = 8988
        private const val DEFAULT_GROUP_OWNER = "192.168.49.1"
        private const val CONNECT_TIMEOUT_MS = 3000
        private const val CONNECT_RETRY_MS = 500L
        private const val MAX_CONNECT_ATTEMPTS = 20
    }
}
