// Copyright Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

package org.citra.citra_emu.moonlight

import android.app.Dialog
import android.view.Surface
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.Closeable
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil
import org.citra.citra_emu.NativeLibrary
import org.citra.citra_emu.R
import org.citra.citra_emu.activities.EmulationActivity
import org.citra.citra_emu.display.ScreenLayout
import org.citra.citra_emu.display.SecondaryDisplayLayout
import org.citra.citra_emu.features.settings.model.BooleanSetting
import org.citra.citra_emu.features.settings.model.IntSetting
import org.citra.citra_emu.features.settings.model.Settings
import org.citra.citra_emu.features.settings.utils.SettingsFile
import org.citra.citra_emu.moonlight.audio.OpusEncoderSession
import org.citra.citra_emu.moonlight.encoder.EncoderSelector
import org.citra.citra_emu.moonlight.encoder.EncoderSession
import org.citra.citra_emu.moonlight.model.CodecPreference
import org.citra.citra_emu.moonlight.model.StreamConfig
import org.citra.citra_emu.moonlight.pairing.PairingPin
import org.citra.citra_emu.moonlight.server.GameStreamServer
import org.citra.citra_emu.utils.EmulationMenuSettings
import org.citra.citra_emu.utils.Log

class MoonlightCastHost(
    private val activity: EmulationActivity,
    private val settings: Settings,
    private val releaseSecondaryDisplay: () -> Unit,
    private val restoreSecondaryDisplay: () -> Unit,
    private val protectNativeSurface: Boolean = false,
    private val onStopped: () -> Unit = {},
) : Closeable {
    private val running = AtomicBoolean(false)
    private val recentLogs = mutableListOf<String>()
    private val logLock = Any()
    private val timestampFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val audioResampler = Pcm16Resampler()

    private var gameStreamServer: GameStreamServer? = null
    private var encoderSession: EncoderSession? = null
    private var encoderSurface: Surface? = null
    private var opusEncoderSession: OpusEncoderSession? = null
    private var activeStreamConfig: StreamConfig? = null
    private var activeEncoderMime: String? = null
    private var currentPin: String = "----"
    private var pairingDialog: Dialog? = null
    private var pairingStatusText: TextView? = null
    private var codecStatusText: TextView? = null
    private var logText: TextView? = null
    private var startGameButton: Button? = null
    private var startGameInlineButton: Button? = null
    private var onStartGame: (() -> Unit)? = null
    private var onCancelBeforeGame: (() -> Unit)? = null
    private val gameStartRequested = AtomicBoolean(false)

    private val previousScreenLayout = IntSetting.SCREEN_LAYOUT.int
    private val previousSecondaryLayout = IntSetting.SECONDARY_DISPLAY_LAYOUT.int
    private val previousAspectRatio = IntSetting.ASPECT_RATIO.int
    private val previousSwapScreen = BooleanSetting.SWAP_SCREEN.boolean
    private val previousMenuSwapScreen = EmulationMenuSettings.swapScreens
    private val previousKeepScreenOn =
        activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0

    val isRunning: Boolean
        get() = running.get()

    fun start() {
        if (!running.compareAndSet(false, true)) {
            return
        }

        try {
            applyCastLayout()
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            startAudioPipeline()

            val config = defaultConfig()
            val advertisedMime = advertisedMimeFor(config)
            val server = GameStreamServer(
                context = activity.applicationContext,
                initialPin = PairingPin.normalize(currentPin),
                onIdrRequested = { encoderSession?.requestSyncFrame() },
                onVideoCongestion = { encoderSession?.requestSyncFrame() },
                onStreamConfigRequested = { requestedConfig -> startOrRestartCapture(requestedConfig) },
                onLaunchRequested = { requestedConfig ->
                    requestGameStart("Moonlight launch", requestedConfig)
                },
                onPinChanged = { newPin -> currentPin = newPin },
                onLog = { log(it) },
            )
            gameStreamServer = server
            server.start(config, advertisedMime)
            log(
                "Moonlight host ready at ${findLocalIpv4Address()}; " +
                    "advertising ${mimeLabel(advertisedMime)} ${config.width}x${config.height}@${config.fps}",
            )
        } catch (e: Exception) {
            close()
            throw e
        }
    }

    fun showPairingDialog(
        onStartGame: (() -> Unit)? = null,
        onCancelBeforeGame: (() -> Unit)? = null,
    ) {
        if (onStartGame != null || onCancelBeforeGame != null) {
            setPendingGameStart(onStartGame, onCancelBeforeGame)
        }
        val canStartGame = this.onStartGame != null

        val density = activity.resources.displayMetrics.density
        val padding = (24 * density).toInt()
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, 0)
        }
        val addressText = TextView(activity).apply {
            text = activity.getString(R.string.moonlight_cast_address, findLocalIpv4Address())
        }
        val statusText = TextView(activity).apply {
            setText(R.string.moonlight_cast_waiting)
        }
        val codecText = TextView(activity).apply {
            text = activity.getString(R.string.moonlight_cast_codec_status, advertisedMimeFor(defaultConfig()).let(::mimeLabel))
        }
        val pinInput = EditText(activity).apply {
            hint = activity.getString(R.string.moonlight_cast_pairing_pin_hint)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            maxLines = 1
        }
        val pinButton = Button(activity).apply {
            setText(R.string.moonlight_cast_set_pin)
            setOnClickListener {
                val pin = pinInput.text?.toString().orEmpty()
                if (!setPairingPin(pin)) {
                    Toast.makeText(activity, R.string.moonlight_cast_invalid_pin, Toast.LENGTH_SHORT).show()
                }
            }
        }
        val inlineStartButton = if (canStartGame) {
            Button(activity).apply {
                setText(R.string.moonlight_cast_start_game)
                setOnClickListener {
                    requestGameStart("host dialog")
                }
            }
        } else {
            null
        }
        val logs = TextView(activity).apply {
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            text = recentLogText()
        }
        val scrollView = ScrollView(activity).apply {
            addView(logs)
        }
        pairingStatusText = statusText
        codecStatusText = codecText
        logText = logs
        startGameInlineButton = inlineStartButton
        content.addView(addressText)
        content.addView(statusText)
        content.addView(codecText)
        content.addView(pinInput)
        content.addView(pinButton)
        inlineStartButton?.let(content::addView)
        content.addView(scrollView)

        pairingDialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.moonlight_cast_pairing_title)
            .setMessage(R.string.moonlight_cast_pairing_message)
            .setView(content)
            .apply {
                if (canStartGame) {
                    setPositiveButton(R.string.moonlight_cast_start_game, null)
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> handlePairingCancel() }
            .setOnCancelListener { handlePairingCancel() }
            .show()

        if (canStartGame) {
            startGameButton = (pairingDialog as? AlertDialog)?.getButton(AlertDialog.BUTTON_POSITIVE)
            startGameButton?.setOnClickListener {
                requestGameStart("host dialog")
            }
        }
    }

    fun setPendingGameStart(
        onStartGame: (() -> Unit)?,
        onCancelBeforeGame: (() -> Unit)?,
    ) {
        this.onStartGame = onStartGame
        this.onCancelBeforeGame = onCancelBeforeGame
        gameStartRequested.set(false)
    }

    private fun requestGameStart(reason: String, requestedConfig: StreamConfig? = null) {
        val startGame = onStartGame
        if (startGame == null) {
            log("$reason received while game is already running")
            encoderSession?.requestSyncFrame()
            return
        }
        if (!gameStartRequested.compareAndSet(false, true)) {
            return
        }
        log("Starting game after $reason")

        if (protectNativeSurface && encoderSurface == null) {
            val initialConfig = requestedConfig ?: activeStreamConfig ?: defaultConfig()
            if (startOrRestartCapture(initialConfig) == null) {
                gameStartRequested.set(false)
                log("Game start blocked: Moonlight encoder surface is not available")
                return
            }
        }

        activity.runOnUiThread {
            pairingStatusText?.setText(R.string.moonlight_cast_game_started)
            startGameButton?.isEnabled = false
            startGameInlineButton?.isEnabled = false
            startGameButton?.setText(R.string.moonlight_cast_game_started)
            startGameInlineButton?.setText(R.string.moonlight_cast_game_started)
            pairingDialog?.setOnCancelListener(null)
            pairingDialog?.dismiss()
            pairingDialog = null
            activity.window.decorView.postDelayed({ startGame() }, START_GAME_AFTER_DIALOG_DELAY_MS)
        }
    }

    @Synchronized
    private fun startOrRestartCapture(config: StreamConfig): String? {
        val currentMime = activeEncoderMime
        if (encoderSession != null && activeStreamConfig == config && currentMime != null) {
            encoderSession?.requestSyncFrame()
            return currentMime
        }
        val lockedConfig = activeStreamConfig
        if (
            protectNativeSurface &&
            NativeLibrary.isRunning() &&
            encoderSession != null &&
            lockedConfig != null &&
            currentMime != null
        ) {
            if (MoonlightStreamLock.canReuseLockedEncoder(lockedConfig, config, currentMime)) {
                if (lockedConfig.bitrate != config.bitrate) {
                    encoderSession?.setVideoBitrate(config.bitrate)
                }
                activeStreamConfig = config
                encoderSession?.requestSyncFrame()
                return currentMime
            }

            log(
                "Rejected Moonlight stream config: Vulkan encoder surface is locked at " +
                    "${lockedConfig.resolutionLabel}@${lockedConfig.fps} ${mimeLabel(currentMime)}",
            )
            updateDialogStatus(
                activity.getString(
                    R.string.moonlight_cast_error,
                    "Vulkan stream config is locked after game start",
                ),
            )
            return null
        }

        val encoderInfo = runCatching {
            EncoderSelector.select(
                codecPreference = config.codecPreference,
                width = config.width,
                height = config.height,
                fps = config.fps,
                bitrate = config.bitrate,
            )
        }.getOrElse { throwable ->
            log("Rejected Moonlight stream config: ${throwable.message}")
            updateDialogStatus(activity.getString(R.string.moonlight_cast_error, throwable.message ?: "encoder"))
            return null
        }

        stopCapturePipeline()
        val server = gameStreamServer ?: return null

        return try {
            val encoder = EncoderSession(
                config = config,
                encoderInfo = encoderInfo,
                onFrame = { frame -> server.onEncodedFrame(frame) },
                onError = { throwable ->
                    log("Encoder error: ${throwable.message}")
                    stopCapturePipeline()
                },
            )
            val surface = encoder.start()
            encoderSession = encoder
            encoderSurface = surface
            activeStreamConfig = config
            activeEncoderMime = encoderInfo.mime

            NativeLibrary.secondarySurfaceChanged(surface)
            NativeLibrary.updateFramebuffer(NativeLibrary.isPortraitMode)
            updateDialogStatus(activity.getString(R.string.moonlight_cast_streaming))
            updateCodecStatus(mimeLabel(encoderInfo.mime))
            log(
                "Streaming ${config.width}x${config.height}@${config.fps} " +
                    "${mimeLabel(encoderInfo.mime)} via ${encoderInfo.codecName}",
            )
            encoderInfo.mime
        } catch (e: Exception) {
            stopCapturePipeline()
            log("Failed to start Moonlight encoder: ${e.message}")
            updateDialogStatus(activity.getString(R.string.moonlight_cast_error, e.message ?: "encoder"))
            null
        }
    }

    @Synchronized
    private fun stopCapturePipeline() {
        runCatching { NativeLibrary.secondarySurfaceDestroyed() }
        encoderSession?.stop()
        encoderSession = null
        encoderSurface = null
        activeStreamConfig = null
        activeEncoderMime = null
    }

    private fun startAudioPipeline() {
        val opus = OpusEncoderSession(
            onPacket = { packet, durationMillis ->
                gameStreamServer?.onOpusAudioPacket(packet, durationMillis)
            },
            onLog = { log(it) },
            onError = { throwable -> log("Opus audio disabled: ${throwable.message}") },
        )
        opus.start()
        opusEncoderSession = opus
        NativeLibrary.setTvAudioFrameCallback { samples, sampleRate, channels, frames ->
            val pcm = audioResampler.resample(samples, sampleRate, channels, frames)
            if (pcm.isNotEmpty()) {
                opusEncoderSession?.queuePcm(pcm)
            }
        }
        NativeLibrary.setTvAudioTapEnabled(true)
    }

    private fun stopAudioPipeline() {
        NativeLibrary.setTvAudioTapEnabled(false)
        NativeLibrary.setTvAudioFrameCallback(null)
        opusEncoderSession?.stop()
        opusEncoderSession = null
        audioResampler.reset()
    }

    fun onEmulationStarted() {
        if (protectNativeSurface) {
            runCatching { NativeLibrary.updateFramebuffer(NativeLibrary.isPortraitMode) }
                .onFailure { Log.error("[MoonlightCastHost] Vulkan framebuffer refresh failed: ${it.message}") }
            return
        }
        val surface = encoderSurface ?: return
        runCatching {
            NativeLibrary.secondarySurfaceChanged(surface)
            NativeLibrary.updateFramebuffer(NativeLibrary.isPortraitMode)
        }.onFailure {
            Log.error("[MoonlightCastHost] Encoder surface reattach failed: ${it.message}")
        }
    }

    fun requestStop(): Boolean {
        if (protectNativeSurface && NativeLibrary.isRunning()) {
            activity.runOnUiThread {
                pairingDialog?.dismiss()
                pairingDialog = null
                Toast.makeText(
                    activity,
                    R.string.moonlight_cast_vulkan_stop_after_game,
                    Toast.LENGTH_LONG,
                ).show()
            }
            return false
        }

        close()
        return true
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) {
            return
        }

        stopCapturePipeline()
        stopAudioPipeline()
        gameStreamServer?.stop()
        gameStreamServer = null

        activity.runOnUiThread {
            pairingDialog?.setOnCancelListener(null)
            pairingDialog?.dismiss()
            pairingDialog = null
            pairingStatusText = null
            codecStatusText = null
            logText = null
            startGameButton = null
            startGameInlineButton = null
            restoreLayout()
            restoreSecondaryDisplay()
            if (!previousKeepScreenOn) {
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            onStopped()
            Toast.makeText(activity, R.string.moonlight_cast_stopped, Toast.LENGTH_SHORT).show()
        }
    }

    private fun handlePairingCancel() {
        if (protectNativeSurface && NativeLibrary.isRunning()) {
            pairingDialog?.setOnCancelListener(null)
            pairingDialog?.dismiss()
            pairingDialog = null
            Toast.makeText(
                activity,
                R.string.moonlight_cast_vulkan_stop_after_game,
                Toast.LENGTH_LONG,
            ).show()
            return
        }

        requestStop()
        onCancelBeforeGame?.invoke()
    }

    private fun setPairingPin(pin: String): Boolean {
        val normalized = PairingPin.normalize(pin) ?: return false
        currentPin = normalized
        gameStreamServer?.setPairingPin(normalized)
        log("Pairing PIN set")
        return true
    }

    private fun defaultConfig(): StreamConfig {
        val hevcAvailable = EncoderSelector.listHardwareEncoders(
            codecPreference = CodecPreference.HEVC,
            width = DEFAULT_WIDTH,
            height = DEFAULT_HEIGHT,
            fps = DEFAULT_FPS,
            bitrate = DEFAULT_HEVC_BITRATE,
        ).isNotEmpty()
        return StreamConfig(
            codecPreference = CodecPreference.AUTO,
            width = DEFAULT_WIDTH,
            height = DEFAULT_HEIGHT,
            fps = DEFAULT_FPS,
            bitrate = if (hevcAvailable) DEFAULT_HEVC_BITRATE else DEFAULT_H264_BITRATE,
            audioEnabled = true,
            lowLatency = true,
            iFrameIntervalSeconds = 1,
        )
    }

    private fun advertisedMimeFor(config: StreamConfig): String =
        when (config.codecPreference) {
            CodecPreference.H264 -> MIME_H264
            CodecPreference.HEVC -> MIME_HEVC
            CodecPreference.AUTO -> {
                val hevcAvailable = EncoderSelector.listHardwareEncoders(
                    codecPreference = CodecPreference.HEVC,
                    width = config.width,
                    height = config.height,
                    fps = config.fps,
                    bitrate = config.bitrate,
                ).isNotEmpty()
                if (hevcAvailable) MIME_HEVC else MIME_H264
            }
        }

    private fun applyCastLayout() {
        IntSetting.SCREEN_LAYOUT.int = ScreenLayout.SINGLE_SCREEN.int
        BooleanSetting.SWAP_SCREEN.boolean = true
        EmulationMenuSettings.swapScreens = true
        IntSetting.SECONDARY_DISPLAY_LAYOUT.int = SecondaryDisplayLayout.TOP_SCREEN.int
        IntSetting.ASPECT_RATIO.int = previousAspectRatio
        settings.saveSetting(IntSetting.SCREEN_LAYOUT, SettingsFile.FILE_NAME_CONFIG)
        settings.saveSetting(BooleanSetting.SWAP_SCREEN, SettingsFile.FILE_NAME_CONFIG)
        settings.saveSetting(IntSetting.SECONDARY_DISPLAY_LAYOUT, SettingsFile.FILE_NAME_CONFIG)
        settings.saveSetting(IntSetting.ASPECT_RATIO, SettingsFile.FILE_NAME_CONFIG)
        NativeLibrary.reloadSettings()
        NativeLibrary.swapScreens(true, activity.windowManager.defaultDisplay.rotation)
        runCatching { NativeLibrary.updateFramebuffer(NativeLibrary.isPortraitMode) }
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
        runCatching { NativeLibrary.updateFramebuffer(NativeLibrary.isPortraitMode) }
    }

    private fun log(message: String) {
        Log.info("[MoonlightCastHost] $message")
        synchronized(logLock) {
            recentLogs.add("${timestampFormat.format(Date())}  $message")
            while (recentLogs.size > MAX_RECENT_LOGS) {
                recentLogs.removeAt(0)
            }
        }
        refreshLogs()
    }

    private fun updateDialogStatus(status: String) {
        activity.runOnUiThread {
            pairingStatusText?.text = status
        }
    }

    private fun updateCodecStatus(codec: String) {
        activity.runOnUiThread {
            codecStatusText?.text = activity.getString(R.string.moonlight_cast_codec_status, codec)
        }
    }

    private fun refreshLogs() {
        val text = recentLogText()
        activity.runOnUiThread {
            logText?.text = text
        }
    }

    private fun recentLogText(): String =
        synchronized(logLock) { recentLogs.takeLast(20).joinToString("\n") }

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

    private fun mimeLabel(mime: String): String =
        when (mime) {
            MIME_HEVC -> "HEVC"
            MIME_H264 -> "H.264"
            else -> mime
        }

    private class Pcm16Resampler {
        private var nextSourcePosition = 0.0

        fun reset() {
            nextSourcePosition = 0.0
        }

        fun resample(samples: ShortArray, sampleRate: Int, channels: Int, frames: Int): ByteArray {
            if (samples.isEmpty() || sampleRate <= 0 || channels <= 0 || frames <= 0) {
                return ByteArray(0)
            }
            if (sampleRate == OPUS_SAMPLE_RATE && channels == OPUS_CHANNELS) {
                return shortsToLittleEndian(samples, frames, channels)
            }

            val sourceStep = sampleRate.toDouble() / OPUS_SAMPLE_RATE.toDouble()
            val maxOutputFrames = ceil((frames + 2) / sourceStep).toInt().coerceAtLeast(1)
            val output = ByteArray(maxOutputFrames * OPUS_CHANNELS * BYTES_PER_SAMPLE)
            var outputOffset = 0
            var position = nextSourcePosition
            while (position < frames && outputOffset + 3 < output.size) {
                val index = position.toInt().coerceIn(0, frames - 1)
                val nextIndex = (index + 1).coerceAtMost(frames - 1)
                val fraction = position - index
                val left = interpolate(sampleAt(samples, index, channels, 0), sampleAt(samples, nextIndex, channels, 0), fraction)
                val right = interpolate(sampleAt(samples, index, channels, 1), sampleAt(samples, nextIndex, channels, 1), fraction)
                outputOffset = putShortLe(output, outputOffset, left)
                outputOffset = putShortLe(output, outputOffset, right)
                position += sourceStep
            }
            nextSourcePosition = position - frames
            return output.copyOf(outputOffset)
        }

        private fun shortsToLittleEndian(samples: ShortArray, frames: Int, channels: Int): ByteArray {
            val output = ByteArray(frames * OPUS_CHANNELS * BYTES_PER_SAMPLE)
            var outputOffset = 0
            for (frame in 0 until frames) {
                outputOffset = putShortLe(output, outputOffset, sampleAt(samples, frame, channels, 0))
                outputOffset = putShortLe(output, outputOffset, sampleAt(samples, frame, channels, 1))
            }
            return output
        }

        private fun sampleAt(samples: ShortArray, frame: Int, channels: Int, channel: Int): Short {
            val actualChannel = if (channels == 1) 0 else channel.coerceAtMost(channels - 1)
            val index = frame * channels + actualChannel
            return samples.getOrElse(index) { 0 }
        }

        private fun interpolate(a: Short, b: Short, fraction: Double): Short =
            (a + (b - a) * fraction).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()

        private fun putShortLe(output: ByteArray, offset: Int, sample: Short): Int {
            output[offset] = (sample.toInt() and 0xFF).toByte()
            output[offset + 1] = ((sample.toInt() ushr 8) and 0xFF).toByte()
            return offset + BYTES_PER_SAMPLE
        }
    }

    companion object {
        private const val MIME_H264 = "video/avc"
        private const val MIME_HEVC = "video/hevc"
        private const val DEFAULT_WIDTH = 800
        private const val DEFAULT_HEIGHT = 480
        private const val DEFAULT_FPS = 60
        private const val DEFAULT_HEVC_BITRATE = 5_000_000
        private const val DEFAULT_H264_BITRATE = 7_000_000
        private const val OPUS_SAMPLE_RATE = 48_000
        private const val OPUS_CHANNELS = 2
        private const val BYTES_PER_SAMPLE = 2
        private const val MAX_RECENT_LOGS = 80
        private const val START_GAME_AFTER_DIALOG_DELAY_MS = 150L
    }
}
