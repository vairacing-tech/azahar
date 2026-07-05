package org.citra.citra_emu.moonlight.server

import java.io.DataInputStream
import java.io.EOFException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class LegacyControlTcpServer(
    private val port: Int,
    private val onIdrRequested: () -> Unit,
    private val onLog: (String) -> Unit,
) {
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    @Volatile private var lastIdrRequestEpochMillis = 0L

    fun start() {
        if (!running.compareAndSet(false, true)) return
        serverSocket = ServerSocket(port, 50, InetAddress.getByName("0.0.0.0")).also {
            it.soTimeout = 1_000
        }
        thread(name = "apsu-legacy-control", isDaemon = true) {
            while (running.get()) {
                val socket = runCatching { serverSocket?.accept() }.getOrNull() ?: continue
                thread(name = "apsu-legacy-control-client", isDaemon = true) {
                    runCatching { handle(socket) }
                        .onFailure { onLog("Legacy control error: ${it.message}") }
                    runCatching { socket.close() }
                }
            }
        }
        onLog("Legacy control listening on TCP $port")
    }

    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    private fun handle(socket: Socket) {
        ClientConnectionState.mark("Legacy control", socket.inetAddress.hostAddress)
        socket.tcpNoDelay = true
        socket.soTimeout = 2_000
        val input = DataInputStream(socket.getInputStream())
        val output = socket.getOutputStream()
        val header = ByteArray(4)
        while (running.get() && !socket.isClosed) {
            val hasHeader = try {
                input.readFully(header)
                true
            } catch (_: SocketTimeoutException) {
                false
            } catch (_: EOFException) {
                return
            }
            if (!hasHeader) continue
            val type = header.readU16Le(0)
            val payloadLength = header.readU16Le(2)
            if (payloadLength > 0) {
                val payload = ByteArray(payloadLength)
                if (!readFully(input, payload)) return
            }
            if (requestsIdr(type)) {
                requestIdrIfDue()
            }
            if (expectsReply(type)) {
                output.write(reply(type))
                output.flush()
            }
        }
    }

    private fun readFully(input: DataInputStream, buffer: ByteArray): Boolean =
        try {
            input.readFully(buffer)
            true
        } catch (_: EOFException) {
            false
        } catch (_: SocketTimeoutException) {
            false
        }

    private fun requestsIdr(type: Int): Boolean =
        type == REQUEST_IDR_GEN4 ||
            type == REQUEST_IDR_GEN3 ||
            type == INVALIDATE_REF_FRAMES_GEN4 ||
            type == INVALIDATE_REF_FRAMES_GEN3

    private fun expectsReply(type: Int): Boolean =
        type == REQUEST_IDR_GEN4 ||
            type == REQUEST_IDR_GEN3 ||
            type == START_B_GEN4 ||
            type == START_B_GEN3 ||
            type == INVALIDATE_REF_FRAMES_GEN4 ||
            type == INVALIDATE_REF_FRAMES_GEN3

    private fun reply(type: Int): ByteArray =
        ByteBuffer.allocate(4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putShort(type.toShort())
            .putShort(0)
            .array()

    private fun ByteArray.readU16Le(offset: Int): Int =
        (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)

    private fun requestIdrIfDue() {
        val now = System.currentTimeMillis()
        if (now - lastIdrRequestEpochMillis < IDR_REQUEST_MIN_INTERVAL_MS) return
        lastIdrRequestEpochMillis = now
        onIdrRequested.invoke()
    }

    companion object {
        private const val REQUEST_IDR_GEN3 = 0x1407
        private const val REQUEST_IDR_GEN4 = 0x0606
        private const val START_B_GEN3 = 0x1410
        private const val START_B_GEN4 = 0x0609
        private const val INVALIDATE_REF_FRAMES_GEN3 = 0x1404
        private const val INVALIDATE_REF_FRAMES_GEN4 = 0x0604
        private const val IDR_REQUEST_MIN_INTERVAL_MS = 250L
    }
}
