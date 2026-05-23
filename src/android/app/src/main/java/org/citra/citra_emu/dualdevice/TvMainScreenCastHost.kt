// Copyright Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

package org.citra.citra_emu.dualdevice

import android.app.Dialog
import androidx.appcompat.app.AlertDialog
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.Surface
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.roundToInt
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
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.AzaharHardwareVideoEncoderFactory
import org.webrtc.DataChannel
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.Predicate
import org.webrtc.RtpParameters
import org.webrtc.RtpSender
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCodecInfo
import org.webrtc.VideoDecoder
import org.webrtc.VideoDecoderFactory
import org.webrtc.VideoEncoder
import org.webrtc.VideoEncoderFactory
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.AudioDeviceModule
import org.webrtc.audio.JavaAudioDeviceModule

class TvMainScreenCastHost(
    private val activity: EmulationActivity,
    private val settings: Settings,
    private val releaseSecondaryDisplay: () -> Unit,
    private val restoreSecondaryDisplay: () -> Unit,
    private val protectNativeSurface: Boolean = false,
    private val onStopped: () -> Unit = {},
) : Closeable {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor: ExecutorService = Executors.newCachedThreadPool()
    private val signalingExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val audioExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val cleanupExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)
    private val peerLock = Any()
    private val pendingAudioTasks = AtomicInteger(0)
    private val capturedVideoFrames = AtomicInteger(0)
    private val audioResampler = Pcm16Resampler(NATIVE_SAMPLE_RATE, WEB_AUDIO_SAMPLE_RATE)
    private val opusAudioQueueLock = Object()
    private val opusAudioQueue = ArrayDeque<ByteArray>()
    private val preferences = activity.getSharedPreferences(
        TV_CAST_PREFS_NAME,
        android.content.Context.MODE_PRIVATE
    )

    private var serverSocket: ServerSocket? = null
    private var webSocket: WebSocketConnection? = null
    private var pairingDialog: Dialog? = null
    private var statusText: TextView? = null
    private var startGameButton: TextView? = null
    private var onStartGame: (() -> Unit)? = null
    private var onCancelBeforeGame: (() -> Unit)? = null
    private val gameStartRequested = AtomicBoolean(false)
    private var audioMode = loadAudioMode()
    private var audioCodec = loadAudioCodec()
    private var videoConfig = loadVideoConfig()

    private var eglBase: EglBase? = null
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var audioDeviceModule: AudioDeviceModule? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var captureSurface: Surface? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var peerConnection: PeerConnection? = null
    private var videoSender: RtpSender? = null
    private var audioSender: RtpSender? = null
    private var audioDataChannel: DataChannel? = null
    private var opusCurrentChunk: ByteArray? = null
    private var opusCurrentOffset = 0

    private val previousScreenLayout = IntSetting.SCREEN_LAYOUT.int
    private val previousSecondaryLayout = IntSetting.SECONDARY_DISPLAY_LAYOUT.int
    private val previousAspectRatio = IntSetting.ASPECT_RATIO.int
    private val previousSwapScreen = BooleanSetting.SWAP_SCREEN.boolean
    private val previousMenuSwapScreen = EmulationMenuSettings.swapScreens

    val isRunning: Boolean
        get() = running.get()

    fun start() {
        if (!running.compareAndSet(false, true)) {
            return
        }

        try {
            serverSocket = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(TV_CAST_HTTP_PORT))
            }
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

    fun showPairingDialog(
        onStartGame: (() -> Unit)? = null,
        onCancelBeforeGame: (() -> Unit)? = null,
    ) {
        this.onStartGame = onStartGame
        this.onCancelBeforeGame = onCancelBeforeGame
        gameStartRequested.set(false)
        startGameButton = null
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
            setText(R.string.tv_main_screen_cast_resolution)
        })
        content.addView(RadioGroup(activity).apply {
            orientation = RadioGroup.VERTICAL
            TvVideoResolution.entries.forEachIndexed { index, resolution ->
                addView(RadioButton(activity).apply {
                    id = RESOLUTION_RADIO_BASE_ID + index
                    text = activity.getString(resolution.labelResId)
                    isChecked = resolution == videoConfig.resolution
                })
            }
            setOnCheckedChangeListener { _, checkedId ->
                val index = checkedId - RESOLUTION_RADIO_BASE_ID
                if (index in TvVideoResolution.entries.indices) {
                    setVideoResolution(TvVideoResolution.entries[index])
                }
            }
        })
        content.addView(TextView(activity).apply {
            setText(R.string.tv_main_screen_cast_aspect)
        })
        content.addView(RadioGroup(activity).apply {
            orientation = RadioGroup.VERTICAL
            TvVideoAspectMode.entries.forEachIndexed { index, aspectMode ->
                addView(RadioButton(activity).apply {
                    id = ASPECT_RADIO_BASE_ID + index
                    text = activity.getString(aspectMode.labelResId)
                    isChecked = aspectMode == videoConfig.aspectMode
                })
            }
            setOnCheckedChangeListener { _, checkedId ->
                val index = checkedId - ASPECT_RADIO_BASE_ID
                if (index in TvVideoAspectMode.entries.indices) {
                    setVideoAspectMode(TvVideoAspectMode.entries[index])
                }
            }
        })
        content.addView(TextView(activity).apply {
            setText(R.string.tv_main_screen_cast_bitrate)
        })
        content.addView(RadioGroup(activity).apply {
            orientation = RadioGroup.VERTICAL
            TvVideoBitrate.entries.forEachIndexed { index, bitrate ->
                addView(RadioButton(activity).apply {
                    id = BITRATE_RADIO_BASE_ID + index
                    text = activity.getString(bitrate.labelResId)
                    isChecked = bitrate == videoConfig.bitrate
                })
            }
            setOnCheckedChangeListener { _, checkedId ->
                val index = checkedId - BITRATE_RADIO_BASE_ID
                if (index in TvVideoBitrate.entries.indices) {
                    setVideoBitrate(TvVideoBitrate.entries[index])
                }
            }
        })
        content.addView(TextView(activity).apply {
            setText(R.string.tv_main_screen_cast_video_priority)
        })
        content.addView(RadioGroup(activity).apply {
            orientation = RadioGroup.VERTICAL
            TvVideoPriority.entries.forEachIndexed { index, priority ->
                addView(RadioButton(activity).apply {
                    id = VIDEO_PRIORITY_RADIO_BASE_ID + index
                    text = activity.getString(priority.labelResId)
                    isChecked = priority == videoConfig.priority
                })
            }
            setOnCheckedChangeListener { _, checkedId ->
                val index = checkedId - VIDEO_PRIORITY_RADIO_BASE_ID
                if (index in TvVideoPriority.entries.indices) {
                    setVideoPriority(TvVideoPriority.entries[index])
                }
            }
        })
        content.addView(TextView(activity).apply {
            setText(R.string.tv_main_screen_cast_audio_mode)
        })
        content.addView(RadioGroup(activity).apply {
            orientation = RadioGroup.VERTICAL
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
        content.addView(TextView(activity).apply {
            setText(R.string.tv_main_screen_cast_audio_codec)
        })
        content.addView(RadioGroup(activity).apply {
            orientation = RadioGroup.VERTICAL
            AudioCodec.entries.forEachIndexed { index, codec ->
                addView(RadioButton(activity).apply {
                    id = AUDIO_CODEC_RADIO_BASE_ID + index
                    text = activity.getString(codec.labelResId)
                    isChecked = codec == audioCodec
                })
            }
            setOnCheckedChangeListener { _, checkedId ->
                val index = checkedId - AUDIO_CODEC_RADIO_BASE_ID
                if (index in AudioCodec.entries.indices) {
                    setAudioCodec(AudioCodec.entries[index])
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
            .setView(ScrollView(activity).apply { addView(content) })
            .apply {
                if (onStartGame != null) {
                    setPositiveButton(R.string.tv_main_screen_cast_start_game, null)
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> handlePairingCancel() }
            .setOnCancelListener { handlePairingCancel() }
            .show()

        if (onStartGame != null) {
            (pairingDialog as? AlertDialog)
                ?.getButton(AlertDialog.BUTTON_POSITIVE)
                ?.also { startGameButton = it }
                ?.setOnClickListener {
                    if (!gameStartRequested.compareAndSet(false, true)) {
                        return@setOnClickListener
                    }
                    it.isEnabled = false
                    (it as? TextView)?.setText(R.string.tv_main_screen_cast_game_started)
                    setStatus(R.string.tv_main_screen_cast_waiting)
                    onStartGame()
                    dismissPairingDialog()
                }
        }
    }

    private fun pairingUrl(): String = "http://${findLocalIpv4Address()}:${serverSocket?.localPort ?: 0}/tv"

    private fun loadVideoConfig(): TvVideoConfig = TvVideoConfig(
        resolution = TvVideoResolution.fromProtocolValue(
            preferences.getString(PREF_VIDEO_RESOLUTION, null)
        ) ?: TvVideoResolution.TwoX,
        aspectMode = TvVideoAspectMode.fromProtocolValue(
            preferences.getString(PREF_VIDEO_ASPECT, null)
        ) ?: TvVideoAspectMode.Native,
        bitrate = TvVideoBitrate.fromProtocolValue(
            preferences.getString(PREF_VIDEO_BITRATE, null)
        ) ?: TvVideoBitrate.Medium,
        priority = TvVideoPriority.fromProtocolValue(
            preferences.getString(PREF_VIDEO_PRIORITY, null)
        ) ?: TvVideoPriority.LowLatency
    )

    private fun loadAudioMode(): AudioMode =
        AudioMode.fromProtocolValue(preferences.getString(PREF_AUDIO_MODE, null)) ?: AudioMode.Host

    private fun loadAudioCodec(): AudioCodec =
        AudioCodec.fromProtocolValue(preferences.getString(PREF_AUDIO_CODEC, null)) ?: AudioCodec.Pcm

    private fun saveTvCastPreferences() {
        preferences.edit()
            .putString(PREF_VIDEO_RESOLUTION, videoConfig.resolution.protocolValue)
            .putString(PREF_VIDEO_ASPECT, videoConfig.aspectMode.protocolValue)
            .putString(PREF_VIDEO_BITRATE, videoConfig.bitrate.protocolValue)
            .putString(PREF_VIDEO_PRIORITY, videoConfig.priority.protocolValue)
            .putString(PREF_AUDIO_MODE, audioMode.protocolValue)
            .putString(PREF_AUDIO_CODEC, audioCodec.protocolValue)
            .apply()
    }

    private fun applyCastLayout() {
        IntSetting.SCREEN_LAYOUT.int = ScreenLayout.SINGLE_SCREEN.int
        BooleanSetting.SWAP_SCREEN.boolean = true
        EmulationMenuSettings.swapScreens = true
        IntSetting.SECONDARY_DISPLAY_LAYOUT.int = SecondaryDisplayLayout.TOP_SCREEN.int
        IntSetting.ASPECT_RATIO.int = videoConfig.aspectMode.aspectRatioSetting
        settings.saveSetting(IntSetting.SCREEN_LAYOUT, SettingsFile.FILE_NAME_CONFIG)
        settings.saveSetting(BooleanSetting.SWAP_SCREEN, SettingsFile.FILE_NAME_CONFIG)
        settings.saveSetting(IntSetting.SECONDARY_DISPLAY_LAYOUT, SettingsFile.FILE_NAME_CONFIG)
        settings.saveSetting(IntSetting.ASPECT_RATIO, SettingsFile.FILE_NAME_CONFIG)
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
        IntSetting.ASPECT_RATIO.int = previousAspectRatio
        settings.saveSetting(IntSetting.SCREEN_LAYOUT, SettingsFile.FILE_NAME_CONFIG)
        settings.saveSetting(BooleanSetting.SWAP_SCREEN, SettingsFile.FILE_NAME_CONFIG)
        settings.saveSetting(IntSetting.SECONDARY_DISPLAY_LAYOUT, SettingsFile.FILE_NAME_CONFIG)
        settings.saveSetting(IntSetting.ASPECT_RATIO, SettingsFile.FILE_NAME_CONFIG)
        NativeLibrary.reloadSettings()
        NativeLibrary.swapScreens(previousSwapScreen, activity.windowManager.defaultDisplay.rotation)
        NativeLibrary.updateFramebuffer(NativeLibrary.isPortraitMode)
    }

    private fun startWebRtcCapture() {
        ensurePeerConnectionFactoryInitialized(activity.applicationContext)
        val egl = EglBase.create(null as EglBase.Context?, EglBase.CONFIG_RECORDABLE)
        eglBase = egl
        val codecPolicy = H264HardwareCodecPolicy()
        val encoderFactory = H264OnlyVideoEncoderFactory(
            AzaharHardwareVideoEncoderFactory(
                egl.eglBaseContext,
                true,
                codecPolicy,
                TV_CAST_GOP_SECONDS,
                TV_CAST_MAX_B_FRAMES,
                TV_CAST_QP_P_MAX
            )
        )
        if (!codecPolicy.hasAllowedAvcEncoder) {
            throw IllegalStateException("No hardware H.264 encoder available for TV cast")
        }
        val audioModule = JavaAudioDeviceModule.builder(activity.applicationContext)
            .setUseLowLatency(true)
            .setUseStereoInput(true)
            .setUseStereoOutput(true)
            .setInputSampleRate(WEB_AUDIO_SAMPLE_RATE)
            .setOutputSampleRate(WEB_AUDIO_SAMPLE_RATE)
            .setAudioBufferCallback { buffer, _, channelCount, sampleRate, bytesRead, captureTimeNs ->
                fillWebRtcOpusAudioBuffer(buffer, channelCount, sampleRate, bytesRead)
                if (captureTimeNs != 0L) captureTimeNs else System.nanoTime()
            }
            .createAudioDeviceModule()
        audioModule.setAudioRecordEnabled(false)
        audioDeviceModule = audioModule
        val factory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(audioModule)
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(DisabledVideoDecoderFactory())
            .createPeerConnectionFactory()
        peerConnectionFactory = factory

        val source = factory.createVideoSource(false)
        source.setIsScreencast(true)
        source.adaptOutputFormat(videoConfig.width, videoConfig.height, CAST_FPS)
        videoSource = source
        val track = factory.createVideoTrack(VIDEO_TRACK_ID, source)
        track.setEnabled(true)
        videoTrack = track
        val createdAudioSource = factory.createAudioSource(MediaConstraints())
        audioSource = createdAudioSource
        audioTrack = factory.createAudioTrack(AUDIO_TRACK_ID, createdAudioSource).apply {
            setEnabled(false)
        }

        val textureHelper = SurfaceTextureHelper.create("AzaharTvMainScreen", egl.eglBaseContext)
            ?: throw IllegalStateException("Unable to create WebRTC texture helper")
        textureHelper.setTextureSize(videoConfig.width, videoConfig.height)
        surfaceTextureHelper = textureHelper
        source.capturerObserver.onCapturerStarted(true)
        textureHelper.startListening(VideoSink { frame ->
            forwardVideoFrame(source, frame)
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
                Log.info("[TvMainScreenCastHost] HTTP ${request.path}")
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
        Log.info("[TvMainScreenCastHost] TV signaling connected")
        val previousConnection = synchronized(peerLock) {
            val previous = webSocket
            webSocket = null
            previous
        }
        previousConnection?.closeQuietly()
        closePeerConnection()
        synchronized(peerLock) {
            webSocket = connection
        }
        setStatus(R.string.tv_main_screen_cast_connected)
        sendStatus("signal_connected")
        mainHandler.post {
            Toast.makeText(activity, R.string.tv_main_screen_cast_connected, Toast.LENGTH_SHORT).show()
        }

        try {
            while (running.get()) {
                val message = connection.readTextFrame() ?: break
                Log.debug("[TvMainScreenCastHost] TV signal message received")
                handleSignalMessage(message)
            }
        } finally {
            Log.warning("[TvMainScreenCastHost] TV signaling closed")
            val shouldClosePeer = synchronized(peerLock) {
                if (webSocket === connection) {
                    webSocket = null
                    true
                } else {
                    false
                }
            }
            if (shouldClosePeer) {
                closePeerConnection()
                if (running.get()) {
                    setStatus(R.string.tv_main_screen_cast_waiting)
                }
            }
        }
    }

    private fun ensurePeerConnection(): PeerConnection {
        synchronized(peerLock) { peerConnection }?.let { return it }
        return createPeerConnection()
    }

    private fun createPeerConnection(): PeerConnection {
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

        synchronized(peerLock) {
            peerConnection = connection
        }
        updateAudioRouting()
        return connection
    }

    private fun handleSignalMessage(message: String) {
        val json = try {
            JSONObject(message)
        } catch (_: Exception) {
            sendSignalError("Invalid signaling JSON")
            return
        }
        when (json.optString("type")) {
            "hello" -> {
                Log.info("[TvMainScreenCastHost] TV hello")
                applyClientConfig(json)
                ensurePeerConnection()
                sendStatus("ready")
            }
            "offer" -> {
                Log.info("[TvMainScreenCastHost] TV offer")
                handleOffer(json.optString("sdp"))
            }
            "ice" -> handleRemoteIce(json)
            "stop" -> {
                Log.info("[TvMainScreenCastHost] TV requested stop")
                requestStop()
            }
            else -> sendSignalError("Unsupported signaling message")
        }
    }

    private fun applyClientConfig(json: JSONObject) {
        var changed = false
        AudioMode.fromProtocolValue(json.optString("audioMode").takeIf { json.has("audioMode") })?.let {
            if (audioMode != it) {
                audioMode = it
                changed = true
            }
        }
        AudioCodec.fromProtocolValue(json.optString("audioCodec").takeIf { json.has("audioCodec") })?.let {
            if (audioCodec != it) {
                audioCodec = it
                changed = true
            }
        }
        if (changed) {
            saveTvCastPreferences()
        }
        updateAudioRouting()
    }

    private fun handleOffer(sdp: String) {
        if (!sdp.contains("H264/90000", ignoreCase = true)) {
            sendSignalError("This TV browser did not offer H.264 video.")
            return
        }
        if (shouldUseWebRtcOpusAudio() && !sdp.contains("opus/48000", ignoreCase = true)) {
            sendSignalError("This TV browser did not offer Opus audio.")
            return
        }
        val connection = ensurePeerConnection()
        val offer = SessionDescription(SessionDescription.Type.OFFER, sdp)
        connection.setRemoteDescription(
            SimpleSdpObserver(
                onSetSuccess = {
                    if (configureMediaTransceiversForAnswer(connection)) {
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
                                                Log.info("[TvMainScreenCastHost] TV answer sent")
                                                sendJson(
                                                    JSONObject()
                                                        .put("type", "answer")
                                                        .put("sdp", localAnswer.description)
                                                )
                                                updateVideoSenderParameters()
                                                sendStatus("connected")
                                                mainHandler.post {
                                                    if (onStartGame != null && !gameStartRequested.get()) {
                                                        statusText?.setText(R.string.tv_main_screen_cast_ready_to_start)
                                                        startGameButton?.isEnabled = true
                                                    } else {
                                                        dismissPairingDialog()
                                                    }
                                                }
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
                    }
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

    private fun configureMediaTransceiversForAnswer(connection: PeerConnection): Boolean {
        val track = videoTrack ?: run {
            sendSignalError("Video track unavailable on Android host.")
            return false
        }
        val videoTransceiver = connection.transceivers.firstOrNull {
            !it.isStopped && it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO
        } ?: run {
            sendSignalError("TV offer did not include a video receiver.")
            return false
        }
        videoTransceiver.setDirection(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY)
        if (!videoTransceiver.sender.setTrack(track, false)) {
            sendSignalError("Could not attach TV video track.")
            return false
        }

        var createdAudioSender: RtpSender? = null
        val audioTransceiver = connection.transceivers.firstOrNull {
            !it.isStopped && it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO
        }
        if (shouldUseWebRtcOpusAudio()) {
            val opusTrack = audioTrack ?: run {
                sendSignalError("Audio track unavailable on Android host.")
                return false
            }
            if (audioTransceiver == null) {
                sendSignalError("TV offer did not include an Opus audio receiver.")
                return false
            }
            audioTransceiver.setDirection(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY)
            if (!audioTransceiver.sender.setTrack(opusTrack, false)) {
                sendSignalError("Could not attach TV audio track.")
                return false
            }
            createdAudioSender = audioTransceiver.sender
        } else {
            audioTransceiver?.setDirection(RtpTransceiver.RtpTransceiverDirection.INACTIVE)
        }

        synchronized(peerLock) {
            videoSender = videoTransceiver.sender
            audioSender = createdAudioSender
        }
        updateAudioRouting()
        return true
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

    private fun setVideoResolution(resolution: TvVideoResolution) {
        videoConfig = videoConfig.copy(resolution = resolution)
        saveTvCastPreferences()
        applyVideoConfig()
        sendStatus("video_resolution_${resolution.protocolValue}")
    }

    fun onEmulationStarted() {
        if (protectNativeSurface) {
            Log.info("[TvMainScreenCastHost] Capture surface owned by Vulkan; refreshing framebuffer")
            try {
                NativeLibrary.updateFramebuffer(NativeLibrary.isPortraitMode)
            } catch (e: Exception) {
                Log.error("[TvMainScreenCastHost] Vulkan framebuffer refresh failed: ${e.message}")
            }
            scheduleNoVideoFrameWarning()
            return
        }
        val surface = captureSurface ?: return
        try {
            Log.info("[TvMainScreenCastHost] Reattaching capture surface after emulation start")
            NativeLibrary.secondarySurfaceChanged(surface)
            NativeLibrary.updateFramebuffer(NativeLibrary.isPortraitMode)
            scheduleNoVideoFrameWarning()
        } catch (e: Exception) {
            Log.error("[TvMainScreenCastHost] Capture surface reattach failed: ${e.message}")
        }
    }

    private fun setVideoAspectMode(aspectMode: TvVideoAspectMode) {
        videoConfig = videoConfig.copy(aspectMode = aspectMode)
        saveTvCastPreferences()
        applyVideoConfig()
        sendStatus("video_aspect_${aspectMode.protocolValue}")
    }

    private fun setVideoBitrate(bitrate: TvVideoBitrate) {
        videoConfig = videoConfig.copy(bitrate = bitrate)
        saveTvCastPreferences()
        updateVideoSenderParameters()
        sendStatus("video_bitrate_${bitrate.protocolValue}")
    }

    private fun setVideoPriority(priority: TvVideoPriority) {
        videoConfig = videoConfig.copy(priority = priority)
        saveTvCastPreferences()
        updateVideoSenderParameters()
        sendStatus("video_priority_${priority.protocolValue}")
    }

    private fun applyVideoConfig() {
        try {
            IntSetting.ASPECT_RATIO.int = videoConfig.aspectMode.aspectRatioSetting
            settings.saveSetting(IntSetting.ASPECT_RATIO, SettingsFile.FILE_NAME_CONFIG)
            NativeLibrary.reloadSettings()
            surfaceTextureHelper?.setTextureSize(videoConfig.width, videoConfig.height)
            videoSource?.adaptOutputFormat(videoConfig.width, videoConfig.height, CAST_FPS)
            if (protectNativeSurface) {
                Log.info("[TvMainScreenCastHost] Vulkan capture config changed; keeping existing surface")
            } else {
                captureSurface?.let { NativeLibrary.secondarySurfaceChanged(it) }
            }
            NativeLibrary.updateFramebuffer(NativeLibrary.isPortraitMode)
        } catch (e: Exception) {
            Log.error("[TvMainScreenCastHost] Video config update failed: ${e.message}")
        }
        updateVideoSenderParameters()
    }

    private fun forwardVideoFrame(source: VideoSource, frame: VideoFrame) {
        noteVideoFrameCaptured()
        frame.buffer.retain()
        val forwardedFrame = VideoFrame(frame.buffer, frame.rotation, System.nanoTime())
        try {
            source.capturerObserver.onFrameCaptured(forwardedFrame)
        } finally {
            forwardedFrame.release()
        }
    }

    private fun noteVideoFrameCaptured() {
        val count = capturedVideoFrames.incrementAndGet()
        if (count == 1) {
            Log.info("[TvMainScreenCastHost] First video frame captured")
            sendStatus("video_started")
        } else if (count % 120 == 0) {
            Log.info("[TvMainScreenCastHost] WebRTC video frames captured: $count")
        }
    }

    private fun scheduleNoVideoFrameWarning() {
        mainHandler.postDelayed({
            if (running.get() && capturedVideoFrames.get() == 0) {
                Log.warning("[TvMainScreenCastHost] No WebRTC video frames captured after emulation start")
                sendStatus("video_waiting_no_frames")
            }
        }, VIDEO_FRAME_WARNING_DELAY_MS)
    }

    private fun updateVideoSenderParameters() {
        val sender = synchronized(peerLock) { videoSender } ?: return
        try {
            val parameters = sender.parameters ?: return
            parameters.degradationPreference = videoConfig.priority.degradationPreference
            parameters.encodings.forEach { encoding ->
                encoding.active = true
                encoding.maxFramerate = CAST_FPS
                encoding.minBitrateBps = videoConfig.bitrate.minBitrateBps
                encoding.maxBitrateBps = videoConfig.bitrate.maxBitrateBps
            }
            sender.setParameters(parameters)
            synchronized(peerLock) {
                peerConnection?.setBitrate(
                    videoConfig.bitrate.minBitrateBps,
                    videoConfig.bitrate.startBitrateBps,
                    videoConfig.bitrate.maxBitrateBps
                )
            }
        } catch (t: Throwable) {
            Log.warning("[TvMainScreenCastHost] Video sender parameters unavailable: ${t.message}")
        }
    }

    private fun setAudioMode(mode: AudioMode) {
        audioMode = mode
        saveTvCastPreferences()
        updateAudioRouting()
        sendStatus("audio_mode_${mode.protocolValue}")
    }

    private fun setAudioCodec(codec: AudioCodec) {
        audioCodec = codec
        saveTvCastPreferences()
        clearOpusAudioQueue()
        updateAudioRouting()
        sendStatus("audio_codec_${codec.protocolValue}")
    }

    private fun updateAudioRouting() {
        val shouldSendPcmToTv = audioMode != AudioMode.Host &&
            audioCodec == AudioCodec.Pcm &&
            synchronized(peerLock) { audioDataChannel?.state() == DataChannel.State.OPEN }
        val shouldSendOpusToTv = shouldUseWebRtcOpusAudio() &&
            synchronized(peerLock) { peerConnection != null && audioSender != null }
        val shouldSendToTv = shouldSendPcmToTv || shouldSendOpusToTv
        NativeLibrary.setTvAudioTapEnabled(shouldSendToTv)
        NativeLibrary.setTvAudioLocalMute(audioMode == AudioMode.Tv && shouldSendToTv)
        audioTrack?.setEnabled(shouldSendOpusToTv)
        (audioDeviceModule as? JavaAudioDeviceModule)?.let { module ->
            if (shouldSendOpusToTv) {
                module.requestStartRecording()
            } else {
                module.requestStopRecording()
            }
        }
        if (!shouldSendOpusToTv) {
            clearOpusAudioQueue()
        }
    }

    private fun shouldUseWebRtcOpusAudio(): Boolean =
        audioMode != AudioMode.Host && audioCodec == AudioCodec.Opus

    private fun onNativeAudioFrame(
        samples: ShortArray,
        sampleRate: Int,
        channels: Int,
        frames: Int,
    ) {
        if (sampleRate != NATIVE_SAMPLE_RATE || channels <= 0 || audioMode == AudioMode.Host) {
            return
        }
        val targetCodec = audioCodec
        val sendPcm = targetCodec == AudioCodec.Pcm &&
            synchronized(peerLock) { audioDataChannel?.state() == DataChannel.State.OPEN }
        val queueOpus = targetCodec == AudioCodec.Opus &&
            synchronized(peerLock) { peerConnection != null && audioSender != null }
        if (!sendPcm && !queueOpus) {
            return
        }
        if (pendingAudioTasks.incrementAndGet() > MAX_PENDING_AUDIO_TASKS) {
            pendingAudioTasks.decrementAndGet()
            return
        }
        audioExecutor.execute {
            try {
                audioResampler.push(samples, frames, channels) { pcmChunk ->
                    if (targetCodec == AudioCodec.Opus) {
                        enqueueOpusAudioChunk(pcmChunk)
                    } else {
                        val channel = synchronized(peerLock) { audioDataChannel } ?: return@push
                        if (channel.state() == DataChannel.State.OPEN) {
                            channel.send(DataChannel.Buffer(ByteBuffer.wrap(pcmChunk), true))
                        }
                    }
                }
            } finally {
                pendingAudioTasks.decrementAndGet()
            }
        }
    }

    private fun enqueueOpusAudioChunk(chunk: ByteArray) {
        synchronized(opusAudioQueueLock) {
            while (opusAudioQueue.size >= MAX_OPUS_AUDIO_CHUNKS) {
                opusAudioQueue.removeFirst()
            }
            opusAudioQueue.addLast(chunk)
        }
    }

    private fun clearOpusAudioQueue() {
        synchronized(opusAudioQueueLock) {
            opusAudioQueue.clear()
            opusCurrentChunk = null
            opusCurrentOffset = 0
        }
    }

    private fun fillWebRtcOpusAudioBuffer(
        buffer: ByteBuffer,
        channelCount: Int,
        sampleRate: Int,
        bytesRead: Int,
    ) {
        val requestedBytes = minOf(
            buffer.capacity(),
            if (bytesRead > 0) bytesRead else AUDIO_CHUNK_FRAMES * 2 * java.lang.Short.BYTES
        )
        buffer.clear()
        if (
            audioMode == AudioMode.Host ||
            audioCodec != AudioCodec.Opus ||
            channelCount != 2 ||
            sampleRate != WEB_AUDIO_SAMPLE_RATE
        ) {
            repeat(requestedBytes) { buffer.put(0) }
            buffer.rewind()
            return
        }

        synchronized(opusAudioQueueLock) {
            var written = 0
            while (written < requestedBytes) {
                val chunk = opusCurrentChunk ?: opusAudioQueue.removeFirstOrNull()?.also {
                    opusCurrentChunk = it
                    opusCurrentOffset = 0
                }
                if (chunk == null) {
                    while (written < requestedBytes) {
                        buffer.put(0)
                        written++
                    }
                    break
                }

                val copySize = minOf(requestedBytes - written, chunk.size - opusCurrentOffset)
                buffer.put(chunk, opusCurrentOffset, copySize)
                opusCurrentOffset += copySize
                written += copySize
                if (opusCurrentOffset >= chunk.size) {
                    opusCurrentChunk = null
                    opusCurrentOffset = 0
                }
            }
        }
        buffer.rewind()
    }

    private fun sendStatus(state: String) {
        sendJson(
            JSONObject()
                .put("type", "status")
                .put("state", state)
                .put("videoWidth", videoConfig.width)
                .put("videoHeight", videoConfig.height)
                .put("videoAspect", videoConfig.aspectMode.protocolValue)
                .put("videoBitrate", videoConfig.bitrate.protocolValue)
                .put("videoPriority", videoConfig.priority.protocolValue)
                .put("audioMode", audioMode.protocolValue)
                .put("audioCodec", audioCodec.protocolValue)
                .put("audioSampleRate", WEB_AUDIO_SAMPLE_RATE)
        )
    }

    private fun sendSignalError(message: String) {
        Log.error("[TvMainScreenCastHost] $message")
        sendJson(JSONObject().put("type", "error").put("message", message))
    }

    private fun sendJson(json: JSONObject) {
        if (!running.get()) {
            return
        }
        val payload = json.toString()
        try {
            signalingExecutor.execute {
                try {
                    val socket = synchronized(peerLock) { webSocket } ?: return@execute
                    socket.sendTextFrame(payload)
                } catch (e: Exception) {
                    if (running.get()) {
                        Log.error("[TvMainScreenCastHost] Signaling send failed: ${e.message}")
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            if (running.get()) {
                Log.warning("[TvMainScreenCastHost] Signaling send skipped after shutdown")
            }
        }
    }

    private fun setStatus(stringResId: Int) {
        mainHandler.post {
            statusText?.setText(stringResId)
        }
    }

    private fun dismissPairingDialog() {
        pairingDialog?.setOnCancelListener(null)
        pairingDialog?.dismiss()
        pairingDialog = null
        statusText = null
        startGameButton = null
    }

    private fun handlePairingCancel() {
        if (protectNativeSurface && NativeLibrary.isRunning()) {
            dismissPairingDialog()
            Toast.makeText(
                activity,
                R.string.tv_main_screen_cast_vulkan_stop_after_game,
                Toast.LENGTH_LONG
            ).show()
            return
        }

        requestStop()
        onCancelBeforeGame?.invoke()
    }

    private fun closePeerConnection() {
        val channel: DataChannel?
        val connection: PeerConnection?
        synchronized(peerLock) {
            channel = audioDataChannel
            connection = peerConnection
            audioDataChannel = null
            videoSender = null
            audioSender = null
            peerConnection = null
        }
        try {
            channel?.unregisterObserver()
        } catch (_: Exception) {
        }
        try {
            channel?.dispose()
        } catch (_: Exception) {
        }
        try {
            connection?.close()
        } catch (_: Exception) {
        }
        try {
            connection?.dispose()
        } catch (_: Exception) {
        }
        updateAudioRouting()
    }

    private fun stopWebRtcCapture() {
        closePeerConnection()
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
        audioTrack?.dispose()
        audioTrack = null
        audioSource?.dispose()
        audioSource = null
        peerConnectionFactory?.dispose()
        peerConnectionFactory = null
        audioDeviceModule?.release()
        audioDeviceModule = null
        eglBase?.release()
        eglBase = null
        clearOpusAudioQueue()
    }

    fun requestStop(): Boolean {
        if (protectNativeSurface && NativeLibrary.isRunning()) {
            dismissPairingDialog()
            Toast.makeText(
                activity,
                R.string.tv_main_screen_cast_vulkan_stop_after_game,
                Toast.LENGTH_LONG
            ).show()
            return false
        }

        close()
        return true
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
        val socket = synchronized(peerLock) {
            val currentSocket = webSocket
            webSocket = null
            currentSocket
        }
        socket?.closeQuietly()
        serverSocket.closeQuietly()
        cleanupExecutor.execute {
            stopWebRtcCapture()
            executor.shutdownNow()
            signalingExecutor.shutdownNow()
            audioExecutor.shutdownNow()
            cleanupExecutor.shutdown()
        }
        mainHandler.post {
            dismissPairingDialog()
            restoreLayout()
            restoreSecondaryDisplay()
            onStopped()
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
        val h264OnlyLines = lines.map { line ->
            if (line.startsWith("m=video ")) {
                val parts = line.split(" ")
                if (parts.size > 3) {
                    (parts.take(3) + sortedH264Payloads(lines, h264Payloads)).joinToString(" ")
                } else {
                    line
                }
            } else {
                line
            }
        }
        return applyVideoBandwidthLines(
            applyH264BitrateFmtp(h264OnlyLines, h264Payloads)
        ).joinToString("\r\n")
    }

    private fun sortedH264Payloads(lines: List<String>, h264Payloads: Set<String>): List<String> {
        val profileRankByPayload = lines.mapNotNull { line ->
            val match = FMTP_REGEX.matchEntire(line) ?: return@mapNotNull null
            match.groupValues[1] to h264ProfileRank(match.groupValues[2])
        }.toMap()
        return h264Payloads.sortedWith(
            compareByDescending<String> { profileRankByPayload[it] ?: 0 }.thenBy { it.toIntOrNull() ?: 0 }
        )
    }

    private fun h264ProfileRank(fmtpParameters: String): Int {
        val profileLevelId = H264_PROFILE_LEVEL_ID_REGEX.find(fmtpParameters)
            ?.groupValues
            ?.getOrNull(1)
            ?.lowercase(Locale.US)
            ?: return 0
        val profileRank = when (profileLevelId.take(2)) {
            "64", "6e", "7a", "f4" -> 3
            "4d" -> 2
            "42" -> 1
            else -> 0
        }
        val packetizationBonus = if (
            fmtpParameters.contains("packetization-mode=1", ignoreCase = true)
        ) 1 else 0
        return profileRank * 10 + packetizationBonus
    }

    private fun applyH264BitrateFmtp(lines: List<String>, h264Payloads: Set<String>): List<String> {
        val bitrateParameters = videoConfig.bitrate.fmtpParameters() ?: return lines
        val fmtpPayloads = lines.mapNotNull { line ->
            FMTP_REGEX.matchEntire(line)?.groupValues?.getOrNull(1)
        }.toSet()
        return buildList {
            lines.forEach { line ->
                val fmtpMatch = FMTP_REGEX.matchEntire(line)
                if (fmtpMatch != null && fmtpMatch.groupValues[1] in h264Payloads) {
                    add("a=fmtp:${fmtpMatch.groupValues[1]} ${mergeFmtpParameters(fmtpMatch.groupValues[2], bitrateParameters)}")
                } else {
                    add(line)
                    val rtpMapMatch = RTPMAP_H264_REGEX.matchEntire(line)
                    val payload = rtpMapMatch?.groupValues?.getOrNull(1)
                    if (payload != null && payload !in fmtpPayloads) {
                        add("a=fmtp:$payload ${bitrateParameters.joinToString(";")}")
                    }
                }
            }
        }
    }

    private fun applyVideoBandwidthLines(lines: List<String>): List<String> {
        val maxBitrate = videoConfig.bitrate.maxBitrateBps ?: return lines
        val asKbps = maxBitrate / 1000
        val output = mutableListOf<String>()
        var inVideo = false
        var bandwidthInserted = false

        fun insertBandwidth() {
            if (!bandwidthInserted) {
                output.add("b=AS:$asKbps")
                output.add("b=TIAS:$maxBitrate")
                bandwidthInserted = true
            }
        }

        lines.forEach { line ->
            if (line.startsWith("m=")) {
                if (inVideo) {
                    insertBandwidth()
                }
                inVideo = line.startsWith("m=video ")
                bandwidthInserted = false
            }
            if (inVideo && (line.startsWith("b=AS:") || line.startsWith("b=TIAS:"))) {
                return@forEach
            }
            if (inVideo && !bandwidthInserted && line.startsWith("a=")) {
                insertBandwidth()
            }
            output.add(line)
            if (inVideo && !bandwidthInserted && line.startsWith("c=")) {
                insertBandwidth()
            }
        }
        if (inVideo) {
            insertBandwidth()
        }
        return output
    }

    private fun mergeFmtpParameters(existingParameters: String, bitrateParameters: List<String>): String {
        val existing = existingParameters.split(";")
            .map(String::trim)
            .filter(String::isNotEmpty)
            .filterNot { it.startsWith("x-google-", ignoreCase = true) }
        return (existing + bitrateParameters).joinToString(";")
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
    .options {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: 12px;
      margin-top: 16px;
    }
    input, select {
      min-width: 0;
      height: 48px;
      border-radius: 6px;
      border: 1px solid rgba(255, 255, 255, 0.22);
      background: rgba(255, 255, 255, 0.08);
      color: #fff;
    }
    input {
      flex: 1;
      font-size: 24px;
      text-align: center;
      letter-spacing: 4px;
    }
    select {
      width: 100%;
      padding: 0 12px;
      font-size: 16px;
    }
    select option {
      background: #ffffff;
      color: #111827;
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
  <audio id="opusAudio" autoplay></audio>
  <div id="panel" class="panel">
    <main class="box">
      <h1>Azahar TV Main Screen</h1>
      <div class="row">
        <button id="connect" autofocus>Connect</button>
      </div>
      <div id="status" class="status">Ready to connect</div>
    </main>
  </div>
  <script>
    const video = document.getElementById('video');
    const opusAudio = document.getElementById('opusAudio');
    const panel = document.getElementById('panel');
    const connectButton = document.getElementById('connect');
    const statusText = document.getElementById('status');

    let socket = null;
    let peer = null;
    let audioPlayer = null;

    video.onplaying = () => panel.classList.add('hidden');

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
      return codecs
        .filter((codec) => String(codec.mimeType).toLowerCase() === 'video/h264')
        .sort((a, b) => h264ProfileRank(b) - h264ProfileRank(a));
    }

    function h264ProfileRank(codec) {
      const match = /profile-level-id=([0-9a-fA-F]{6})/.exec(String(codec.sdpFmtpLine || ''));
      if (!match) return 0;
      const fmtp = String(codec.sdpFmtpLine || '');
      const profile = match[1].slice(0, 2).toLowerCase();
      let rank = 0;
      if (profile === '64' || profile === '6e' || profile === '7a' || profile === 'f4') rank = 3;
      else if (profile === '4d') rank = 2;
      else if (profile === '42') rank = 1;
      return rank * 10 + (fmtp.includes('packetization-mode=1') ? 1 : 0);
    }

    function opusCodecs() {
      const receiverCaps = window.RTCRtpReceiver && RTCRtpReceiver.getCapabilities
        ? RTCRtpReceiver.getCapabilities('audio') : null;
      const codecs = receiverCaps && receiverCaps.codecs ? receiverCaps.codecs : [];
      return codecs.filter((codec) => String(codec.mimeType).toLowerCase() === 'audio/opus');
    }

    async function connect() {
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
        const stream = event.streams && event.streams[0]
          ? event.streams[0]
          : new MediaStream([event.track]);
        if (event.track.kind === 'video') {
          video.srcObject = stream;
          setStatus('Connected, waiting for video...');
          video.play().catch(() => {});
          if (document.body.requestFullscreen) {
            document.body.requestFullscreen().catch(() => {});
          }
        } else if (event.track.kind === 'audio') {
          opusAudio.srcObject = stream;
          opusAudio.play().catch(() => {});
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
      const audioTransceiver = peer.addTransceiver('audio', { direction: 'recvonly' });
      const opus = opusCodecs();
      if (audioTransceiver.setCodecPreferences && opus.length) {
        audioTransceiver.setCodecPreferences(opus);
      }

      const audioChannel = peer.createDataChannel('audio', {
        ordered: false,
        maxRetransmits: 0
      });
      audioChannel.binaryType = 'arraybuffer';
      audioChannel.onopen = () => setStatus('Control connected, waiting for video...');
      audioChannel.onmessage = (event) => {
        if (audioPlayer) audioPlayer.enqueue(event.data);
      };

      const wsUrl = (location.protocol === 'https:' ? 'wss://' : 'ws://') +
        location.host + '/signal';
      socket = new WebSocket(wsUrl);
      socket.onopen = async () => {
        socket.send(JSON.stringify({
          type: 'hello'
        }));
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
        } else if (message.type === 'status') {
          if (message.state === 'video_started') {
            setStatus('Video started');
            panel.classList.add('hidden');
          } else if (message.state === 'video_waiting_no_frames') {
            setStatus('Connected, but no video frames are arriving from Android yet.', true);
            panel.classList.remove('hidden');
          }
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
        Both("both", R.string.tv_main_screen_cast_audio_both);

        companion object {
            fun fromProtocolValue(value: String?): AudioMode? =
                entries.firstOrNull { it.protocolValue == value }
        }
    }

    private enum class AudioCodec(val protocolValue: String, val labelResId: Int) {
        Pcm("pcm", R.string.tv_main_screen_cast_audio_codec_pcm),
        Opus("opus", R.string.tv_main_screen_cast_audio_codec_opus);

        companion object {
            fun fromProtocolValue(value: String?): AudioCodec? =
                entries.firstOrNull { it.protocolValue == value }
        }
    }

    private enum class TvVideoAspectMode(
        val protocolValue: String,
        val aspectRatioSetting: Int,
        val labelResId: Int,
    ) {
        Native("native", ASPECT_RATIO_DEFAULT, R.string.tv_main_screen_cast_aspect_native),
        Widescreen("16_9", ASPECT_RATIO_16_9, R.string.tv_main_screen_cast_aspect_16_9),
        Fill("fill", ASPECT_RATIO_STRETCH, R.string.tv_main_screen_cast_aspect_fill);

        val usesWidescreenSurface: Boolean
            get() = this != Native

        companion object {
            fun fromProtocolValue(value: String?): TvVideoAspectMode? =
                entries.firstOrNull { it.protocolValue == value }
        }
    }

    private enum class TvVideoResolution(
        val protocolValue: String,
        val nativeWidth: Int,
        val height: Int,
        val labelResId: Int,
    ) {
        OneX("1x", BASE_TOP_SCREEN_WIDTH, BASE_TOP_SCREEN_HEIGHT, R.string.tv_main_screen_cast_resolution_1x),
        TwoX("2x", BASE_TOP_SCREEN_WIDTH * 2, BASE_TOP_SCREEN_HEIGHT * 2, R.string.tv_main_screen_cast_resolution_2x),
        ThreeX("3x", BASE_TOP_SCREEN_WIDTH * 3, BASE_TOP_SCREEN_HEIGHT * 3, R.string.tv_main_screen_cast_resolution_3x),
        FourX("4x", BASE_TOP_SCREEN_WIDTH * 4, BASE_TOP_SCREEN_HEIGHT * 4, R.string.tv_main_screen_cast_resolution_4x),
        FullHd("1080p", 1800, 1080, R.string.tv_main_screen_cast_resolution_1080p),
        ;

        fun widthFor(aspectMode: TvVideoAspectMode): Int =
            if (aspectMode.usesWidescreenSurface) {
                roundToEven(height * 16f / 9f)
            } else {
                nativeWidth
            }

        companion object {
            fun fromProtocolValue(value: String?): TvVideoResolution? =
                entries.firstOrNull { it.protocolValue == value }
        }
    }

    private enum class TvVideoBitrate(
        val protocolValue: String,
        val minBitrateBps: Int?,
        val startBitrateBps: Int?,
        val maxBitrateBps: Int?,
        val labelResId: Int,
    ) {
        Auto("auto", null, null, null, R.string.tv_main_screen_cast_bitrate_auto),
        Low("low", 1_500_000, 3_000_000, 4_000_000, R.string.tv_main_screen_cast_bitrate_low),
        Medium("medium", 2_000_000, 6_000_000, 8_000_000, R.string.tv_main_screen_cast_bitrate_medium),
        High("high", 5_000_000, 16_000_000, 28_000_000, R.string.tv_main_screen_cast_bitrate_high),
        VeryHigh("very_high", 10_000_000, 30_000_000, 55_000_000, R.string.tv_main_screen_cast_bitrate_very_high),
        Ultra("ultra", 18_000_000, 48_000_000, 85_000_000, R.string.tv_main_screen_cast_bitrate_ultra),
        ;

        fun fmtpParameters(): List<String>? {
            val minKbps = minBitrateBps?.div(1000) ?: return null
            val startKbps = startBitrateBps?.div(1000) ?: return null
            val maxKbps = maxBitrateBps?.div(1000) ?: return null
            return listOf(
                "x-google-min-bitrate=$minKbps",
                "x-google-start-bitrate=$startKbps",
                "x-google-max-bitrate=$maxKbps"
            )
        }

        companion object {
            fun fromProtocolValue(value: String?): TvVideoBitrate? =
                entries.firstOrNull { it.protocolValue == value }
        }
    }

    private enum class TvVideoPriority(
        val protocolValue: String,
        val degradationPreference: RtpParameters.DegradationPreference,
        val labelResId: Int,
    ) {
        LowLatency(
            "low_latency",
            RtpParameters.DegradationPreference.MAINTAIN_FRAMERATE,
            R.string.tv_main_screen_cast_video_priority_low_latency
        ),
        Quality(
            "quality",
            RtpParameters.DegradationPreference.MAINTAIN_FRAMERATE,
            R.string.tv_main_screen_cast_video_priority_quality
        );

        companion object {
            fun fromProtocolValue(value: String?): TvVideoPriority? =
                entries.firstOrNull { it.protocolValue == value }
        }
    }

    private data class TvVideoConfig(
        val resolution: TvVideoResolution,
        val aspectMode: TvVideoAspectMode,
        val bitrate: TvVideoBitrate,
        val priority: TvVideoPriority,
    ) {
        val width: Int
            get() = resolution.widthFor(aspectMode)
        val height: Int
            get() = resolution.height

        companion object {
            fun default(): TvVideoConfig = TvVideoConfig(
                resolution = TvVideoResolution.TwoX,
                aspectMode = TvVideoAspectMode.Native,
                bitrate = TvVideoBitrate.Medium,
                priority = TvVideoPriority.LowLatency
            )
        }
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

    private class H264HardwareCodecPolicy : Predicate<MediaCodecInfo> {
        val hasAllowedAvcEncoder: Boolean

        private var preferQualcomm: Boolean = false

        init {
            val hardwareAvcEncoders = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { it.isAllowedHardwareAvcEncoder(ignoreQualcommPreference = true) }
            preferQualcomm = hardwareAvcEncoders.any { it.isQualcommCodec() }
            hasAllowedAvcEncoder = hardwareAvcEncoders.any {
                it.isAllowedHardwareAvcEncoder(ignoreQualcommPreference = false)
            }
            val policy = if (preferQualcomm) "Qualcomm H.264" else "hardware H.264"
            Log.info("[TvMainScreenCastHost] WebRTC encoder policy: $policy only")
        }

        override fun test(codecInfo: MediaCodecInfo): Boolean {
            return codecInfo.isAllowedHardwareAvcEncoder(ignoreQualcommPreference = false)
        }

        private fun MediaCodecInfo.isAllowedHardwareAvcEncoder(
            ignoreQualcommPreference: Boolean,
        ): Boolean {
            return isEncoder &&
                supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) } &&
                isHardwareCodec() &&
                !isSecureOrTunneledCodec() &&
                (ignoreQualcommPreference || !preferQualcomm || isQualcommCodec())
        }

        private fun MediaCodecInfo.isHardwareCodec(): Boolean {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                return isHardwareAccelerated && !isSoftwareOnly
            }

            val lowerName = name.lowercase(Locale.US)
            val knownSoftwarePrefixes = listOf(
                "omx.google.",
                "c2.android.",
                "c2.google.",
                "ffmpeg"
            )
            return knownSoftwarePrefixes.none { lowerName.startsWith(it) }
        }

        private fun MediaCodecInfo.isQualcommCodec(): Boolean {
            val lowerName = name.lowercase(Locale.US)
            return lowerName.startsWith("c2.qti.") ||
                lowerName.startsWith("omx.qcom.") ||
                lowerName.contains("qti") ||
                lowerName.contains("qcom")
        }

        private fun MediaCodecInfo.isSecureOrTunneledCodec(): Boolean {
            val lowerName = name.lowercase(Locale.US)
            return lowerName.contains(".secure") || lowerName.contains(".tunneled")
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

    private class DisabledVideoDecoderFactory : VideoDecoderFactory {
        override fun createDecoder(info: VideoCodecInfo): VideoDecoder? = null

        override fun getSupportedCodecs(): Array<VideoCodecInfo> = emptyArray()
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
        private val FMTP_REGEX = Regex("""a=fmtp:(\d+)\s*(.*)""", RegexOption.IGNORE_CASE)
        private val H264_PROFILE_LEVEL_ID_REGEX = Regex("""(?:^|[;\s])profile-level-id=([0-9a-fA-F]{6})(?:$|[;\s])""")
        private const val TV_CAST_PREFS_NAME = "azahar_tv_main_screen_cast"
        private const val PREF_VIDEO_RESOLUTION = "video_resolution"
        private const val PREF_VIDEO_ASPECT = "video_aspect"
        private const val PREF_VIDEO_BITRATE = "video_bitrate"
        private const val PREF_VIDEO_PRIORITY = "video_priority"
        private const val PREF_AUDIO_MODE = "audio_mode"
        private const val PREF_AUDIO_CODEC = "audio_codec"
        private const val VIDEO_TRACK_ID = "AZAHAR_TOP_SCREEN"
        private const val AUDIO_TRACK_ID = "AZAHAR_TV_AUDIO"
        private const val AUDIO_DATA_CHANNEL_LABEL = "audio"
        private const val BASE_TOP_SCREEN_WIDTH = 400
        private const val BASE_TOP_SCREEN_HEIGHT = 240
        private const val ASPECT_RATIO_DEFAULT = 0
        private const val ASPECT_RATIO_16_9 = 1
        private const val ASPECT_RATIO_STRETCH = 5
        private const val CAST_FPS = 60
        private const val TV_CAST_GOP_SECONDS = 2
        private const val TV_CAST_MAX_B_FRAMES = 0
        private const val TV_CAST_QP_P_MAX = 34
        private const val NATIVE_SAMPLE_RATE = 32728
        private const val WEB_AUDIO_SAMPLE_RATE = 48000
        private const val AUDIO_CHUNK_FRAMES = 480
        private const val MAX_PENDING_AUDIO_TASKS = 4
        private const val MAX_OPUS_AUDIO_CHUNKS = 12
        private const val RESOLUTION_RADIO_BASE_ID = 30_000
        private const val ASPECT_RADIO_BASE_ID = 32_500
        private const val BITRATE_RADIO_BASE_ID = 35_000
        private const val VIDEO_PRIORITY_RADIO_BASE_ID = 37_500
        private const val AUDIO_MODE_RADIO_BASE_ID = 40_000
        private const val AUDIO_CODEC_RADIO_BASE_ID = 45_000
        private const val TV_CAST_HTTP_PORT = 41315
        private const val VIDEO_FRAME_WARNING_DELAY_MS = 3_000L
        private const val HTTP_SOCKET_TIMEOUT_MS = 10_000
        private const val MAX_HTTP_HEADER_BYTES = 16 * 1024
        private const val MAX_WEBSOCKET_PAYLOAD_BYTES = 2 * 1024 * 1024
        private const val HTTP_HEADER_END = "\r\n\r\n"
        private const val WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

        private fun roundToEven(value: Float): Int {
            val rounded = value.roundToInt()
            if (rounded % 2 == 0) {
                return rounded
            }
            val lower = rounded - 1
            val upper = rounded + 1
            return if (abs(value - lower) <= abs(value - upper)) lower else upper
        }

        private fun ensurePeerConnectionFactoryInitialized(context: android.content.Context) {
            if (peerConnectionFactoryInitialized.compareAndSet(false, true)) {
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(context)
                        .setFieldTrials("WebRTC-H264HighProfile/Enabled/")
                        .createInitializationOptions()
                )
            }
        }

    }
}
