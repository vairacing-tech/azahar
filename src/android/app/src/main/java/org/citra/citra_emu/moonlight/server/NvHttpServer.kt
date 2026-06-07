package org.citra.citra_emu.moonlight.server

import org.citra.citra_emu.moonlight.crypto.ServerIdentity
import org.citra.citra_emu.moonlight.model.StreamConfig
import org.citra.citra_emu.moonlight.pairing.PairingPin
import org.citra.citra_emu.moonlight.pairing.PairingProtocol
import org.citra.citra_emu.moonlight.pairing.PairingStore
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLServerSocket
import kotlin.concurrent.thread

class NvHttpServer(
    private val httpPort: Int,
    private val httpsPort: Int,
    private val serverIdentity: ServerIdentity,
    private val pairingStore: PairingStore,
    private val pairingProtocol: PairingProtocol,
    private val uniqueId: String,
    private val currentConfig: () -> StreamConfig,
    private val activeVideoMime: () -> String,
    private val onPinReceived: (String) -> Unit,
    private val onLaunchRequested: (StreamConfig?) -> Boolean,
    private val onLog: (String) -> Unit,
) {
    private val running = AtomicBoolean(false)
    private val currentGameId = AtomicInteger(0)
    private var httpSocket: ServerSocket? = null
    private var httpsSocket: ServerSocket? = null
    private var httpThread: Thread? = null
    private var httpsThread: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        httpSocket = ServerSocket(httpPort, 50, InetAddress.getByName("0.0.0.0")).also {
            it.soTimeout = 1_000
        }
        httpsSocket = (serverIdentity.sslServerSocketFactory.createServerSocket(
            httpsPort,
            50,
            InetAddress.getByName("0.0.0.0"),
        ) as SSLServerSocket).also {
            it.needClientAuth = false
            it.wantClientAuth = false
            it.soTimeout = 1_000
        }
        httpThread = acceptLoop("apsu-nvhttp", httpSocket, secure = false)
        httpsThread = acceptLoop("apsu-nvhttps", httpsSocket, secure = true)
        onLog("NVHTTP listening on TCP $httpPort and TLS $httpsPort")
    }

    fun stop() {
        running.set(false)
        runCatching { httpSocket?.close() }
        runCatching { httpsSocket?.close() }
        httpSocket = null
        httpsSocket = null
        httpThread = null
        httpsThread = null
    }

    private fun acceptLoop(name: String, serverSocket: ServerSocket?, secure: Boolean): Thread =
        thread(name = name, isDaemon = true) {
            while (running.get()) {
                val socket = runCatching { serverSocket?.accept() }.getOrNull() ?: continue
                thread(name = "$name-client", isDaemon = true) {
                    runCatching { handle(socket, secure) }
                        .onFailure { onLog("${if (secure) "NVHTTPS" else "NVHTTP"} error: ${it.message}") }
                    runCatching { socket.close() }
                }
            }
        }

    private fun handle(socket: Socket, secure: Boolean) {
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
        val requestLine = reader.readLine() ?: return
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
        }

        val parts = requestLine.split(" ")
        val target = parts.getOrNull(1) ?: "/"
        val uri = URI("http://localhost$target")
        val query = parseQuery(uri.rawQuery)
        val path = uri.path.lowercase()
        val localAddress = socket.localAddress.hostAddress?.takeUnless { it == "0.0.0.0" } ?: "127.0.0.1"
        ClientConnectionState.mark(if (secure) "NVHTTPS" else "NVHTTP", socket.inetAddress.hostAddress)
        onLog("${if (secure) "NVHTTPS" else "NVHTTP"} ${parts.firstOrNull().orEmpty()} $path")
        val response = when (path) {
            "/serverinfo", "/serverinfo.xml" -> xml(serverInfo(localAddress, secure))
            "/applist", "/applist.xml" -> xml(appList())
            "/appasset", "/appasset.xml" -> png(DEFAULT_APP_ASSET_PNG)
            "/pair", "/pair.xml" -> xml(pair(query))
            "/unpair", "/unpair.xml" -> {
                pairingProtocol.unpair()
                currentGameId.set(0)
                ClientConnectionState.setCurrentGame(0)
                xml(okXml("unpair"))
            }
            "/launch", "/launch.xml" -> xml(launch(localAddress, query))
            "/resume", "/resume.xml" -> xml(launch(localAddress, query, resume = true))
            "/cancel", "/cancel.xml" -> xml(cancel())
            "/pin", "/pin.xml" -> xml(pin(query))
            else -> xml(errorXml(404, "Unknown endpoint: $path"), httpStatus = 404)
        }

        val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))
        writer.write("HTTP/1.1 ${response.httpStatus} ${response.reason}\r\n")
        writer.write("Content-Type: ${response.contentType}\r\n")
        writer.write("Content-Length: ${response.body.size}\r\n")
        writer.write("Connection: close\r\n")
        writer.write("\r\n")
        writer.flush()
        socket.getOutputStream().write(response.body)
        socket.getOutputStream().flush()
    }

    private fun serverInfo(localAddress: String, secure: Boolean): String {
        val paired = pairingStore.list().isNotEmpty()
        val pairStatus = if (secure && paired) 1 else 0
        val config = currentConfig()
        val codecSupport = when (activeVideoMime()) {
            "video/hevc" -> 0x00000101
            else -> 0x00000001
        }
        return """
            <?xml version="1.0" encoding="utf-8"?>
            <root status_code="200">
              <hostname>Azahar Main Screen</hostname>
              <appversion>${GameStreamProtocol.APP_VERSION}</appversion>
              <GfeVersion>${GameStreamProtocol.GFE_VERSION}</GfeVersion>
              <uniqueid>$uniqueId</uniqueid>
              <HttpsPort>${Ports.HTTPS}</HttpsPort>
              <ExternalPort>${Ports.HTTP}</ExternalPort>
              <RtspPort>${Ports.RTSP}</RtspPort>
              <PairStatus>$pairStatus</PairStatus>
              <currentgame>${currentGameId.get()}</currentgame>
              <state>MJOLNIR_SERVER_AVAILABLE</state>
              <MaxLumaPixelsH264>1869449984</MaxLumaPixelsH264>
              <MaxLumaPixelsHEVC>${if (activeVideoMime() == "video/hevc") "1869449984" else "0"}</MaxLumaPixelsHEVC>
              <ServerCodecModeSupport>$codecSupport</ServerCodecModeSupport>
              <gputype>Android Hardware Encoder</gputype>
              <LocalIP>$localAddress</LocalIP>
              <mac>00:00:00:00:00:00</mac>
              <codec>${config.codecPreference.name}</codec>
              <height>${config.height}</height>
              <width>${config.width}</width>
              <fps>${config.fps}</fps>
            </root>
        """.trimIndent()
    }

    private fun appList(): String =
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<root status_code=\"200\">" +
            "<App><IsHdrSupported>0</IsHdrSupported><AppTitle>Azahar Main Screen</AppTitle><ID>1</ID></App>" +
            "</root>"

    private fun pair(query: Map<String, String>): String {
        val uniqueId = query["uniqueid"] ?: "0123456789ABCDEF"
        return pairingProtocol.handle(uniqueId, query)
    }

    private fun pin(query: Map<String, String>): String {
        val pin = PairingPin.fromQuery(query)
            ?: return errorXml(400, "PIN must be exactly 4 digits")
        onPinReceived(pin)
        onLog("Pairing PIN received via NVHTTP")
        return okXml("pin")
    }

    private fun launch(localAddress: String, query: Map<String, String>, resume: Boolean = false): String {
        val requestedConfig = ClientStreamConfig.fromLaunchQuery(query, currentConfig())
        val accepted = onLaunchRequested(requestedConfig)
        return if (accepted) {
            val appId = query.firstInt("appid", "appId", "id").coerceAtLeast(1)
            currentGameId.set(appId)
            ClientConnectionState.setCurrentGame(appId)
            onLog("${if (resume) "Resume" else "Launch"} accepted for app $appId")
            val tag = if (resume) "resume" else "gamesession"
            """
                <?xml version="1.0" encoding="utf-8"?>
                <root status_code="200">
                  <sessionUrl0>rtsp://$localAddress:${Ports.RTSP}</sessionUrl0>
                  <$tag>1</$tag>
                </root>
            """.trimIndent()
        } else {
            errorXml(409, "Launch rejected")
        }
    }

    private fun cancel(): String {
        currentGameId.set(0)
        ClientConnectionState.setCurrentGame(0)
        onLog("Game session cancelled")
        return okXml("cancel")
    }

    private fun okXml(name: String): String =
        """
            <?xml version="1.0" encoding="utf-8"?>
            <root status_code="200"><$name>1</$name></root>
        """.trimIndent()

    private fun errorXml(status: Int, message: String): String =
        """
            <?xml version="1.0" encoding="utf-8"?>
            <root status_code="$status" status_message="$message"><error>$message</error></root>
        """.trimIndent()

    private fun parseQuery(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        return rawQuery.split("&").mapNotNull { part ->
            val pieces = part.split("=", limit = 2)
            val key = pieces.getOrNull(0)?.decodeUrl() ?: return@mapNotNull null
            val value = pieces.getOrNull(1)?.decodeUrl().orEmpty()
            key to value
        }.toMap()
    }

    private fun String.decodeUrl(): String =
        URLDecoder.decode(this, StandardCharsets.UTF_8.name())

    private fun Map<String, String>.firstInt(vararg names: String): Int {
        names.forEach { name ->
            this[name]?.toIntOrNull()?.let { return it }
        }
        return 1
    }

    private fun xml(body: String, httpStatus: Int = 200): NvHttpResponse =
        NvHttpResponse(
            httpStatus = httpStatus,
            reason = if (httpStatus == 200) "OK" else "NOT FOUND",
            contentType = "text/xml; charset=utf-8",
            body = body.toByteArray(StandardCharsets.UTF_8),
        )

    private fun png(body: ByteArray): NvHttpResponse =
        NvHttpResponse(
            httpStatus = 200,
            reason = "OK",
            contentType = "image/png",
            body = body,
        )

    private data class NvHttpResponse(
        val httpStatus: Int,
        val reason: String,
        val contentType: String,
        val body: ByteArray,
    )

    companion object {
        private val DEFAULT_APP_ASSET_PNG: ByteArray = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAFgwJ/lz9tJwAAAABJRU5ErkJggg==",
        )
    }
}
