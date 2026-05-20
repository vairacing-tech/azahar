// Copyright Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

package org.citra.citra_emu.dualdevice

import android.app.Dialog
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Surface
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.io.BufferedReader
import java.io.Closeable
import java.io.InputStreamReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.EnumMap
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import org.citra.citra_emu.NativeLibrary
import org.citra.citra_emu.R
import org.citra.citra_emu.activities.EmulationActivity
import org.citra.citra_emu.display.ScreenLayout
import org.citra.citra_emu.display.SecondaryDisplayLayout
import org.citra.citra_emu.features.settings.model.IntSetting
import org.citra.citra_emu.features.settings.model.Settings
import org.citra.citra_emu.features.settings.utils.SettingsFile
import org.citra.citra_emu.utils.Log

class DualDeviceCastHost(
    private val activity: EmulationActivity,
    private val settings: Settings,
    private val releaseSecondaryDisplay: () -> Unit,
    private val restoreSecondaryDisplay: () -> Unit,
) : Closeable {
    private val executor: ExecutorService = Executors.newCachedThreadPool()
    private val running = AtomicBoolean(false)
    private val token = UUID.randomUUID().toString().replace("-", "")

    private var serverSocket: ServerSocket? = null
    private var controlSocket: Socket? = null
    private var videoSocket: DatagramSocket? = null
    private var receiverAddress: java.net.InetAddress? = null
    private var receiverVideoPort = 0
    private var encoder: MediaCodec? = null
    private var encoderSurface: Surface? = null
    private var pairingDialog: Dialog? = null
    private var castConfig = CastConfig.default()
    private var sequence = 0

    private val previousScreenLayout = IntSetting.SCREEN_LAYOUT.int
    private val previousSecondaryLayout = IntSetting.SECONDARY_DISPLAY_LAYOUT.int

    val isRunning: Boolean
        get() = running.get()

    fun start() {
        if (!running.compareAndSet(false, true)) {
            return
        }

        try {
            serverSocket = ServerSocket(0)
            videoSocket = DatagramSocket().apply {
                sendBufferSize = VIDEO_SOCKET_BUFFER_BYTES
            }
            applyCastLayout()
            executor.execute(::acceptControlClient)
        } catch (e: Exception) {
            close()
            throw e
        }
    }

    fun showPairingDialog() {
        val qr = createQrBitmap(pairingUri(), 720)
        val density = activity.resources.displayMetrics.density
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (24 * density).toInt()
            setPadding(padding, padding, padding, 0)
        }
        val message = TextView(activity).apply {
            setText(R.string.dual_device_cast_pairing_message)
            textAlignment = TextView.TEXT_ALIGNMENT_CENTER
        }
        val image = ImageView(activity).apply {
            setImageBitmap(qr)
            adjustViewBounds = true
            maxWidth = (320 * density).toInt()
            maxHeight = (320 * density).toInt()
        }
        val status = TextView(activity).apply {
            setText(R.string.dual_device_cast_waiting)
            textAlignment = TextView.TEXT_ALIGNMENT_CENTER
        }
        content.addView(message)
        content.addView(image)
        content.addView(status)

        pairingDialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.dual_device_cast_pairing_title)
            .setView(content)
            .setNegativeButton(android.R.string.cancel) { _, _ -> close() }
            .show()
    }

    private fun pairingUri(): String {
        val host = findLocalIpv4Address()
        val controlPort = serverSocket?.localPort ?: 0
        val videoPort = videoSocket?.localPort ?: 0
        return Uri.Builder()
            .scheme("azahar2device")
            .authority("join")
            .appendQueryParameter("host", host)
            .appendQueryParameter("control", controlPort.toString())
            .appendQueryParameter("video", videoPort.toString())
            .appendQueryParameter("token", token)
            .appendQueryParameter("screen", "bottom")
            .build()
            .toString()
    }

    private fun applyCastLayout() {
        IntSetting.SCREEN_LAYOUT.int = ScreenLayout.SINGLE_SCREEN.int
        IntSetting.SECONDARY_DISPLAY_LAYOUT.int = SecondaryDisplayLayout.BOTTOM_SCREEN.int
        settings.saveSetting(IntSetting.SCREEN_LAYOUT, SettingsFile.FILE_NAME_CONFIG)
        settings.saveSetting(IntSetting.SECONDARY_DISPLAY_LAYOUT, SettingsFile.FILE_NAME_CONFIG)
        NativeLibrary.reloadSettings()
        NativeLibrary.updateFramebuffer(NativeLibrary.isPortraitMode)
        releaseSecondaryDisplay()
    }

    private fun restoreLayout() {
        IntSetting.SCREEN_LAYOUT.int = previousScreenLayout
        IntSetting.SECONDARY_DISPLAY_LAYOUT.int = previousSecondaryLayout
        settings.saveSetting(IntSetting.SCREEN_LAYOUT, SettingsFile.FILE_NAME_CONFIG)
        settings.saveSetting(IntSetting.SECONDARY_DISPLAY_LAYOUT, SettingsFile.FILE_NAME_CONFIG)
        NativeLibrary.reloadSettings()
        NativeLibrary.updateFramebuffer(NativeLibrary.isPortraitMode)
    }

    private fun acceptControlClient() {
        try {
            val socket = serverSocket?.accept() ?: return
            controlSocket = socket
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val hello = reader.readLine() ?: throw IllegalStateException("Receiver disconnected")
            val parts = hello.trim().split(" ")
            if (parts.size < 3 || parts[0] != "HELLO" || parts[1] != token) {
                socket.getOutputStream().write("ERR auth\n".toByteArray())
                throw IllegalArgumentException("Invalid receiver token")
            }

            receiverAddress = socket.inetAddress
            receiverVideoPort = parts[2].toInt()
            castConfig = CastConfig.fromReceiverRequest(parts.getOrNull(3), parts.getOrNull(4))
            socket.getOutputStream()
                .write("OK ${castConfig.width} ${castConfig.height} $CAST_FPS ${castConfig.aspectMode.protocolValue}\n".toByteArray())
            activity.runOnUiThread {
                Toast.makeText(activity, R.string.dual_device_cast_connected, Toast.LENGTH_SHORT).show()
                pairingDialog?.dismiss()
                pairingDialog = null
            }

            startEncoder(castConfig)
            readControlMessages(reader)
            close()
        } catch (e: Exception) {
            if (running.get()) {
                Log.error("[DualDeviceCastHost] ${e.message}")
                activity.runOnUiThread {
                    Toast.makeText(
                        activity,
                        activity.getString(R.string.dual_device_cast_error, e.message ?: "unknown"),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            close()
        }
    }

    private fun readControlMessages(reader: BufferedReader) {
        while (running.get()) {
            val line = reader.readLine() ?: break
            val parts = line.trim().split(" ")
            if (parts.isEmpty()) continue
            when (parts[0].uppercase(Locale.US)) {
                "TOUCH" -> handleTouch(parts)
                "PING" -> controlSocket?.getOutputStream()?.write("PONG\n".toByteArray())
                "STOP" -> break
            }
        }
    }

    private fun handleTouch(parts: List<String>) {
        if (parts.size < 4) return
        val action = parts[1]
        val x = (parts[2].toFloatOrNull() ?: return).coerceIn(0f, 1f) * castConfig.width
        val y = (parts[3].toFloatOrNull() ?: return).coerceIn(0f, 1f) * castConfig.height
        when (action) {
            "down" -> NativeLibrary.onSecondaryTouchEvent(x, y, true)
            "move" -> NativeLibrary.onSecondaryTouchMoved(x, y)
            "up", "cancel" -> NativeLibrary.onSecondaryTouchEvent(0f, 0f, false)
        }
    }

    private fun startEncoder(config: CastConfig) {
        val encoderInfo = selectHardwareAvcEncoder(config)
        val codec = createConfiguredEncoder(encoderInfo, config)
        encoderSurface = codec.createInputSurface()
        encoder = codec
        codec.start()
        applyLowLatencyRuntimeParameters(codec)
        NativeLibrary.secondarySurfaceChanged(encoderSurface!!)
        executor.execute(::drainEncoder)
    }

    private fun selectHardwareAvcEncoder(config: CastConfig): MediaCodecInfo {
        val candidates = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter {
                it.isEncoder &&
                    it.supportedTypes.any { type -> type.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) } &&
                    it.isHardwareCodec() &&
                    !it.isSecureOrTunneledCodec() &&
                    it.supportsSurfaceInput() &&
                    it.supportsAvcSize(config.width, config.height, CAST_FPS)
            }
            .sortedWith(
                compareByDescending<MediaCodecInfo> { it.preferenceScore() }
                    .thenBy { it.name }
            )

        val encoderInfo = candidates.firstOrNull()
            ?: throw IllegalStateException("No hardware AVC encoder with Surface input")
        val vendor = if (encoderInfo.isQualcommCodec()) "Qualcomm" else "hardware"
        Log.info(
            "[DualDeviceCastHost] Using $vendor encoder ${encoderInfo.name} " +
                "${config.width}x${config.height}@$CAST_FPS ${config.bitrate}bps"
        )
        return encoderInfo
    }

    private fun createConfiguredEncoder(
        encoderInfo: MediaCodecInfo,
        config: CastConfig,
    ): MediaCodec {
        return tryCreateConfiguredEncoder(encoderInfo, config, tuned = true)
            ?: tryCreateConfiguredEncoder(encoderInfo, config, tuned = false)
            ?: throw IllegalStateException("Unable to configure hardware AVC encoder ${encoderInfo.name}")
    }

    private fun tryCreateConfiguredEncoder(
        encoderInfo: MediaCodecInfo,
        config: CastConfig,
        tuned: Boolean,
    ): MediaCodec? {
        var codec: MediaCodec? = null
        return try {
            codec = MediaCodec.createByCodecName(encoderInfo.name)
            codec.configure(
                createEncoderFormat(encoderInfo, config, tuned),
                null,
                null,
                MediaCodec.CONFIGURE_FLAG_ENCODE
            )
            codec
        } catch (e: Exception) {
            try {
                codec?.release()
            } catch (_: Exception) {
            }
            if (tuned) {
                Log.error("[DualDeviceCastHost] Tuned encoder config failed, retrying baseline: ${e.message}")
            }
            null
        }
    }

    private fun createEncoderFormat(
        encoderInfo: MediaCodecInfo,
        config: CastConfig,
        tuned: Boolean,
    ): MediaFormat {
        return MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC,
            config.width,
            config.height
        ).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, config.bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, CAST_FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                setInteger(MediaFormat.KEY_OPERATING_RATE, CAST_FPS)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                setInteger(MediaFormat.KEY_LATENCY, 0)
            }
            if (tuned) {
                applyEncoderTuning(encoderInfo)
            }
        }
    }

    private fun MediaFormat.applyEncoderTuning(encoderInfo: MediaCodecInfo) {
        val capabilities = encoderInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val encoderCapabilities = capabilities.encoderCapabilities
        if (encoderCapabilities.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)) {
            setInteger(
                MediaFormat.KEY_BITRATE_MODE,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
            )
        }
        when {
            encoderInfo.supportsAvcProfile(MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedBaseline) ->
                setInteger(
                    MediaFormat.KEY_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedBaseline
                )
            encoderInfo.supportsAvcProfile(MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline) ->
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
        }
        setInteger("low-latency", 1)
        setInteger("vendor.qti-ext-enc-low-latency.enable", 1)
    }

    private fun applyLowLatencyRuntimeParameters(codec: MediaCodec) {
        try {
            codec.setParameters(Bundle().apply {
                putInt("low-latency", 1)
                putInt("vendor.qti-ext-enc-low-latency.enable", 1)
            })
        } catch (_: Exception) {
        }
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

    private fun MediaCodecInfo.preferenceScore(): Int {
        val lowerName = name.lowercase(Locale.US)
        return when {
            lowerName.startsWith("c2.qti.") -> 400
            lowerName.startsWith("omx.qcom.") -> 300
            lowerName.contains("qti") || lowerName.contains("qcom") -> 200
            else -> 100
        }
    }

    private fun MediaCodecInfo.supportsSurfaceInput(): Boolean {
        return try {
            getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                .colorFormats
                .contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        } catch (_: Exception) {
            false
        }
    }

    private fun MediaCodecInfo.supportsAvcSize(width: Int, height: Int, fps: Int): Boolean {
        return try {
            val videoCapabilities = getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                videoCapabilities.areSizeAndRateSupported(width, height, fps.toDouble()) ||
                    videoCapabilities.isSizeSupported(width, height)
            } else {
                videoCapabilities.isSizeSupported(width, height)
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun MediaCodecInfo.supportsAvcProfile(profile: Int): Boolean {
        return try {
            getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                .profileLevels
                .any { it.profile == profile }
        } catch (_: Exception) {
            false
        }
    }

    private fun drainEncoder() {
        val info = MediaCodec.BufferInfo()
        val codec = encoder ?: return
        while (running.get()) {
            val index = try {
                codec.dequeueOutputBuffer(info, 10_000)
            } catch (_: IllegalStateException) {
                break
            }
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue
            if (index < 0) continue
            try {
                val buffer = codec.getOutputBuffer(index)
                if (buffer != null && info.size > 0) {
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    val flags = when {
                        info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0 -> FLAG_CONFIG
                        info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0 -> FLAG_KEY_FRAME
                        else -> FLAG_FRAME
                    }
                    sendVideoFrame(buffer.slice(), info.size, info.presentationTimeUs, flags)
                }
            } catch (e: Exception) {
                if (running.get()) {
                    Log.error("[DualDeviceCastHost] Encoder drain failed: ${e.message}")
                    close()
                }
                break
            } finally {
                try {
                    codec.releaseOutputBuffer(index, false)
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun sendVideoFrame(
        data: ByteBuffer,
        size: Int,
        presentationTimeUs: Long,
        flags: Int,
    ) {
        val address = receiverAddress ?: return
        val port = receiverVideoPort
        if (port <= 0) return
        val socket = videoSocket ?: return
        val frameSequence = sequence++
        val chunkCount = ((size + MAX_PAYLOAD - 1) / MAX_PAYLOAD).coerceAtLeast(1)
        val source = data.duplicate()
        val packetBytes = ByteArray(HEADER_SIZE + MAX_PAYLOAD)
        val packetHeader = ByteBuffer.wrap(packetBytes)
        var offset = 0
        for (chunkIndex in 0 until chunkCount) {
            val payloadSize = minOf(MAX_PAYLOAD, size - offset)
            packetHeader.clear()
            packetHeader.putInt(MAGIC)
            packetHeader.putInt(frameSequence)
            packetHeader.putLong(presentationTimeUs)
            packetHeader.put(flags.toByte())
            packetHeader.putShort(chunkIndex.toShort())
            packetHeader.putShort(chunkCount.toShort())
            source.position(offset)
            source.get(packetBytes, HEADER_SIZE, payloadSize)
            val packetSize = HEADER_SIZE + payloadSize
            try {
                socket.send(DatagramPacket(packetBytes, packetSize, address, port))
            } catch (e: Exception) {
                if (running.get()) {
                    Log.error("[DualDeviceCastHost] Video send failed: ${e.message}")
                    close()
                }
                return
            }
            offset += payloadSize
        }
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) {
            return
        }
        try {
            NativeLibrary.secondarySurfaceDestroyed()
        } catch (_: Exception) {
        }
        closeEncoder()
        controlSocket.closeQuietly()
        serverSocket.closeQuietly()
        videoSocket.closeQuietly()
        executor.shutdownNow()
        activity.runOnUiThread {
            pairingDialog?.dismiss()
            pairingDialog = null
            restoreLayout()
            restoreSecondaryDisplay()
            Toast.makeText(activity, R.string.dual_device_cast_stopped, Toast.LENGTH_SHORT).show()
        }
    }

    private fun closeEncoder() {
        try {
            encoder?.stop()
        } catch (_: Exception) {
        }
        try {
            encoder?.release()
        } catch (_: Exception) {
        }
        encoder = null
        encoderSurface?.release()
        encoderSurface = null
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

    private fun createQrBitmap(contents: String, size: Int): Bitmap {
        val hints = EnumMap<EncodeHintType, Any>(EncodeHintType::class.java)
        hints[EncodeHintType.MARGIN] = 1
        val matrix = QRCodeWriter().encode(contents, BarcodeFormat.QR_CODE, size, size, hints)
        val pixels = IntArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                pixels[y * size + x] = if (matrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            }
        }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }

    private fun Closeable?.closeQuietly() {
        try {
            this?.close()
        } catch (_: Exception) {
        }
    }

    private enum class ReceiverAspectMode(val protocolValue: String) {
        FourThree("4:3"),
        SixteenNine("16:9"),
        Fill("fill");

        companion object {
            fun fromProtocolValue(value: String?): ReceiverAspectMode {
                return when (value?.lowercase(Locale.US)) {
                    "16:9", "16:0", "16_9", "widescreen" -> SixteenNine
                    "fill", "full" -> Fill
                    else -> FourThree
                }
            }
        }
    }

    private data class CastConfig(
        val width: Int,
        val height: Int,
        val aspectMode: ReceiverAspectMode,
    ) {
        val bitrate: Int
            get() {
                val basePixels = BASE_TOUCH_WIDTH * BASE_TOUCH_HEIGHT * DEFAULT_MULTIPLIER * DEFAULT_MULTIPLIER
                val scaledBitrate = BASE_BITRATE.toLong() * width * height / basePixels
                return scaledBitrate.coerceIn(MIN_BITRATE.toLong(), MAX_BITRATE.toLong()).toInt()
            }

        companion object {
            fun default(): CastConfig {
                return fromReceiverRequest(DEFAULT_MULTIPLIER.toString(), ReceiverAspectMode.FourThree.protocolValue)
            }

            fun fromReceiverRequest(multiplierValue: String?, aspectValue: String?): CastConfig {
                val multiplier = (multiplierValue?.toIntOrNull() ?: DEFAULT_MULTIPLIER)
                    .coerceIn(MIN_MULTIPLIER, MAX_MULTIPLIER)
                val aspectMode = ReceiverAspectMode.fromProtocolValue(aspectValue)
                val height = BASE_TOUCH_HEIGHT * multiplier
                val width = when (aspectMode) {
                    ReceiverAspectMode.SixteenNine -> alignEven((height * 16 + 4) / 9)
                    ReceiverAspectMode.FourThree,
                    ReceiverAspectMode.Fill -> BASE_TOUCH_WIDTH * multiplier
                }
                return CastConfig(width, height, aspectMode)
            }

            private fun alignEven(value: Int): Int {
                return if (value % 2 == 0) value else value + 1
            }
        }
    }

    companion object {
        private const val BASE_TOUCH_WIDTH = 320
        private const val BASE_TOUCH_HEIGHT = 240
        private const val DEFAULT_MULTIPLIER = 2
        private const val MIN_MULTIPLIER = 1
        private const val MAX_MULTIPLIER = 4
        private const val CAST_FPS = 60
        private const val BASE_BITRATE = 4_000_000
        private const val MIN_BITRATE = 1_500_000
        private const val MAX_BITRATE = 12_000_000
        private const val VIDEO_SOCKET_BUFFER_BYTES = 256 * 1024
        private const val MAGIC = 0x415A3244 // AZ2D
        private const val HEADER_SIZE = 21
        private const val MAX_PAYLOAD = 1180
        private const val FLAG_FRAME = 0
        private const val FLAG_KEY_FRAME = 1
        private const val FLAG_CONFIG = 2
    }
}
