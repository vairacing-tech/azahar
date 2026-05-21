// Copyright Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

package org.citra.citra_emu.dualdevice

import android.app.Dialog
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.Surface
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.citra.citra_emu.NativeLibrary
import org.citra.citra_emu.R
import org.citra.citra_emu.activities.EmulationActivity
import org.citra.citra_emu.display.ScreenLayout
import org.citra.citra_emu.display.SecondaryDisplayLayout
import org.citra.citra_emu.features.settings.model.BooleanSetting
import org.citra.citra_emu.features.settings.model.IntSetting
import org.citra.citra_emu.features.settings.model.Settings
import org.citra.citra_emu.features.settings.utils.SettingsFile
import org.citra.citra_emu.utils.EmulationMenuSettings
import org.citra.citra_emu.utils.Log
import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCodecInfo
import org.webrtc.VideoEncoder
import org.webrtc.VideoEncoderFactory
import org.webrtc.VideoSink
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

class TvMainScreenCastHost(
    private val activity: EmulationActivity,
    private val settings: Settings,
    private val releaseSecondaryDisplay: () -> Unit,
    private val restoreSecondaryDisplay: () -> Unit,
) : Closeable {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor: ExecutorService = Executors.newCachedThreadPool()
    private val audioExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)
    private val pin = "%06d".format(SecureRandom().nextInt(1_000_000))
    private val peerLock = Any()
    private val pendingAudioTasks = AtomicInteger(0)
    private val audioResampler = Pcm16Resampler(NATIVE_SAMPLE_RATE, WEB_AUDIO_SAMPLE_RATE)

    private var serverSocket: ServerSocket? = null
    private var webSocket: WebSocketConnection? = null
    private var pairingDialog: Dialog? = null
    private var statusText: TextView? = null
    private var audioMode = AudioMode.Host

    private var eglBase: EglBase? = null
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var captureSurface: Surface? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var peerConnection: PeerConnection? = null
    private var audioDataChannel: DataChannel? = null

    private val previousScreenLayout = IntSetting.SCREEN_LAYOUT.int
    private val previousSecondaryLayout = IntSetting.SECONDARY_DISPLAY_LAYOUT.int
    private val previousSwapScreen = BooleanSetting.SWAP_SCREEN.boolean
    private val previousMenuSwapScreen = EmulationMenuSettings.swapScreens

    val isRunning: Boolean
        get() = running.get()

    fun start() {
        if (!running.compareAndSet(false, true)) {
            return
        }

        try {
            serverSocket = ServerSocket(0)
            applyCastLayout()
            startWebRtcCapture()
            NativeLibrary.setTvAudioFrameCallback { samples, sampleRate, channels, frames ->
                onNativeAudioFrame(samples, sampleRate, channels, frames)
            }
            executor.execute(::acceptHttpClients)
        } catch (e: Exception) {
            close()
            throw e
        }
    }

    fun showPairingDialog() {
        val density = activity.resources.displayMetrics.density
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (24 * density).toInt()
            setPadding(padding, padding, padding, 0)
        }

        content.addView(TextView(activity).apply {
            setText(R.string.tv_main_screen_cast_pairing_message)
        })
        content.addView(TextView(activity).apply {
            text = pairingUrl()
            textSize = 18f
            setTextIsSelectable(true)
        })
        content.addView(TextView(activity).apply {
            text = activity.getString(R.string.tv_main_screen_cast_pin, pin)
            textSize = 28f
            textAlignment = TextView.TEXT_ALIGNMENT_CENTER
            setPadding(0, (16 * density).toInt(), 0, (12 * density).toInt())
        })
        content.addView(TextView(activity).apply {
            setText(R.string.tv_main_screen_cast_audio_mode)
        })
        content.addView(RadioGroup(activity).apply {
            orientation = RadioGroup.HORIZONTAL
            AudioMode.entries.forEachIndexed { index, mode ->
                addView(RadioButton(activity).apply {
                    id = AUDIO_MODE_RADIO_BASE_ID + index
                    text = activity.getString(mode.labelResId)
                    isChecked = mode == audioMode
                })
            }
            setOnCheckedChangeListener { _, checkedId ->
                val index = checkedId - AUDIO_MODE_RADIO_BASE_ID
                if (index in AudioMode.entries.indices) {
                    setAudioMode(AudioMode.entries[index])
                }
            }
        })
        statusText = TextView(activity).apply {
            setText(R.string.tv_main_screen_cast_waiting)
            textAlignment = TextView.TEXT_ALIGNMENT_CENTER
            setPadding(0, (12 * density).toInt(), 0, 0)
        }
        content.addView(statusText)

        pairingDialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.tv_main_screen_cast_pairing_title)
            .setView(content)
            .setNegativeButton(android.R.string.cancel) { _, _ -> close() }
            .setOnCancelListener { close() }
            .show()
    }

    private fun pairingUrl(): String = "http://${findLocalIpv4Address()}:${serverSocket?.localPort ?: 0}/tv"

    private fun applyCastLayout() {
        IntSetting.SCREEN_LAYOUT.int = ScreenLayout.SINGLE_SCREEN.int
        BooleanSetting.SWAP_SCREEN.boolean = true
        EmulationMenuSettings.swapScreens = true
        IntSetting.SECONDARY_DISPLAY_LAYOUT.int = SecondaryDisplayLayout.TOP_SCREEN.int
        settings.saveSetting(IntSetting.SCREEN_LAYOUT, SettingsFile.FILE_NAME_CONFIG)
        settings.saveSetting(BooleanSetting.SWAP_SCREEN, SettingsFile.FILE_NAME_CONFIG)
        settings.saveSetting(IntSetting.SECONDARY_DISPLAY_LAYOUT, SettingsFile.FILE_NAME_CONFIG)
        NativeLibrary.reloadSettings()
        NativeLibrary.swapScreens(true, activity.windowManager.defaultDisplay.rotation)
        NativeLibrary.updateFramebuffer(NativeLibrary.isPortraitMode)
        releaseSecondaryDisplay()
    }

    private fun restoreLayout() {
        IntSetting.SCREEN_LAYOUT.int = previousScreenLayout
        BooleanSetting.SWAP_SCREEN.boolean = previousSwapScreen
        EmulationMenuSettings.swapScreens = previousMenuSwapScreen
        IntSetting.SECONDARY_DISPLAY_LAYOUT.int = previousSecondaryLayout
        settings.saveSetting(IntSetting.SCREEN_LAYOUT, SettingsFile.FILE_NAME_CONFIG)
        settings.saveSetting(BooleanSetting.SWAP_SCREEN, SettingsFile.FILE_NAME_CONFIG)
        settings.saveSetting(IntSetting.SECONDARY_DISPLAY_LAYOUT, SettingsFile.FILE_NAME_CONFIG)
        NativeLibrary.reloadSettings()
        NativeLibrary.swapScreens(previousSwapScreen, activity.windowManager.defaultDisplay.rotation)
        NativeLibrary.updateFramebuffer(NativeLibrary.isPortraitMode)
    }

    private fun startWebRtcCapture() {
        ensurePeerConnectionFactoryInitialized(activity.applicationContext)
        val egl = EglBase.create()
        eglBase = egl
        val encoderFactory = H264OnlyVideoEncoderFactory(
            DefaultVideoEncoderFactory(egl.eglBaseContext, true, false)
        )
        val factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .createPeerConnectionFactory()
        peerConnectionFactory = factory

        val source = factory.createVideoSource(false)
        videoSource = source
        val track = factory.createVideoTrack(VIDEO_TRACK_ID, source)
        track.setEnabled(true)
        videoTrack = track

        val textureHelper = SurfaceTextureHelper.create("AzaharTvMainScreen", egl.eglBaseContext)
            ?: throw IllegalStateException("Unable to create WebRTC texture helper")
        textureHelper.setTextureSize(TOP_SCREEN_WIDTH, TOP_SCREEN_HEIGHT)
        surfaceTextureHelper = textureHelper
        source.capturerObserver.onCapturerStarted(true)
        textureHelper.startListening(VideoSink { frame ->
            source.capturerObserver.onFrameCaptured(frame)
        })
        captureSurface = Surface(textureHelper.surfaceTexture)
        NativeLibrary.secondarySurfaceChanged(captureSurface!!)
    }

    private fun acceptHttpClients() {
        while (running.get()) {
            val socket = try {
                serverSocket?.accept() ?: return
            } catch (_: Exception) {
                return
            }
            executor.execute { handleHttpClient(socket) }
        }
    }

    private fun handleHttpClient(socket: Socket) {
        socket.use { client ->
            try {
                client.soTimeout = HTTP_SOCKET_TIMEOUT_MS
                val input = BufferedInputStream(client.getInputStream())
                val output = BufferedOutputStream(client.getOutputStream())
                val request = readHttpRequest(input) ?: return
                when {
                    request.path == "/" || request.path == "/tv" -> serveTvPage(output)
                    request.path == "/favicon.ico" -> serveNoContent(output)
                    request.path == "/signal" -> upgradeWebSocket(request, client, input, output)
                    else -> serveNotFound(output)
                }
            } catch (e: Exception) {
                if (running.get()) {
                    Log.error("[TvMainScreenCastHost] HTTP client failed: ${e.message}")
                }
            }
        }
    }

    private fun upgradeWebSocket(
        request: HttpRequest,
        socket: Socket,
        input: BufferedInputStream,
        output: BufferedOutputStream,
    ) {
        if (request.query["pin"] != pin) {
            serveForbidden(output)
            return
        }
        val key = request.headers["sec-websocket-key"]
        if (key.isNullOrBlank()) {
            serveBadRequest(output)
            return
        }

        val acceptKey = websocketAcceptKey(key)
        output.write(
            (
                "HTTP/1.1 101 Switching Protocols\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Sec-WebSocket-Accept: $acceptKey\r\n" +
                    "\r\n"
                ).toByteArray(StandardCharsets.US_ASCII)
        )
        output.flush()

        socket.soTimeout = 0
        val connection = WebSocketConnection(socket, input, output)
        synchronized(peerLock) {
            webSocket?.closeQuietly()
            closePeerConnectionLocked()
            webSocket = connection
            createPeerConnectionLocked()
        }
        setStatus(R.string.tv_main_screen_cast_connected)
        sendStatus("connected")
        mainHandler.post {
            Toast.makeText(activity, R.string.tv_main_screen_cast_connected, Toast.LENGTH_SHORT).show()
        }

        try {
            while (running.get()) {
                val message = connection.readTextFrame() ?: break
                handleSignalMessage(message)
            }
        } finally {
            synchronized(peerLock) {
                if (webSocket === connection) {
                    webSocket = null
                    closePeerConnectionLocked()
                    if (running.get()) {
                        setStatus(R.string.tv_main_screen_cast_waiting)
                    }
                }
            }
        }
    }

    private fun createPeerConnectionLocked() {
        val factory = peerConnectionFactory ?: throw IllegalStateException("WebRTC not initialized")
        val config = PeerConnection.RTCConfiguration(emptyList<PeerConnection.IceServer>()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        val connection = factory.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                sendStatus(state.name.lowercase(Locale.US))
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
            override fun onIceCandidate(candidate: IceCandidate) {
                sendJson(
                    JSONObject()
                        .put("type", "ice")
                        .put("sdpMid", candidate.sdpMid)
                        .put("sdpMLineIndex", candidate.sdpMLineIndex)
                        .put("candidate", candidate.sdp)
                )
            }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
            override fun onAddStream(stream: org.webrtc.MediaStream) = Unit
            override fun onRemoveStream(stream: org.webrtc.MediaStream) = Unit
            override fun onDataChannel(channel: DataChannel) {
                if (channel.label() == AUDIO_DATA_CHANNEL_LABEL) {
                    registerAudioDataChannel(channel)
                }
            }
            override fun onRenegotiationNeeded() = Unit
            override fun onAddTrack(
                receiver: org.webrtc.RtpReceiver,
                streams: Array<out org.webrtc.MediaStream>,
            ) = Unit
        }) ?: throw IllegalStateException("Unable to create WebRTC peer connection")

        val track = videoTrack ?: throw IllegalStateException("Video track unavailable")
        val transceiverInit = RtpTransceiver.RtpTransceiverInit(
            RtpTransceiver.RtpTransceiverDirection.SEND_ONLY
        )
        connection.addTransceiver(track, transceiverInit)
        peerConnection = connection
    }

    private fun handleSignalMessage(message: String) {
        val json = try {
            JSONObject(message)
        } catch (_: Exception) {
            sendSignalError("Invalid signaling JSON")
            return
        }
        when (json.optString("type")) {
            "hello" -> sendStatus("ready")
            "offer" -> handleOffer(json.optString("sdp"))
            "ice" -> handleRemoteIce(json)
            "stop" -> close()
            else -> sendSignalError("Unsupported signaling message")
        }
    }

    private fun handleOffer(sdp: String) {
        if (!sdp.contains("H264/90000", ignoreCase = true)) {
            sendSignalError("This TV browser did not offer H.264 video.")
            return
        }
        val connection = synchronized(peerLock) { peerConnection } ?: return
        val offer = SessionDescription(SessionDescription.Type.OFFER, sdp)
        connection.setRemoteDescription(
            SimpleSdpObserver(
                onSetSuccess = {
                    connection.createAnswer(
                        SimpleSdpObserver(
                            onCreateSuccess = { answer ->
                                val localAnswer = SessionDescription(
                                    answer.type,
                                    preferH264Sdp(answer.description)
                                )
                                connection.setLocalDescription(
                                    SimpleSdpObserver(
                                        onSetSuccess = {
                                            sendJson(
                                                JSONObject()
                                                    .put("type", "answer")
                                                    .put("sdp", localAnswer.description)
                                            )
                                        },
                                        onFailure = ::sendSignalError
                                    ),
                                    localAnswer
                                )
                            },
                            onFailure = ::sendSignalError
                        ),
                        MediaConstraints()
                    )
                },
                onFailure = ::sendSignalError
            ),
            offer
        )
    }

    private fun handleRemoteIce(json: JSONObject) {
        val candidate = json.optString("candidate")
        if (candidate.isBlank()) return
        val iceCandidate = IceCandidate(
            json.optString("sdpMid"),
            json.optInt("sdpMLineIndex"),
            candidate
        )
        synchronized(peerLock) { peerConnection }?.addIceCandidate(iceCandidate)
    }

    private fun registerAudioDataChannel(channel: DataChannel) {
        synchronized(peerLock) {
            audioDataChannel?.unregisterObserver()
            audioDataChannel?.dispose()
            audioDataChannel = channel
        }
        channel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) = Unit
            override fun onStateChange() {
                updateAudioRouting()
                sendStatus("audio_${channel.state().name.lowercase(Locale.US)}")
            }
            override fun onMessage(buffer: DataChannel.Buffer) = Unit
        })
        updateAudioRouting()
    }

    private fun setAudioMode(mode: AudioMode) {
        audioMode = mode
        updateAudioRouting()
        sendStatus("audio_mode_${mode.protocolValue}")
    }

    private fun updateAudioRouting() {
        val shouldSendToTv = audioMode != AudioMode.Host &&
            synchronized(peerLock) { audioDataChannel?.state() == DataChannel.State.OPEN }
        NativeLibrary.setTvAudioTapEnabled(shouldSendToTv)
        NativeLibrary.setTvAudioLocalMute(audioMode == AudioMode.Tv && shouldSendToTv)
    }

    private fun onNativeAudioFrame(
        samples: ShortArray,
        sampleRate: Int,
        channels: Int,
        frames: Int,
    ) {
        if (sampleRate != NATIVE_SAMPLE_RATE || channels <= 0 || audioMode == AudioMode.Host) {
            return
        }
        if (synchronized(peerLock) { audioDataChannel?.state() != DataChannel.State.OPEN }) {
            return
        }
        if (pendingAudioTasks.incrementAndGet() > MAX_PENDING_AUDIO_TASKS) {
            pendingAudioTasks.decrementAndGet()
            return
        }
        audioExecutor.execute {
            try {
                audioResampler.push(samples, frames, channels) { pcmChunk ->
                    val channel = synchronized(peerLock) { audioDataChannel } ?: return@push
                    if (channel.state() == DataChannel.State.OPEN) {
                        channel.send(DataChannel.Buffer(ByteBuffer.wrap(pcmChunk), true))
                    }
                }
            } finally {
                pendingAudioTasks.decrementAndGet()
            }
        }
    }

    private fun sendStatus(state: String) {
        sendJson(
            JSONObject()
                .put("type", "status")
                .put("state", state)
                .put("pin", pin)
                .put("videoWidth", TOP_SCREEN_WIDTH)
                .put("videoHeight", TOP_SCREEN_HEIGHT)
                .put("audioMode", audioMode.protocolValue)
                .put("audioSampleRate", WEB_AUDIO_SAMPLE_RATE)
        )
    }

    private fun sendSignalError(message: String) {
        Log.error("[TvMainScreenCastHost] $message")
        sendJson(JSONObject().put("type", "error").put("message", message))
    }

    private fun sendJson(json: JSONObject) {
        try {
            synchronized(peerLock) {
                webSocket?.sendTextFrame(json.toString())
            }
        } catch (e: Exception) {
            if (running.get()) {
                Log.error("[TvMainScreenCastHost] Signaling send failed: ${e.message}")
            }
        }
    }

    private fun setStatus(stringResId: Int) {
        mainHandler.post {
            statusText?.setText(stringResId)
        }
    }

    private fun closePeerConnectionLocked() {
        audioDataChannel?.unregisterObserver()
        audioDataChannel?.dispose()
        audioDataChannel = null
        peerConnection?.close()
        peerConnection?.dispose()
        peerConnection = null
        updateAudioRouting()
    }

    private fun stopWebRtcCapture() {
        synchronized(peerLock) {
            closePeerConnectionLocked()
        }
        try {
            videoSource?.capturerObserver?.onCapturerStopped()
        } catch (_: Exception) {
        }
        try {
            surfaceTextureHelper?.stopListening()
        } catch (_: Exception) {
        }
        captureSurface?.release()
        captureSurface = null
        try {
            surfaceTextureHelper?.dispose()
        } catch (_: Exception) {
        }
        surfaceTextureHelper = null
        videoTrack?.dispose()
        videoTrack = null
        videoSource?.dispose()
        videoSource = null
        peerConnectionFactory?.dispose()
        peerConnectionFactory = null
        eglBase?.release()
        eglBase = null
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) {
            return
        }
        try {
            NativeLibrary.secondarySurfaceDestroyed()
        } catch (_: Exception) {
        }
        NativeLibrary.setTvAudioFrameCallback(null)
        NativeLibrary.setTvAudioTapEnabled(false)
        NativeLibrary.setTvAudioLocalMute(false)
        webSocket?.closeQuietly()
        serverSocket.closeQuietly()
        stopWebRtcCapture()
        executor.shutdownNow()
        audioExecutor.shutdownNow()
        mainHandler.post {
            pairingDialog?.dismiss()
            pairingDialog = null
            statusText = null
            restoreLayout()
            restoreSecondaryDisplay()
            Toast.makeText(activity, R.string.tv_main_screen_cast_stopped, Toast.LENGTH_SHORT).show()
        }
    }

    private fun findLocalIpv4Address(): String {
        NetworkInterface.getNetworkInterfaces().toList().forEach { networkInterface ->
            if (!networkInterface.isUp || networkInterface.isLoopback) return@forEach
            networkInterface.inetAddresses.toList().forEach { address ->
                if (address is Inet4Address && !address.isLoopbackAddress) {
                    return address.hostAddress ?: "127.0.0.1"
                }
            }
        }
        return "127.0.0.1"
    }

    private fun readHttpRequest(input: BufferedInputStream): HttpRequest? {
        val bytes = ArrayList<Byte>(2048)
        var previous = 0
        var matched = 0
        while (bytes.size < MAX_HTTP_HEADER_BYTES) {
            val value = input.read()
            if (value < 0) return null
            bytes.add(value.toByte())
            matched = if (value == HTTP_HEADER_END[matched].code) matched + 1 else if (value == HTTP_HEADER_END[0].code) 1 else 0
            previous = value
            if (matched == HTTP_HEADER_END.length) break
        }
        if (previous < 0 || matched != HTTP_HEADER_END.length) return null
        val headerText = bytes.toByteArray().toString(StandardCharsets.ISO_8859_1)
        val lines = headerText.split("\r\n")
        val requestLine = lines.firstOrNull()?.split(" ") ?: return null
        if (requestLine.size < 2) return null
        val target = requestLine[1]
        val headers = mutableMapOf<String, String>()
        lines.drop(1).forEach { line ->
            val separator = line.indexOf(':')
            if (separator > 0) {
                headers[line.substring(0, separator).trim().lowercase(Locale.US)] =
                    line.substring(separator + 1).trim()
            }
        }
        return HttpRequest(
            path = target.substringBefore('?'),
            query = parseQuery(target.substringAfter('?', "")),
            headers = headers
        )
    }

    private fun serveTvPage(output: BufferedOutputStream) {
        val bytes = tvReceiverHtml().toByteArray(StandardCharsets.UTF_8)
        writeHttpResponse(output, "200 OK", "text/html; charset=utf-8", bytes)
    }

    private fun serveNoContent(output: BufferedOutputStream) {
        output.write("HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
        output.flush()
    }

    private fun serveNotFound(output: BufferedOutputStream) {
        writeHttpResponse(output, "404 Not Found", "text/plain; charset=utf-8", "Not found".toByteArray())
    }

    private fun serveForbidden(output: BufferedOutputStream) {
        writeHttpResponse(output, "403 Forbidden", "text/plain; charset=utf-8", "Invalid PIN".toByteArray())
    }

    private fun serveBadRequest(output: BufferedOutputStream) {
        writeHttpResponse(output, "400 Bad Request", "text/plain; charset=utf-8", "Bad request".toByteArray())
    }

    private fun writeHttpResponse(
        output: BufferedOutputStream,
        status: String,
        contentType: String,
        body: ByteArray,
    ) {
        output.write(
            (
                "HTTP/1.1 $status\r\n" +
                    "Content-Type: $contentType\r\n" +
                    "Content-Length: ${body.size}\r\n" +
                    "Cache-Control: no-store\r\n" +
                    "Connection: close\r\n" +
                    "\r\n"
                ).toByteArray(StandardCharsets.US_ASCII)
        )
        output.write(body)
        output.flush()
    }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        return query.split('&').mapNotNull { part ->
            val key = part.substringBefore('=')
            if (key.isBlank()) return@mapNotNull null
            val value = part.substringAfter('=', "")
            urlDecode(key) to urlDecode(value)
        }.toMap()
    }

    private fun urlDecode(value: String): String =
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())

    private fun websocketAcceptKey(key: String): String {
        val digest = MessageDigest.getInstance("SHA-1")
            .digest((key.trim() + WEBSOCKET_GUID).toByteArray(StandardCharsets.US_ASCII))
        return Base64.encodeToString(digest, Base64.NO_WRAP)
    }

    private fun preferH264Sdp(sdp: String): String {
        val lines = sdp.split("\r\n")
        val h264Payloads = lines.mapNotNull { line ->
            val match = RTPMAP_H264_REGEX.matchEntire(line)
            match?.groupValues?.getOrNull(1)
        }.toSet()
        if (h264Payloads.isEmpty()) return sdp
        return lines.joinToString("\r\n") { line ->
            if (line.startsWith("m=video ")) {
                val parts = line.split(" ")
                if (parts.size > 3) {
                    (parts.take(3) + parts.drop(3).filter { it in h264Payloads }).joinToString(" ")
                } else {
                    line
                }
            } else {
                line
            }
        }
    }

    private fun tvReceiverHtml(): String = """
<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
  <title>Azahar TV Main Screen</title>
  <style>
    :root {
      color-scheme: dark;
      font-family: system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
      background: #000;
      color: #f5f7fb;
    }
    * { box-sizing: border-box; }
    html, body {
      width: 100%;
      height: 100%;
      margin: 0;
      overflow: hidden;
      background: #000;
    }
    body {
      display: grid;
      place-items: center;
    }
    video {
      width: 100vw;
      height: 100vh;
      object-fit: contain;
      background: #000;
    }
    .panel {
      position: fixed;
      inset: 0;
      display: grid;
      place-items: center;
      padding: 32px;
      background: radial-gradient(circle at center, rgba(28, 35, 48, 0.92), rgba(0, 0, 0, 0.96) 64%);
      transition: opacity 180ms ease;
    }
    .panel.hidden {
      opacity: 0;
      pointer-events: none;
    }
    .box {
      width: min(560px, 100%);
      border: 1px solid rgba(255, 255, 255, 0.16);
      background: rgba(9, 12, 18, 0.84);
      padding: 28px;
      border-radius: 8px;
    }
    h1 {
      margin: 0 0 16px;
      font-size: 28px;
      font-weight: 650;
      line-height: 1.15;
    }
    label {
      display: block;
      margin: 0 0 8px;
      color: #b8c2d4;
      font-size: 15px;
    }
    .row {
      display: flex;
      gap: 12px;
    }
    input {
      min-width: 0;
      flex: 1;
      height: 48px;
      border-radius: 6px;
      border: 1px solid rgba(255, 255, 255, 0.22);
      background: rgba(255, 255, 255, 0.08);
      color: #fff;
      font-size: 24px;
      text-align: center;
      letter-spacing: 4px;
    }
    button {
      height: 48px;
      padding: 0 20px;
      border: 0;
      border-radius: 6px;
      background: #4f8cff;
      color: #fff;
      font-size: 16px;
      font-weight: 650;
    }
    .status {
      min-height: 22px;
      margin-top: 16px;
      color: #b8c2d4;
      font-size: 15px;
    }
    .status.error {
      color: #ff8c8c;
    }
  </style>
</head>
<body>
  <video id="video" autoplay playsinline></video>
  <div id="panel" class="panel">
    <main class="box">
      <h1>Azahar TV Main Screen</h1>
      <label for="pin">PIN shown on the Android host</label>
      <div class="row">
        <input id="pin" inputmode="numeric" autocomplete="one-time-code" maxlength="6" autofocus>
        <button id="connect">Connect</button>
      </div>
      <div id="status" class="status">Waiting for PIN</div>
    </main>
  </div>
  <script>
    const video = document.getElementById('video');
    const panel = document.getElementById('panel');
    const pinInput = document.getElementById('pin');
    const connectButton = document.getElementById('connect');
    const statusText = document.getElementById('status');

    let socket = null;
    let peer = null;
    let audioPlayer = null;

    function setStatus(message, isError) {
      statusText.textContent = message;
      statusText.classList.toggle('error', Boolean(isError));
    }

    function h264Codecs() {
      const receiverCaps = window.RTCRtpReceiver && RTCRtpReceiver.getCapabilities
        ? RTCRtpReceiver.getCapabilities('video') : null;
      const senderCaps = window.RTCRtpSender && RTCRtpSender.getCapabilities
        ? RTCRtpSender.getCapabilities('video') : null;
      const codecs = (receiverCaps && receiverCaps.codecs) || (senderCaps && senderCaps.codecs) || [];
      return codecs.filter((codec) => String(codec.mimeType).toLowerCase() === 'video/h264');
    }

    async function connect() {
      const pin = pinInput.value.trim();
      if (!/^\d{6}$/.test(pin)) {
        setStatus('Enter the 6 digit PIN', true);
        return;
      }
      if (!('RTCPeerConnection' in window)) {
        setStatus('This TV browser does not support WebRTC.', true);
        return;
      }
      const h264 = h264Codecs();
      if (!h264.length) {
        setStatus('This TV browser does not expose H.264 for WebRTC.', true);
        return;
      }

      cleanup();
      setStatus('Connecting...');
      try {
        audioPlayer = new PcmAudioPlayer();
        await audioPlayer.start();
      } catch (error) {
        console.warn('Audio output unavailable', error);
        audioPlayer = null;
      }

      peer = new RTCPeerConnection({ iceServers: [] });
      peer.onicecandidate = (event) => {
        if (event.candidate && socket && socket.readyState === WebSocket.OPEN) {
          socket.send(JSON.stringify({
            type: 'ice',
            sdpMid: event.candidate.sdpMid,
            sdpMLineIndex: event.candidate.sdpMLineIndex,
            candidate: event.candidate.candidate
          }));
        }
      };
      peer.ontrack = (event) => {
        video.srcObject = event.streams && event.streams[0]
          ? event.streams[0]
          : new MediaStream([event.track]);
        panel.classList.add('hidden');
        video.play().catch(() => {});
        if (document.body.requestFullscreen) {
          document.body.requestFullscreen().catch(() => {});
        }
      };
      peer.onconnectionstatechange = () => {
        if (peer.connectionState === 'failed' || peer.connectionState === 'disconnected') {
          setStatus('Connection lost', true);
          panel.classList.remove('hidden');
        }
      };

      const transceiver = peer.addTransceiver('video', { direction: 'recvonly' });
      if (transceiver.setCodecPreferences) {
        transceiver.setCodecPreferences(h264);
      }

      const audioChannel = peer.createDataChannel('audio', {
        ordered: false,
        maxRetransmits: 0
      });
      audioChannel.binaryType = 'arraybuffer';
      audioChannel.onopen = () => setStatus('Connected');
      audioChannel.onmessage = (event) => {
        if (audioPlayer) audioPlayer.enqueue(event.data);
      };

      const wsUrl = (location.protocol === 'https:' ? 'wss://' : 'ws://') +
        location.host + '/signal?pin=' + encodeURIComponent(pin);
      socket = new WebSocket(wsUrl);
      socket.onopen = async () => {
        socket.send(JSON.stringify({ type: 'hello' }));
        const offer = await peer.createOffer();
        if (!/H264\/90000/i.test(offer.sdp)) {
          setStatus('The generated offer has no H.264 video.', true);
          cleanup();
          return;
        }
        await peer.setLocalDescription(offer);
        socket.send(JSON.stringify({ type: 'offer', sdp: peer.localDescription.sdp }));
      };
      socket.onmessage = async (event) => {
        const message = JSON.parse(event.data);
        if (message.type === 'answer') {
          await peer.setRemoteDescription({ type: 'answer', sdp: message.sdp });
        } else if (message.type === 'ice' && message.candidate) {
          await peer.addIceCandidate({
            sdpMid: message.sdpMid,
            sdpMLineIndex: message.sdpMLineIndex,
            candidate: message.candidate
          });
        } else if (message.type === 'error') {
          setStatus(message.message || 'Host error', true);
          panel.classList.remove('hidden');
        } else if (message.type === 'stop') {
          setStatus('Cast stopped');
          panel.classList.remove('hidden');
          cleanup();
        }
      };
      socket.onerror = () => setStatus('Could not connect to the Android host.', true);
      socket.onclose = () => {
        if (peer) {
          setStatus('Disconnected');
          panel.classList.remove('hidden');
        }
      };
    }

    function cleanup() {
      if (socket) {
        try { socket.close(); } catch (e) {}
        socket = null;
      }
      if (peer) {
        try { peer.close(); } catch (e) {}
        peer = null;
      }
      if (audioPlayer) {
        audioPlayer.stop();
        audioPlayer = null;
      }
    }

    class PcmAudioPlayer {
      constructor() {
        this.context = null;
        this.node = null;
        this.queue = [];
        this.current = null;
        this.offset = 0;
        this.started = false;
      }

      async start() {
        const AudioContextClass = window.AudioContext || window.webkitAudioContext;
        if (!AudioContextClass) return;
        this.context = new AudioContextClass({ sampleRate: 48000, latencyHint: 'interactive' });
        await this.context.resume();
        if (this.context.audioWorklet) {
          try {
            const processor = `
              class Pcm16Player extends AudioWorkletProcessor {
                constructor() {
                  super();
                  this.queue = [];
                  this.current = null;
                  this.offset = 0;
                  this.started = false;
                  this.port.onmessage = (event) => {
                    this.queue.push(new Int16Array(event.data));
                    while (this.queue.length > 12) this.queue.shift();
                  };
                }
                process(inputs, outputs) {
                  const left = outputs[0][0];
                  const right = outputs[0][1] || left;
                  if (!this.started) {
                    const frames = this.queue.reduce((sum, chunk) => sum + chunk.length / 2, 0);
                    if (frames < 2400) {
                      left.fill(0);
                      right.fill(0);
                      return true;
                    }
                    this.started = true;
                  }
                  for (let i = 0; i < left.length; i++) {
                    if (!this.current || this.offset >= this.current.length) {
                      this.current = this.queue.shift();
                      this.offset = 0;
                    }
                    if (!this.current) {
                      left[i] = 0;
                      right[i] = 0;
                      this.started = false;
                    } else {
                      left[i] = this.current[this.offset++] / 32768;
                      right[i] = this.current[this.offset++] / 32768;
                    }
                  }
                  return true;
                }
              }
              registerProcessor('pcm16-player', Pcm16Player);
            `;
            const url = URL.createObjectURL(new Blob([processor], { type: 'application/javascript' }));
            await this.context.audioWorklet.addModule(url);
            URL.revokeObjectURL(url);
            this.node = new AudioWorkletNode(this.context, 'pcm16-player', { outputChannelCount: [2] });
            this.node.connect(this.context.destination);
            return;
          } catch (error) {
            console.warn('AudioWorklet unavailable, using fallback', error);
          }
        }
        if (this.context.createScriptProcessor) {
          this.node = this.context.createScriptProcessor(1024, 0, 2);
          this.node.onaudioprocess = (event) => this.fillScriptProcessor(event);
          this.node.connect(this.context.destination);
        }
      }

      enqueue(buffer) {
        if (!buffer) return;
        if (this.node && this.node.port) {
          this.node.port.postMessage(buffer, [buffer]);
        } else {
          this.queue.push(new Int16Array(buffer));
          while (this.queue.length > 12) this.queue.shift();
        }
      }

      fillScriptProcessor(event) {
        const left = event.outputBuffer.getChannelData(0);
        const right = event.outputBuffer.numberOfChannels > 1
          ? event.outputBuffer.getChannelData(1)
          : left;
        if (!this.started) {
          const frames = this.queue.reduce((sum, chunk) => sum + chunk.length / 2, 0);
          if (frames < 2400) {
            left.fill(0);
            right.fill(0);
            return;
          }
          this.started = true;
        }
        for (let i = 0; i < left.length; i++) {
          if (!this.current || this.offset >= this.current.length) {
            this.current = this.queue.shift();
            this.offset = 0;
          }
          if (!this.current) {
            left[i] = 0;
            right[i] = 0;
            this.started = false;
          } else {
            left[i] = this.current[this.offset++] / 32768;
            right[i] = this.current[this.offset++] / 32768;
          }
        }
      }

      stop() {
        if (this.node) {
          try { this.node.disconnect(); } catch (e) {}
          this.node = null;
        }
        if (this.context) {
          this.context.close().catch(() => {});
          this.context = null;
        }
      }
    }

    connectButton.addEventListener('click', connect);
    pinInput.addEventListener('keydown', (event) => {
      if (event.key === 'Enter') connect();
    });
  </script>
</body>
</html>
""".trimIndent()

    private fun Closeable?.closeQuietly() {
        try {
            this?.close()
        } catch (_: Exception) {
        }
    }

    private fun Socket?.closeQuietly() {
        try {
            this?.close()
        } catch (_: Exception) {
        }
    }

    private enum class AudioMode(val protocolValue: String, val labelResId: Int) {
        Host("host", R.string.tv_main_screen_cast_audio_host),
        Tv("tv", R.string.tv_main_screen_cast_audio_tv),
        Both("both", R.string.tv_main_screen_cast_audio_both),
    }

    private data class HttpRequest(
        val path: String,
        val query: Map<String, String>,
        val headers: Map<String, String>,
    )

    private class WebSocketConnection(
        private val socket: Socket,
        private val input: BufferedInputStream,
        private val output: BufferedOutputStream,
    ) : Closeable {
        @Synchronized
        fun sendTextFrame(text: String) {
            val payload = text.toByteArray(StandardCharsets.UTF_8)
            output.write(0x81)
            when {
                payload.size < 126 -> output.write(payload.size)
                payload.size <= 0xFFFF -> {
                    output.write(126)
                    output.write((payload.size ushr 8) and 0xFF)
                    output.write(payload.size and 0xFF)
                }
                else -> {
                    output.write(127)
                    val length = payload.size.toLong()
                    for (shift in 56 downTo 0 step 8) {
                        output.write(((length ushr shift) and 0xFF).toInt())
                    }
                }
            }
            output.write(payload)
            output.flush()
        }

        fun readTextFrame(): String? {
            while (true) {
                val first = input.read()
                if (first < 0) return null
                val second = input.read()
                if (second < 0) return null
                val opcode = first and 0x0F
                val masked = second and 0x80 != 0
                var length = (second and 0x7F).toLong()
                if (length == 126L) {
                    length = ((input.read() and 0xFF) shl 8 or (input.read() and 0xFF)).toLong()
                } else if (length == 127L) {
                    length = 0
                    repeat(8) {
                        length = (length shl 8) or (input.read() and 0xFF).toLong()
                    }
                }
                if (length > MAX_WEBSOCKET_PAYLOAD_BYTES) {
                    return null
                }
                val mask = if (masked) ByteArray(4).also { readFully(input, it) } else null
                val payload = ByteArray(length.toInt())
                readFully(input, payload)
                if (mask != null) {
                    payload.indices.forEach { i ->
                        payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
                    }
                }
                when (opcode) {
                    0x1 -> return payload.toString(StandardCharsets.UTF_8)
                    0x8 -> return null
                    0x9 -> sendPong(payload)
                    0xA -> Unit
                    else -> return null
                }
            }
        }

        @Synchronized
        private fun sendPong(payload: ByteArray) {
            output.write(0x8A)
            output.write(payload.size)
            output.write(payload)
            output.flush()
        }

        override fun close() {
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }

        companion object {
            private fun readFully(input: BufferedInputStream, buffer: ByteArray) {
                var offset = 0
                while (offset < buffer.size) {
                    val read = input.read(buffer, offset, buffer.size - offset)
                    if (read < 0) throw IllegalStateException("Unexpected EOF")
                    offset += read
                }
            }
        }
    }

    private class H264OnlyVideoEncoderFactory(
        private val delegate: VideoEncoderFactory,
    ) : VideoEncoderFactory {
        override fun createEncoder(info: VideoCodecInfo): VideoEncoder? {
            return if (info.name.equals("H264", ignoreCase = true)) {
                delegate.createEncoder(info)
            } else {
                null
            }
        }

        override fun getSupportedCodecs(): Array<VideoCodecInfo> {
            return delegate.supportedCodecs
                .filter { it.name.equals("H264", ignoreCase = true) }
                .toTypedArray()
        }
    }

    private class SimpleSdpObserver(
        private val onCreateSuccess: (SessionDescription) -> Unit = {},
        private val onSetSuccess: () -> Unit = {},
        private val onFailure: (String) -> Unit = {},
    ) : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) = onCreateSuccess.invoke(description)
        override fun onSetSuccess() = onSetSuccess.invoke()
        override fun onCreateFailure(error: String) = onFailure.invoke(error)
        override fun onSetFailure(error: String) = onFailure.invoke(error)
    }

    private class Pcm16Resampler(
        private val inputRate: Int,
        private val outputRate: Int,
    ) {
        private val outputChunk = ShortArray(AUDIO_CHUNK_FRAMES * 2)
        private var outputFrames = 0
        private var hasLastFrame = false
        private var lastLeft = 0
        private var lastRight = 0
        private var sourcePosition = 0.0

        fun push(
            samples: ShortArray,
            frames: Int,
            channels: Int,
            emit: (ByteArray) -> Unit,
        ) {
            if (frames <= 0 || samples.isEmpty()) return
            val combinedFrames = frames + if (hasLastFrame) 1 else 0
            val combined = ShortArray(combinedFrames * 2)
            var writeFrame = 0
            if (hasLastFrame) {
                combined[0] = lastLeft.toShort()
                combined[1] = lastRight.toShort()
                writeFrame = 1
            }
            for (frame in 0 until frames) {
                val source = frame * channels
                val left = samples.getOrElse(source) { 0 }
                val right = samples.getOrElse(source + 1) { left }
                val destination = (writeFrame + frame) * 2
                combined[destination] = left
                combined[destination + 1] = right
            }
            if (!hasLastFrame) {
                hasLastFrame = true
            }
            while (sourcePosition + 1.0 < combinedFrames) {
                val baseFrame = sourcePosition.toInt()
                val fraction = sourcePosition - baseFrame
                val base = baseFrame * 2
                val next = base + 2
                val left = lerp(combined[base], combined[next], fraction)
                val right = lerp(combined[base + 1], combined[next + 1], fraction)
                writeOutput(left, right, emit)
                sourcePosition += inputRate.toDouble() / outputRate.toDouble()
            }
            val last = (combinedFrames - 1) * 2
            lastLeft = combined[last].toInt()
            lastRight = combined[last + 1].toInt()
            sourcePosition -= (combinedFrames - 1).toDouble()
        }

        private fun writeOutput(left: Short, right: Short, emit: (ByteArray) -> Unit) {
            val offset = outputFrames * 2
            outputChunk[offset] = left
            outputChunk[offset + 1] = right
            outputFrames++
            if (outputFrames == AUDIO_CHUNK_FRAMES) {
                val bytes = ByteArray(AUDIO_CHUNK_FRAMES * 2 * java.lang.Short.BYTES)
                val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                outputChunk.forEach { sample -> buffer.putShort(sample) }
                outputFrames = 0
                emit(bytes)
            }
        }

        private fun lerp(a: Short, b: Short, fraction: Double): Short {
            val mixed = a.toDouble() + (b.toDouble() - a.toDouble()) * fraction
            return mixed.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    companion object {
        private val peerConnectionFactoryInitialized = AtomicBoolean(false)
        private val RTPMAP_H264_REGEX = Regex("""a=rtpmap:(\d+)\s+H264/90000""", RegexOption.IGNORE_CASE)
        private const val VIDEO_TRACK_ID = "AZAHAR_TOP_SCREEN"
        private const val AUDIO_DATA_CHANNEL_LABEL = "audio"
        private const val TOP_SCREEN_WIDTH = 800
        private const val TOP_SCREEN_HEIGHT = 480
        private const val NATIVE_SAMPLE_RATE = 32728
        private const val WEB_AUDIO_SAMPLE_RATE = 48000
        private const val AUDIO_CHUNK_FRAMES = 480
        private const val MAX_PENDING_AUDIO_TASKS = 4
        private const val AUDIO_MODE_RADIO_BASE_ID = 40_000
        private const val HTTP_SOCKET_TIMEOUT_MS = 10_000
        private const val MAX_HTTP_HEADER_BYTES = 16 * 1024
        private const val MAX_WEBSOCKET_PAYLOAD_BYTES = 2 * 1024 * 1024
        private const val HTTP_HEADER_END = "\r\n\r\n"
        private const val WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

        private fun ensurePeerConnectionFactoryInitialized(context: android.content.Context) {
            if (peerConnectionFactoryInitialized.compareAndSet(false, true)) {
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(context)
                        .setFieldTrials("WebRTC-H264HighProfile/Disabled/")
                        .createInitializationOptions()
                )
            }
        }
    }
}
