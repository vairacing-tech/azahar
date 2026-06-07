package org.citra.citra_emu.moonlight.audio

import io.github.jaredmdobson.concentus.OpusApplication
import io.github.jaredmdobson.concentus.OpusEncoder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.min

class OpusEncoderSession(
    private val bitrate: Int = DEFAULT_BITRATE,
    private val onPacket: (ByteArray, Int) -> Unit,
    private val onLog: (String) -> Unit,
    private val onError: (Throwable) -> Unit,
) {
    private val running = AtomicBoolean(false)
    private val frames = ArrayBlockingQueue<ByteArray>(MAX_QUEUED_FRAMES)
    private val chunkLock = Any()
    private val staging = ByteArray(FRAME_BYTES)
    private var stagingOffset = 0
    private var encoder: OpusEncoder? = null
    private var workerThread: Thread? = null
    private var droppedFrames = 0L
    private var capturedFrames = 0L
    private var nonSilentFrames = 0L
    private var loggedSilentCapture = false
    private var loggedNonSilentCapture = false
    private var loggedFirstPacket = false

    @Volatile var encoderName: String? = null
        private set

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val opusEncoder = OpusEncoder(SAMPLE_RATE, CHANNEL_COUNT, OpusApplication.OPUS_APPLICATION_RESTRICTED_LOWDELAY).apply {
            setBitrate(bitrate)
            setUseVBR(false)
            setUseInbandFEC(false)
            setComplexity(OPUS_COMPLEXITY)
        }
        encoder = opusEncoder
        encoderName = ENCODER_NAME

        workerThread = thread(name = "apsu-opus-encoder", isDaemon = true) {
            runEncoder(opusEncoder)
        }
        onLog("Opus audio encoder $ENCODER_NAME started at ${bitrate / 1000} Kbps, ${PACKET_DURATION_MS}ms frames")
    }

    fun queuePcm(pcm: ByteArray) {
        if (!running.get() || pcm.isEmpty()) return
        synchronized(chunkLock) {
            var offset = 0
            while (offset < pcm.size) {
                val copyLength = min(FRAME_BYTES - stagingOffset, pcm.size - offset)
                System.arraycopy(pcm, offset, staging, stagingOffset, copyLength)
                stagingOffset += copyLength
                offset += copyLength

                if (stagingOffset == FRAME_BYTES) {
                    offerFrame(staging.copyOf())
                    stagingOffset = 0
                }
            }
        }
    }

    fun stop() {
        running.set(false)
        val worker = workerThread
        if (Thread.currentThread() != worker) {
            worker?.interrupt()
            runCatching { worker?.join(1_000) }
        }
        workerThread = null
        encoder = null
        encoderName = null
        frames.clear()
        synchronized(chunkLock) {
            stagingOffset = 0
        }
    }

    private fun offerFrame(frame: ByteArray) {
        observePcmFrame(frame)
        if (frames.offer(frame)) return
        frames.poll()
        if (frames.offer(frame)) {
            droppedFrames++
            if (droppedFrames == 1L || droppedFrames % DROPPED_FRAME_LOG_INTERVAL == 0L) {
                onLog("Dropped $droppedFrames stale PCM audio frames to keep latency low")
            }
        }
    }

    private fun observePcmFrame(frame: ByteArray) {
        capturedFrames++
        val peak = pcmPeak(frame)
        if (peak > PCM_NON_SILENT_PEAK) {
            nonSilentFrames++
            if (!loggedNonSilentCapture) {
                loggedNonSilentCapture = true
                onLog("Audio capture has non-silent PCM, peak=$peak")
            }
        } else if (!loggedSilentCapture && capturedFrames >= SILENT_CAPTURE_LOG_FRAME_COUNT) {
            loggedSilentCapture = true
            onLog("Audio capture still silent after ${SILENT_CAPTURE_LOG_FRAME_COUNT * PACKET_DURATION_MS}ms; source app may block playback capture")
        }
    }

    private fun runEncoder(opusEncoder: OpusEncoder) {
        val pcmShorts = ShortArray(FRAME_SAMPLES_PER_CHANNEL * CHANNEL_COUNT)
        val output = ByteArray(MAX_OPUS_PACKET_BYTES)
        try {
            while (running.get() || frames.isNotEmpty()) {
                val frame = frames.poll(10, TimeUnit.MILLISECONDS)
                if (frame != null) {
                    littleEndianPcmToShorts(frame, pcmShorts)
                    val encodedLength = opusEncoder.encode(
                        pcmShorts,
                        0,
                        FRAME_SAMPLES_PER_CHANNEL,
                        output,
                        0,
                        output.size,
                    )
                    if (encodedLength > 0) {
                        if (!loggedFirstPacket) {
                            loggedFirstPacket = true
                            onLog("Encoded first ${PACKET_DURATION_MS}ms Opus packet, $encodedLength bytes")
                        }
                        onPacket(output.copyOf(encodedLength), PACKET_DURATION_MS)
                    }
                }
            }
        } catch (t: Throwable) {
            if (running.get()) {
                onError(t)
            }
        }
    }

    companion object {
        const val SAMPLE_RATE = 48_000
        const val CHANNEL_COUNT = 2
        const val PACKET_DURATION_MS = 5
        const val PCM_BYTES_PER_SAMPLE = 2
        const val FRAME_SAMPLES_PER_CHANNEL = SAMPLE_RATE * PACKET_DURATION_MS / 1_000
        const val FRAME_BYTES = FRAME_SAMPLES_PER_CHANNEL * CHANNEL_COUNT * PCM_BYTES_PER_SAMPLE
        const val ENCODER_NAME = "Concentus Opus"
        private const val DEFAULT_BITRATE = 128_000
        private const val MAX_QUEUED_FRAMES = 12
        private const val MAX_OPUS_PACKET_BYTES = 1_275
        private const val DROPPED_FRAME_LOG_INTERVAL = 100L
        private const val OPUS_COMPLEXITY = 5
        private const val PCM_NON_SILENT_PEAK = 128
        private const val SILENT_CAPTURE_LOG_FRAME_COUNT = 400L

        fun encoderDescription(): String = "$ENCODER_NAME ${PACKET_DURATION_MS}ms"

        private fun littleEndianPcmToShorts(frame: ByteArray, output: ShortArray) {
            var inputOffset = 0
            for (index in output.indices) {
                val low = frame[inputOffset].toInt() and 0xFF
                val high = frame[inputOffset + 1].toInt()
                output[index] = ((high shl 8) or low).toShort()
                inputOffset += PCM_BYTES_PER_SAMPLE
            }
        }

        private fun pcmPeak(frame: ByteArray): Int {
            var peak = 0
            var index = 0
            while (index + 1 < frame.size) {
                val low = frame[index].toInt() and 0xFF
                val high = frame[index + 1].toInt()
                val sample = ((high shl 8) or low).toShort().toInt()
                val absolute = kotlin.math.abs(sample)
                if (absolute > peak) peak = absolute
                index += PCM_BYTES_PER_SAMPLE
            }
            return peak
        }
    }
}
