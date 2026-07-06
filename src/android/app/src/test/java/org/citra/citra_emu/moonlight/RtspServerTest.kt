package org.citra.citra_emu.moonlight

import org.citra.citra_emu.moonlight.model.StreamConfig
import org.citra.citra_emu.moonlight.server.RtspServer
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets

class RtspServerTest {
    @Test
    fun closesTcpConnectionAfterOptionsResponseForMoonlightTransactions() {
        withServer { port ->
            Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 750
                val request = "OPTIONS rtsp://127.0.0.1:$port RTSP/1.0\r\n" +
                    "CSeq: 1\r\n" +
                    "X-GS-ClientVersion: 13\r\n" +
                    "Host: 127.0.0.1\r\n" +
                    "\r\n"

                socket.getOutputStream().write(request.toByteArray(StandardCharsets.UTF_8))
                socket.getOutputStream().flush()

                val response = readUntilEof(socket)

                assertTrue(response.startsWith("RTSP/1.0 200 OK"))
                assertTrue(response.contains("CSeq: 1"))
                assertTrue(response.contains("Public: OPTIONS"))
            }
        }
    }

    private fun withServer(block: (Int) -> Unit) {
        val port = freePort()
        val server = RtspServer(
            port = port,
            currentConfig = { StreamConfig() },
            activeVideoMime = { "video/hevc" },
            onVideoPacketSize = {},
            onStreamConfigRequested = { true },
            onPlay = {},
            onLog = {},
        )
        try {
            server.start()
            block(port)
        } finally {
            server.stop()
        }
    }

    private fun readUntilEof(socket: Socket): String {
        val bytes = ByteArrayOutputStream()
        val input = socket.getInputStream()
        try {
            while (true) {
                val value = input.read()
                if (value < 0) {
                    break
                }
                bytes.write(value)
            }
        } catch (_: SocketTimeoutException) {
            fail("RTSP server did not close the TCP transaction after responding")
        }
        return bytes.toString(StandardCharsets.UTF_8.name())
    }

    private fun freePort(): Int =
        ServerSocket(0).use { it.localPort }
}
