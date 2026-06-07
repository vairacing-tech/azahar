package org.citra.citra_emu.moonlight.server

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class TcpInputSinkServer(
    private val port: Int,
    private val onLog: (String) -> Unit,
) {
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        serverSocket = ServerSocket(port, 50, InetAddress.getByName("0.0.0.0")).also {
            it.soTimeout = 1_000
        }
        thread(name = "apsu-input-sink", isDaemon = true) {
            while (running.get()) {
                val socket = runCatching { serverSocket?.accept() }.getOrNull() ?: continue
                thread(name = "apsu-input-sink-client", isDaemon = true) {
                    runCatching { drain(socket) }
                    runCatching { socket.close() }
                }
            }
        }
        onLog("Legacy input sink listening on TCP $port")
    }

    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    private fun drain(socket: Socket) {
        ClientConnectionState.mark("Legacy input", socket.inetAddress.hostAddress)
        socket.tcpNoDelay = true
        socket.soTimeout = 2_000
        val buffer = ByteArray(4096)
        val input = socket.getInputStream()
        while (running.get()) {
            try {
                if (input.read(buffer) < 0) return
            } catch (_: SocketTimeoutException) {
                continue
            }
        }
    }
}
