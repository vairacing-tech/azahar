package org.citra.citra_emu.moonlight.server

import org.citra.citra_emu.moonlight.model.StreamConfig
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class RtspServer(
    private val port: Int,
    private val currentConfig: () -> StreamConfig,
    private val activeVideoMime: () -> String,
    private val onVideoPacketSize: (Int) -> Unit,
    private val onStreamConfigRequested: (StreamConfig) -> Boolean,
    private val onPlay: () -> Unit,
    private val onLog: (String) -> Unit,
) {
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private val sessionId = UUID.randomUUID().toString().replace("-", "").take(16)

    fun start() {
        if (!running.compareAndSet(false, true)) return
        serverSocket = ServerSocket(port, 50, InetAddress.getByName("0.0.0.0")).also {
            it.soTimeout = 1_000
        }
        thread(name = "apsu-rtsp", isDaemon = true) {
            while (running.get()) {
                val socket = runCatching { serverSocket?.accept() }.getOrNull() ?: continue
                thread(name = "apsu-rtsp-client", isDaemon = true) {
                    runCatching { handleOne(socket) }
                        .onFailure { onLog("RTSP error: ${it.message}") }
                    runCatching { socket.close() }
                }
            }
        }
        onLog("RTSP listening on TCP $port")
    }

    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    private fun handleOne(socket: Socket) {
        ClientConnectionState.mark("RTSP", socket.inetAddress.hostAddress)
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
        val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))
        while (running.get() && !socket.isClosed) {
            val requestLine = reader.readLine() ?: return
            if (requestLine.isBlank()) continue
            if (!handleRequest(requestLine, reader, writer)) {
                return
            }
        }
    }

    private fun handleRequest(
        requestLine: String,
        reader: BufferedReader,
        writer: BufferedWriter,
    ): Boolean {
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = reader.readLine() ?: return false
            if (line.isEmpty()) break
            val index = line.indexOf(':')
            if (index > 0) {
                headers[line.substring(0, index).trim().lowercase()] = line.substring(index + 1).trim()
            }
        }

        val method = requestLine.substringBefore(' ').uppercase()
        val target = requestLine.substringAfter(' ', "").substringBefore(' ')
        val cseq = headers["cseq"] ?: "1"
        val body = readBody(reader, headers["content-length"]?.toIntOrNull() ?: 0)
        onLog("RTSP $method $target")
        var responseStatus = 200
        var responseReason = "OK"
        if (method == "ANNOUNCE" && !parseAnnounce(body)) {
            responseStatus = 500
            responseReason = "ERROR"
        }
        val responseBody = if (method == "DESCRIBE") describeBody() else ""
        val extraHeaders = buildList {
            add("Server: AzaharMoonlight/0.1.0")
            when (method) {
                "OPTIONS" -> add("Public: OPTIONS, DESCRIBE, SETUP, ANNOUNCE, PLAY, TEARDOWN")
                "SETUP" -> setupHeaders(target).forEach(::add)
                "PLAY" -> {
                    add("Session: $sessionId")
                    add("Range: npt=0.000-")
                    onPlay()
                }
                "ANNOUNCE", "TEARDOWN" -> add("Session: $sessionId")
            }
        }

        writer.write("RTSP/1.0 $responseStatus $responseReason\r\n")
        writer.write("CSeq: $cseq\r\n")
        extraHeaders.forEach { writer.write("$it\r\n") }
        if (responseBody.isNotEmpty()) {
            writer.write("Content-Type: application/sdp\r\n")
            writer.write("Content-Length: ${responseBody.toByteArray(StandardCharsets.UTF_8).size}\r\n")
        }
        writer.write("\r\n")
        writer.write(responseBody)
        writer.flush()
        return method != "TEARDOWN" && responseStatus < 500
    }

    private fun readBody(reader: BufferedReader, contentLength: Int): String {
        if (contentLength <= 0) return ""
        val chars = CharArray(contentLength)
        var offset = 0
        while (offset < contentLength) {
            val read = reader.read(chars, offset, contentLength - offset)
            if (read <= 0) break
            offset += read
        }
        return String(chars, 0, offset)
    }

    private fun parseAnnounce(body: String): Boolean {
        val packetSize = PACKET_SIZE_PATTERN.find(body)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
        if (packetSize != null) {
            onVideoPacketSize(packetSize)
        }
        val requestedConfig = ClientStreamConfig.fromRtspAnnounce(body, currentConfig())
        return onStreamConfigRequested(requestedConfig)
    }

    private fun setupHeaders(target: String): List<String> {
        val streamPort = when {
            target.contains("audio", ignoreCase = true) -> Ports.AUDIO
            target.contains("control", ignoreCase = true) -> Ports.CONTROL
            else -> Ports.VIDEO
        }
        val headers = mutableListOf(
            "Session: $sessionId;timeout=90",
            "Transport: unicast;server_port=$streamPort-${streamPort + 1};source=0.0.0.0",
        )
        if (target.contains("video", ignoreCase = true) || target.contains("audio", ignoreCase = true)) {
            headers += "X-SS-Ping-Payload: SolarisPing00000"
        }
        if (target.contains("control", ignoreCase = true)) {
            headers += "X-SS-Connect-Data: 305419896"
        }
        return headers
    }

    private fun describeBody(): String {
        val config = currentConfig()
        val videoCodecLines = if (activeVideoMime() == "video/hevc") {
            """
            m=video ${Ports.VIDEO} RTP/AVP 96
            a=rtpmap:96 H265/90000
            a=fmtp:96 packetization-mode=1
            """.trimIndent()
        } else {
            """
            m=video ${Ports.VIDEO} RTP/AVP 96
            a=rtpmap:96 H264/90000
            a=fmtp:96 packetization-mode=1
            """.trimIndent()
        }
        return """
            v=0
            o=android 0 0 IN IP4 127.0.0.1
            s=Azahar Main Screen
            t=0 0
            a=x-nv-general.serverAddress:0.0.0.0
            a=x-nv-video[0].clientViewportWd:${config.width}
            a=x-nv-video[0].clientViewportHt:${config.height}
            a=x-nv-video[0].maxFPS:${config.fps}
            a=x-nv-video[0].averageBitrate:${config.bitrate / 1000}
            a=x-nv-video[0].peakBitrate:${config.bitrate / 1000}
            a=x-nv-video[0].timeoutLengthMs:7000
            a=x-nv-video[0].framesWithInvalidRefThreshold:0
            a=x-nv-video[0].refPicInvalidation:1
            a=x-ss-general.featureFlags:0
            a=x-ss-general.encryptionSupported:0
            a=x-ss-general.encryptionRequested:0
            a=x-nv-audio.surround.numChannels:2
            a=x-nv-audio.surround.channelMask:3
            a=x-nv-audio.surround.enable:0
            a=x-nv-aqos.packetDuration:5
            m=audio ${Ports.AUDIO} RTP/AVP 97
            a=rtpmap:97 opus/48000/2
            a=fmtp:97 minptime=5;maxptime=5;useinbandfec=0
            $videoCodecLines
        """.trimIndent().replace("\n", "\r\n")
    }

    companion object {
        private val PACKET_SIZE_PATTERN = Regex("""x-nv-video\[0]\.packetSize:(\d+)""")
    }
}
