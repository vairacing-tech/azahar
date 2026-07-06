package org.citra.citra_emu.moonlight.server

import android.content.Context
import android.media.MediaCodec
import android.os.SystemClock
import org.citra.citra_emu.moonlight.crypto.ServerIdentity
import org.citra.citra_emu.moonlight.encoder.EncodedFrame
import org.citra.citra_emu.moonlight.model.CodecPreference
import org.citra.citra_emu.moonlight.model.StreamConfig
import org.citra.citra_emu.moonlight.pairing.PairingPin
import org.citra.citra_emu.moonlight.pairing.PairingProtocol
import org.citra.citra_emu.moonlight.pairing.PairingStore
import java.util.concurrent.atomic.AtomicLong

class GameStreamServer(
    context: Context,
    initialPin: String?,
    private val onIdrRequested: () -> Unit,
    private val onVideoCongestion: () -> Unit,
    private val onStreamConfigRequested: (StreamConfig) -> String?,
    private val onLaunchRequested: (StreamConfig?) -> Unit,
    private val onPinChanged: (String) -> Unit,
    private val onLog: (String) -> Unit,
) {
    private val appContext = context.applicationContext
    private val hostIdentity = HostIdentity(appContext)
    private val pairingStore = PairingStore(context.applicationContext)
    private val frameCount = AtomicLong()
    private var videoTransport: VideoRtpTransport? = null
    private var audioTransport: AudioRtpTransport? = null
    private var legacyControlServer: LegacyControlTcpServer? = null
    private var inputSinkServer: TcpInputSinkServer? = null
    private var mdnsAdvertiser: MdnsAdvertiser? = null
    private var nvHttpServer: NvHttpServer? = null
    private var rtspServer: RtspServer? = null
    @Volatile private var activePin: String? = initialPin
    @Volatile private var activeConfig: StreamConfig = StreamConfig()
    @Volatile private var activeVideoMime: String = "video/avc"
    private val pinLock = Object()

    fun start(config: StreamConfig, advertisedVideoMime: String) {
        if (nvHttpServer != null || rtspServer != null) return
        activeConfig = config
        activeVideoMime = advertisedVideoMime
        ClientConnectionState.clear()
        frameCount.set(0)
        val identity = ServerIdentity.load(appContext)
        val pairingProtocol = PairingProtocol(
            serverIdentity = identity,
            pairingStore = pairingStore,
            pinProvider = { awaitPairingPin() },
            hash = GameStreamProtocol.pairingHash,
        )
        videoTransport = VideoRtpTransport(
            port = Ports.VIDEO,
            includeFrameHeader = GameStreamProtocol.INCLUDE_VIDEO_FRAME_HEADER,
            onPeerReady = {
                onLog("Video UDP peer ready; requesting IDR frame")
                onIdrRequested()
            },
            onCongestion = {
                onVideoCongestion()
            },
            onLog = onLog,
        ).also { it.start() }
        audioTransport = AudioRtpTransport(
            port = Ports.AUDIO,
            onLog = onLog,
        ).also { it.start() }
        if (GameStreamProtocol.LEGACY_TCP_CONTROL) {
            legacyControlServer = LegacyControlTcpServer(
                port = Ports.LEGACY_CONTROL,
                onIdrRequested = {
                    onLog("Client requested IDR frame")
                    onIdrRequested()
                },
                onLog = onLog,
            ).also { it.start() }
            inputSinkServer = TcpInputSinkServer(
                port = Ports.LEGACY_INPUT,
                onLog = onLog,
            ).also { it.start() }
        }
        nvHttpServer = NvHttpServer(
            httpPort = Ports.HTTP,
            httpsPort = Ports.HTTPS,
            serverIdentity = identity,
            pairingStore = pairingStore,
            pairingProtocol = pairingProtocol,
            uniqueId = hostIdentity.uniqueId,
            currentConfig = { activeConfig },
            activeVideoMime = { activeVideoMime },
            onPinReceived = { pin -> setPairingPin(pin) },
            onLaunchRequested = { requestedConfig -> acceptLaunchConfig(requestedConfig) },
            onLog = onLog,
        ).also { it.start() }
        mdnsAdvertiser = MdnsAdvertiser(
            context = appContext,
            serviceName = "Azahar Main Screen",
            port = Ports.HTTP,
            onLog = onLog,
        ).also { it.start() }
        rtspServer = RtspServer(
            port = Ports.RTSP,
            currentConfig = { activeConfig },
            activeVideoMime = { activeVideoMime },
            onVideoPacketSize = { size -> videoTransport?.setPacketSize(size) },
            onStreamConfigRequested = { requestedConfig ->
                acceptStreamConfig(requestedConfig, "RTSP ANNOUNCE")
            },
            onPlay = {
                onLog("RTSP PLAY received; requesting IDR and waiting for video UDP ping on ${Ports.VIDEO}")
                onIdrRequested()
            },
            onLog = onLog,
        ).also { it.start() }
        val pinState = activePin ?: "not set"
        onLog("Pairing PIN $pinState; GameStream servers listening on ${Ports.HTTP}/${Ports.HTTPS}/${Ports.RTSP}/${Ports.VIDEO}")
    }

    private fun acceptLaunchConfig(requestedConfig: StreamConfig?): Boolean {
        onLog("Launch requested by client")
        if (requestedConfig != null) {
            activeConfig = requestedConfig
            activeVideoMime = advertisedMimeFor(requestedConfig)
            onLog(
                "Launch stream config noted for RTSP: ${requestedConfig.resolutionLabel} " +
                    "${requestedConfig.fps}fps ${requestedConfig.bitrate / 1_000_000} Mbps " +
                    requestedConfig.codecPreference.name,
            )
        }
        onLaunchRequested(requestedConfig)
        return true
    }

    private fun acceptStreamConfig(config: StreamConfig, source: String): Boolean {
        val mime = onStreamConfigRequested(config) ?: run {
            onLog("$source stream config rejected: ${config.resolutionLabel} ${config.fps}fps ${config.bitrate / 1_000_000} Mbps ${config.codecPreference.name}")
            return false
        }
        videoTransport?.setFrameRate(config.fps)
        activeConfig = config
        activeVideoMime = mime
        onLog("$source stream config accepted: ${config.resolutionLabel} ${config.fps}fps ${config.bitrate / 1_000_000} Mbps ${mimeLabel(mime)}")
        return true
    }

    fun setPairingPin(pin: String) {
        synchronized(pinLock) {
            activePin = pin
            pinLock.notifyAll()
        }
        onPinChanged(pin)
        onLog("Pairing PIN updated")
    }

    private fun awaitPairingPin(timeoutMillis: Long = PAIRING_PIN_TIMEOUT_MILLIS): String? {
        PairingPin.normalize(activePin)?.let { return it }
        onLog("Waiting for pairing PIN from Artemis/Moonlight")
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        synchronized(pinLock) {
            while (true) {
                PairingPin.normalize(activePin)?.let { return it }
                val remaining = deadline - SystemClock.elapsedRealtime()
                if (remaining <= 0L) return null
                runCatching { pinLock.wait(remaining) }
            }
        }
    }

    fun stop() {
        ClientConnectionState.clear()
        nvHttpServer?.stop()
        nvHttpServer = null
        mdnsAdvertiser?.stop()
        mdnsAdvertiser = null
        rtspServer?.stop()
        rtspServer = null
        inputSinkServer?.stop()
        inputSinkServer = null
        legacyControlServer?.stop()
        legacyControlServer = null
        audioTransport?.stop()
        audioTransport = null
        videoTransport?.stop()
        videoTransport = null
        onLog("GameStream control servers stopped")
    }

    private fun advertisedMimeFor(config: StreamConfig): String =
        when (config.codecPreference) {
            CodecPreference.HEVC -> "video/hevc"
            CodecPreference.H264 -> "video/avc"
            CodecPreference.AUTO -> activeVideoMime
        }

    fun onEncodedFrame(frame: EncodedFrame) {
        val count = frameCount.incrementAndGet()
        videoTransport?.sendFrame(frame)
        val keyFrame = (frame.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
        if (keyFrame) {
            onLog("Encoded keyframe $count, ${frame.bytes.size} bytes")
        } else if (count == 1L || count % 300L == 0L) {
            onLog("Encoded frame $count, ${frame.bytes.size} bytes, flags=${frame.flags}")
        }
    }

    fun onOpusAudioPacket(packet: ByteArray, durationMillis: Int) {
        audioTransport?.sendOpusPacket(packet, durationMillis)
    }

    companion object {
        private const val PAIRING_PIN_TIMEOUT_MILLIS = 90_000L

        private fun mimeLabel(mime: String): String =
            when (mime) {
                "video/hevc" -> "HEVC"
                "video/avc" -> "H.264"
                else -> mime
            }
    }
}
