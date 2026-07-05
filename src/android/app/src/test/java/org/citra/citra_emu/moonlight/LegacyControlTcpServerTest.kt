package org.citra.citra_emu.moonlight

import org.citra.citra_emu.moonlight.server.LegacyControlTcpServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger

class LegacyControlTcpServerTest {
    @Test
    fun repliesToHandshakePacketsButNotPeriodicLossStats() {
        val idrRequests = AtomicInteger()
        withServer(idrRequests = idrRequests) { port ->
            Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 250
                val input = DataInputStream(socket.getInputStream())
                val output = DataOutputStream(socket.getOutputStream())

                sendPacket(output, REQUEST_IDR_GEN4, byteArrayOf(0, 0))
                assertReply(input, REQUEST_IDR_GEN4)
                assertEquals(1, idrRequests.get())

                sendPacket(output, START_B_GEN4, byteArrayOf(0))
                assertReply(input, START_B_GEN4)

                sendPacket(output, LOSS_STATS_GEN4, ByteArray(32))
                assertNoReply(input)

                sendPacket(output, LOSS_STATS_GEN4, ByteArray(32))
                assertNoReply(input)
            }
        }
    }

    @Test
    fun keepsIdleControlSocketOpenForMoonlightSession() {
        withServer { port ->
            Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 500
                val input = DataInputStream(socket.getInputStream())
                val output = DataOutputStream(socket.getOutputStream())

                Thread.sleep(2_300)

                sendPacket(output, REQUEST_IDR_GEN4, byteArrayOf(0, 0))
                assertReply(input, REQUEST_IDR_GEN4)
            }
        }
    }

    private fun withServer(
        idrRequests: AtomicInteger = AtomicInteger(),
        block: (Int) -> Unit,
    ) {
        val port = freePort()
        val server = LegacyControlTcpServer(
            port = port,
            onIdrRequested = { idrRequests.incrementAndGet() },
            onLog = {},
        )
        try {
            server.start()
            block(port)
        } finally {
            server.stop()
        }
    }

    private fun freePort(): Int =
        ServerSocket(0).use { it.localPort }

    private fun sendPacket(output: DataOutputStream, type: Int, payload: ByteArray) {
        output.writeShortLE(type)
        output.writeShortLE(payload.size)
        output.write(payload)
        output.flush()
    }

    private fun assertReply(input: DataInputStream, expectedType: Int) {
        val type = input.readUnsignedShortLE()
        val length = input.readUnsignedShortLE()
        assertEquals(expectedType, type)
        assertEquals(0, length)
    }

    private fun assertNoReply(input: DataInputStream) {
        try {
            input.readByte()
        } catch (_: SocketTimeoutException) {
            return
        }
        throw AssertionError("Loss stats packet received an unexpected control reply")
    }

    private fun DataOutputStream.writeShortLE(value: Int) {
        write(value and 0xFF)
        write((value ushr 8) and 0xFF)
    }

    private fun DataInputStream.readUnsignedShortLE(): Int {
        val low = readUnsignedByte()
        val high = readUnsignedByte()
        return low or (high shl 8)
    }

    companion object {
        private const val REQUEST_IDR_GEN4 = 0x0606
        private const val START_B_GEN4 = 0x0609
        private const val LOSS_STATS_GEN4 = 0x060A
    }
}
