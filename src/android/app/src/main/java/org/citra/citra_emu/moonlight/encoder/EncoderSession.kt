package org.citra.citra_emu.moonlight.encoder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import org.citra.citra_emu.moonlight.model.StreamConfig

class EncoderSession(
    private val config: StreamConfig,
    private val encoderInfo: HardwareEncoderInfo,
    private val onFrame: (EncodedFrame) -> Unit,
    private val onError: (Throwable) -> Unit,
) {
    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var callbackThread: HandlerThread? = null
    @Volatile private var codecConfig: ByteArray = ByteArray(0)

    fun start(): Surface {
        check(codec == null) { "EncoderSession already started" }

        val callbackThread = HandlerThread("apsu-encoder-callback").also { it.start() }
        this.callbackThread = callbackThread

        val mediaCodec = MediaCodec.createByCodecName(encoderInfo.codecName)
        mediaCodec.setCallback(callback(), Handler(callbackThread.looper))

        val format = MediaFormat.createVideoFormat(encoderInfo.mime, config.width, config.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, config.bitrate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, config.iFrameIntervalSeconds)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            EncoderFrameRateConfig.applyTo(this, config.fps)
            setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1)
            if (encoderInfo.cbrSupported) {
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            }
            if (config.lowLatency && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
        }

        mediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = mediaCodec.createInputSurface()
        mediaCodec.start()

        codec = mediaCodec
        inputSurface = surface
        requestSyncFrame()
        return surface
    }

    fun requestSyncFrame() {
        val codec = codec ?: return
        runCatching {
            codec.setParameters(Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            })
        }
    }

    fun setVideoBitrate(bitrate: Int): Boolean {
        val codec = codec ?: return false
        return runCatching {
            codec.setParameters(Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bitrate.coerceAtLeast(1))
            })
            true
        }.getOrElse {
            false
        }
    }

    fun stop() {
        val mediaCodec = codec
        codec = null
        inputSurface?.release()
        inputSurface = null
        runCatching { mediaCodec?.stop() }
        runCatching { mediaCodec?.release() }
        callbackThread?.quitSafely()
        callbackThread = null
    }

    private fun callback(): MediaCodec.Callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit

        override fun onOutputBufferAvailable(
            codec: MediaCodec,
            index: Int,
            info: MediaCodec.BufferInfo,
        ) {
            try {
                val buffer = codec.getOutputBuffer(index)
                if (buffer != null && info.size > 0) {
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    val normalized = AnnexBNormalizer.from(buffer)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        codecConfig = normalized
                        return
                    }
                    val frameFlags = if (isKeyFrame(info.flags, normalized)) {
                        info.flags or MediaCodec.BUFFER_FLAG_KEY_FRAME
                    } else {
                        info.flags
                    }
                    val frameBytes = prepareFrameBytes(normalized, frameFlags)
                    onFrame(
                        EncodedFrame(
                            bytes = frameBytes,
                            presentationTimeUs = info.presentationTimeUs,
                            flags = frameFlags,
                        ),
                    )
                }
            } catch (t: Throwable) {
                onError(t)
            } finally {
                runCatching { codec.releaseOutputBuffer(index, false) }
            }
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            onError(e)
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            codecConfig = codecConfigFromFormat(format)
        }
    }

    private fun prepareFrameBytes(normalized: ByteArray, flags: Int): ByteArray {
        if ((flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) == 0 || codecConfig.isEmpty()) {
            return normalized
        }
        if (normalized.startsWith(codecConfig)) {
            return normalized
        }
        return codecConfig + normalized
    }

    private fun isKeyFrame(flags: Int, annexBBytes: ByteArray): Boolean =
        (flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0 ||
            NalUnitInspector.containsIdrNal(annexBBytes, encoderInfo.mime)

    private fun codecConfigFromFormat(format: MediaFormat): ByteArray {
        val keys = buildList {
            add("csd-0")
            add("csd-1")
            add("csd-2")
        }
        return keys.mapNotNull { key ->
            if (!format.containsKey(key)) return@mapNotNull null
            format.getByteBuffer(key)?.let { buffer ->
                val duplicate = buffer.duplicate()
                AnnexBNormalizer.from(duplicate)
            }
        }.concatenate()
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (prefix.size > size) return false
        for (i in prefix.indices) {
            if (this[i] != prefix[i]) return false
        }
        return true
    }

    private fun List<ByteArray>.concatenate(): ByteArray {
        if (isEmpty()) return ByteArray(0)
        if (size == 1) return first()
        val output = ByteArray(sumOf { it.size })
        var offset = 0
        for (bytes in this) {
            System.arraycopy(bytes, 0, output, offset, bytes.size)
            offset += bytes.size
        }
        return output
    }
}
