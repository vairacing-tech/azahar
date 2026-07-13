package org.citra.citra_emu.moonlight.server

import android.media.MediaCodec
import android.os.SystemClock
import org.citra.citra_emu.moonlight.encoder.EncodedFrame
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.min

class VideoRtpTransport(
    private val port: Int,
    private val includeFrameHeader: Boolean,
    private val onPeerReady: () -> Unit,
    private val onCongestion: () -> Unit,
    private val onLog: (String) -> Unit,
) {
    private val running = AtomicBoolean(false)
    private val pendingFrames = ArrayBlockingQueue<EncodedFrame>(MAX_PENDING_VIDEO_FRAMES)
    private val packetScratch = ByteArray(MAX_UDP_PACKET_SIZE)
    private val timestampTracker = RtpTimestampTracker()
    private var socket: DatagramSocket? = null
    @Volatile private var peer: InetSocketAddress? = null
    private var receiveThread: Thread? = null
    private var senderThread: Thread? = null
    private var rtpSequence = 1
    private var streamPacketIndex = 0
    private var frameIndex = 1
    private var sentFrames = 0L
    private var droppedFrames = 0L
    private var lastCongestionSignalMs = 0L
    @Volatile private var packetSize = DEFAULT_GAMESTREAM_PACKET_SIZE
    @Volatile private var frameRate = DEFAULT_FRAME_RATE

    fun start() {
        if (!running.compareAndSet(false, true)) return
        rtpSequence = 1
        streamPacketIndex = 0
        frameIndex = 1
        sentFrames = 0L
        droppedFrames = 0L
        lastCongestionSignalMs = 0L
        pendingFrames.clear()
        timestampTracker.reset()
        socket = DatagramSocket(port, InetAddress.getByName("0.0.0.0")).also {
            it.soTimeout = 1_000
            it.sendBufferSize = 1024 * 1024
        }
        senderThread = thread(name = "apsu-video-rtp-sender", isDaemon = true) {
            while (running.get()) {
                val frame = runCatching {
                    pendingFrames.poll(SENDER_POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                }.getOrNull() ?: continue
                runCatching { sendFrameNow(frame) }
                    .onFailure { throwable ->
                        if (running.get()) {
                            onLog("Video RTP send error: ${throwable.message}")
                        }
                    }
            }
        }
        receiveThread = thread(name = "apsu-video-udp", isDaemon = true) {
            val buffer = ByteArray(2048)
            while (running.get()) {
                val packet = DatagramPacket(buffer, buffer.size)
                val received = runCatching {
                    socket?.receive(packet)
                    packet
                }.getOrNull() ?: continue

                val nextPeer = InetSocketAddress(received.address, received.port)
                ClientConnectionState.mark("Video RTP", received.address.hostAddress)
                if (peer != nextPeer) {
                    peer = nextPeer
                    pendingFrames.clear()
                    onLog("Video UDP peer ${nextPeer.address.hostAddress}:${nextPeer.port}")
                    onPeerReady()
                }
            }
        }
        onLog("Video RTP listening on UDP $port")
    }

    fun stop() {
        running.set(false)
        runCatching { socket?.close() }
        socket = null
        peer = null
        pendingFrames.clear()
        rtpSequence = 1
        streamPacketIndex = 0
        frameIndex = 1
        sentFrames = 0L
        droppedFrames = 0L
        timestampTracker.reset()
        receiveThread = null
        senderThread = null
    }

    fun setPacketSize(requestedPacketSize: Int) {
        val nextPacketSize = requestedPacketSize.coerceIn(MIN_GAMESTREAM_PACKET_SIZE, MAX_GAMESTREAM_PACKET_SIZE)
        if (packetSize != nextPacketSize) {
            packetSize = nextPacketSize
            onLog("Video packet size set to $nextPacketSize")
        }
    }

    fun setFrameRate(fps: Int) {
        val nextFrameRate = fps.coerceIn(1, 240)
        if (frameRate != nextFrameRate) {
            frameRate = nextFrameRate
            onLog("Video RTP timestamp rate set for ${nextFrameRate}fps")
        }
    }

    fun sendFrame(frame: EncodedFrame) {
        if (frame.bytes.isEmpty()) return
        if (!running.get() || socket == null || peer == null) return
        val keyFrame = (frame.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
        if (keyFrame) {
            val dropped = pendingFrames.size
            pendingFrames.clear()
            if (dropped > 0) {
                recordCongestion(dropped, "keyframe replaced queued frames")
            }
        }
        if (pendingFrames.offer(frame)) return

        var dropped = 0
        while (!pendingFrames.offer(frame)) {
            if (pendingFrames.poll() == null) {
                dropped++
                break
            }
            dropped++
        }
        recordCongestion(dropped, "sender queue full")
    }

    private fun sendFrameNow(frame: EncodedFrame) {
        val activeSocket = socket ?: return
        val activePeer = peer ?: return

        val frameType = if ((frame.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) 2 else 1
        val payloadSize = frame.bytes.size + if (includeFrameHeader) FRAME_HEADER_SIZE else 0
        val payloadUnitSize = (packetSize - NV_VIDEO_HEADER_SIZE).coerceAtLeast(1)
        val dataPackets = (payloadSize + payloadUnitSize - 1) / payloadUnitSize
        if (dataPackets <= 0 || dataPackets > 1023) {
            onLog("Dropping oversized encoded frame: $payloadSize bytes, $dataPackets packets")
            return
        }

        val currentFrame = frameIndex++
        val timestamp = timestampTracker.timestampFor(frame.presentationTimeUs)
        val datagram = DatagramPacket(packetScratch, packetScratch.size, activePeer.address, activePeer.port)
        var offset = 0
        for (packetIndex in 0 until dataPackets) {
            val chunkLength = min(payloadUnitSize, payloadSize - offset)
            val sequence = nextRtpSequence()
            val currentStreamPacketIndex = nextStreamPacketIndex()
            val flags = FLAG_CONTAINS_PIC_DATA or
                (if (packetIndex == 0) FLAG_SOF else 0) or
                (if (packetIndex == dataPackets - 1) FLAG_EOF else 0)
            val datagramLength = buildPacket(
                sequence = sequence,
                streamPacketIndex = currentStreamPacketIndex,
                timestamp = timestamp,
                frameIndex = currentFrame,
                packetIndex = packetIndex,
                dataPackets = dataPackets,
                flags = flags,
                frameType = frameType,
                encodedFrame = frame.bytes,
                payloadOffset = offset,
                payloadLength = chunkLength,
            )
            datagram.setLength(datagramLength)
            activeSocket.send(datagram)
            offset += chunkLength
        }
        sentFrames++
        if (sentFrames == 1L || sentFrames % SENT_FRAME_LOG_INTERVAL == 0L) {
            onLog(
                "Sent video frame $sentFrames as $dataPackets UDP packets to " +
                    "${activePeer.address.hostAddress}:${activePeer.port}; " +
                    "queue=${pendingFrames.size}, dropped=$droppedFrames",
            )
        }
    }

    private fun nextRtpSequence(): Int {
        val sequence = rtpSequence and 0xFFFF
        rtpSequence = (rtpSequence + 1) and 0xFFFF
        return sequence
    }

    private fun nextStreamPacketIndex(): Int {
        val index = streamPacketIndex and STREAM_PACKET_INDEX_MASK
        streamPacketIndex = (streamPacketIndex + 1) and STREAM_PACKET_INDEX_MASK
        return index
    }

    private fun buildPacket(
        sequence: Int,
        streamPacketIndex: Int,
        timestamp: Int,
        frameIndex: Int,
        packetIndex: Int,
        dataPackets: Int,
        flags: Int,
        frameType: Int,
        encodedFrame: ByteArray,
        payloadOffset: Int,
        payloadLength: Int,
    ): Int {
        packetScratch[0] = 0x90.toByte()
        packetScratch[1] = 96.toByte()
        putShortBE(packetScratch, 2, sequence)
        putIntBE(packetScratch, 4, timestamp)
        putIntBE(packetScratch, 8, 0)
        putIntBE(packetScratch, 12, 0)

        putIntLE(packetScratch, 16, NvVideoPacketHeader.encodeStreamPacketIndex(streamPacketIndex))
        putIntLE(packetScratch, 20, frameIndex)
        packetScratch[24] = flags.toByte()
        packetScratch[25] = 0
        packetScratch[26] = 0x10
        packetScratch[27] = 0
        putIntLE(packetScratch, 28, (packetIndex shl 12) or (dataPackets shl 22))
        copyPayloadChunk(
            target = packetScratch,
            targetOffset = PACKET_HEADER_SIZE,
            frameType = frameType,
            encodedFrame = encodedFrame,
            payloadOffset = payloadOffset,
            payloadLength = payloadLength,
        )
        return PACKET_HEADER_SIZE + payloadLength
    }

    private fun copyPayloadChunk(
        target: ByteArray,
        targetOffset: Int,
        frameType: Int,
        encodedFrame: ByteArray,
        payloadOffset: Int,
        payloadLength: Int,
    ) {
        var remaining = payloadLength
        var sourceOffset = payloadOffset
        var writeOffset = targetOffset
        if (includeFrameHeader && sourceOffset < FRAME_HEADER_SIZE) {
            val headerBytes = min(remaining, FRAME_HEADER_SIZE - sourceOffset)
            for (index in 0 until headerBytes) {
                target[writeOffset + index] = frameHeaderByte(frameType, sourceOffset + index)
            }
            writeOffset += headerBytes
            sourceOffset += headerBytes
            remaining -= headerBytes
        }
        if (remaining <= 0) return
        val encodedOffset = if (includeFrameHeader) sourceOffset - FRAME_HEADER_SIZE else sourceOffset
        System.arraycopy(encodedFrame, encodedOffset, target, writeOffset, remaining)
    }

    private fun frameHeaderByte(frameType: Int, offset: Int): Byte =
        when (offset) {
            0 -> 0x01.toByte()
            3 -> frameType.toByte()
            else -> 0x00.toByte()
        }

    private fun recordCongestion(dropped: Int, reason: String) {
        if (dropped <= 0) return
        droppedFrames += dropped.toLong()
        val now = SystemClock.elapsedRealtime()
        if (now - lastCongestionSignalMs < CONGESTION_SIGNAL_INTERVAL_MS) return
        lastCongestionSignalMs = now
        onLog(
            "Video RTP congestion: dropped $dropped frame(s), total=$droppedFrames, " +
                "queue=${pendingFrames.size}, reason=$reason; requesting IDR",
        )
        onCongestion()
    }

    private fun putShortBE(target: ByteArray, offset: Int, value: Int) {
        target[offset] = ((value ushr 8) and 0xFF).toByte()
        target[offset + 1] = (value and 0xFF).toByte()
    }

    private fun putIntBE(target: ByteArray, offset: Int, value: Int) {
        target[offset] = ((value ushr 24) and 0xFF).toByte()
        target[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        target[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        target[offset + 3] = (value and 0xFF).toByte()
    }

    private fun putIntLE(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value and 0xFF).toByte()
        target[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        target[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        target[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }

    companion object {
        private const val RTP_HEADER_SIZE = 12
        private const val RTP_EXTENSION_SIZE = 4
        private const val NV_VIDEO_HEADER_SIZE = 16
        private const val PACKET_HEADER_SIZE = RTP_HEADER_SIZE + RTP_EXTENSION_SIZE + NV_VIDEO_HEADER_SIZE
        private const val DEFAULT_GAMESTREAM_PACKET_SIZE = 1024
        private const val MIN_GAMESTREAM_PACKET_SIZE = 512
        private const val MAX_GAMESTREAM_PACKET_SIZE = 1392
        private const val MAX_UDP_PACKET_SIZE = 1500
        private const val FRAME_HEADER_SIZE = 8
        private const val SENT_FRAME_LOG_INTERVAL = 300L
        private const val STREAM_PACKET_INDEX_MASK = 0x00FF_FFFF
        private const val DEFAULT_FRAME_RATE = 60
        private const val MAX_PENDING_VIDEO_FRAMES = 2
        private const val SENDER_POLL_TIMEOUT_MS = 100L
        private const val CONGESTION_SIGNAL_INTERVAL_MS = 1_000L

        private const val FLAG_CONTAINS_PIC_DATA = 0x01
        private const val FLAG_EOF = 0x02
        private const val FLAG_SOF = 0x04
    }
}

internal object NvVideoPacketHeader {
    private const val STREAM_PACKET_INDEX_MASK = 0x00FF_FFFF

    fun encodeStreamPacketIndex(index: Int): Int =
        (index and STREAM_PACKET_INDEX_MASK) shl 8
}

internal class RtpTimestampTracker {
    private var basePresentationTimeUs: Long? = null
    private var lastTimestamp = -1L

    fun timestampFor(presentationTimeUs: Long): Int {
        val base = basePresentationTimeUs ?: presentationTimeUs.also {
            basePresentationTimeUs = it
        }
        val elapsedUs = (presentationTimeUs - base).coerceAtLeast(0L)
        val ptsTimestamp =
            (elapsedUs * RTP_CLOCK_RATE + TIMESTAMP_ROUNDING_OFFSET) / MICROSECONDS_PER_SECOND
        val nextTimestamp = ptsTimestamp.coerceAtLeast(lastTimestamp + 1L)
        lastTimestamp = nextTimestamp
        return nextTimestamp.toInt()
    }

    fun reset() {
        basePresentationTimeUs = null
        lastTimestamp = -1L
    }

    private companion object {
        const val RTP_CLOCK_RATE = 90_000L
        const val MICROSECONDS_PER_SECOND = 1_000_000L
        const val TIMESTAMP_ROUNDING_OFFSET = MICROSECONDS_PER_SECOND / 2L
    }
}
