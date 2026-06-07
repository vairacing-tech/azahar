package org.citra.citra_emu.moonlight.server

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class AudioRtpTransport(
    private val port: Int,
    private val onLog: (String) -> Unit,
) {
    private val running = AtomicBoolean(false)
    private var socket: DatagramSocket? = null
    @Volatile private var peer: InetSocketAddress? = null
    private var receiveThread: Thread? = null
    private var rtpSequence = 0
    private var timestamp = 0
    private var sentPackets = 0L

    fun start() {
        if (!running.compareAndSet(false, true)) return
        rtpSequence = 0
        timestamp = 0
        sentPackets = 0L
        socket = DatagramSocket(port, InetAddress.getByName("0.0.0.0")).also {
            it.soTimeout = 1_000
            it.sendBufferSize = 256 * 1024
        }
        receiveThread = thread(name = "apsu-audio-udp", isDaemon = true) {
            val buffer = ByteArray(512)
            while (running.get()) {
                val packet = DatagramPacket(buffer, buffer.size)
                val received = runCatching {
                    socket?.receive(packet)
                    packet
                }.getOrNull() ?: continue

                val nextPeer = InetSocketAddress(received.address, received.port)
                ClientConnectionState.mark("Audio RTP", received.address.hostAddress)
                if (peer != nextPeer) {
                    peer = nextPeer
                    onLog("Audio UDP peer ${nextPeer.address.hostAddress}:${nextPeer.port}")
                }
            }
        }
        onLog("Audio RTP listening on UDP $port")
    }

    fun stop() {
        running.set(false)
        runCatching { socket?.close() }
        socket = null
        peer = null
        receiveThread = null
        rtpSequence = 0
        timestamp = 0
        sentPackets = 0L
    }

    fun sendOpusPacket(payload: ByteArray, durationMillis: Int) {
        val activeSocket = socket ?: return
        val activePeer = peer ?: return
        if (payload.isEmpty()) return

        val packet = AudioRtpPacket.build(
            payload = payload,
            sequence = nextRtpSequence(),
            timestamp = timestamp,
            ssrc = AUDIO_SSRC,
        )
        activeSocket.send(DatagramPacket(packet, packet.size, activePeer.address, activePeer.port))
        timestamp += durationMillis.coerceAtLeast(1) * (AUDIO_CLOCK_HZ / 1_000)
        sentPackets++
        if (sentPackets == 1L || sentPackets % SENT_PACKET_LOG_INTERVAL == 0L) {
            onLog("Sent audio packet $sentPackets, ${payload.size} bytes to ${activePeer.address.hostAddress}:${activePeer.port}")
        }
    }

    private fun nextRtpSequence(): Int {
        val sequence = rtpSequence and 0xFFFF
        rtpSequence = (rtpSequence + 1) and 0xFFFF
        return sequence
    }

    companion object {
        private const val AUDIO_SSRC = 0x4150_5355
        private const val AUDIO_CLOCK_HZ = 48_000
        private const val SENT_PACKET_LOG_INTERVAL = 500L
    }
}

internal object AudioRtpPacket {
    private const val RTP_HEADER_SIZE = 12
    private const val RTP_VERSION_2 = 0x80
    private const val RTP_PAYLOAD_TYPE_OPUS = 97

    fun build(payload: ByteArray, sequence: Int, timestamp: Int, ssrc: Int): ByteArray =
        ByteBuffer.allocate(RTP_HEADER_SIZE + payload.size)
            .order(ByteOrder.BIG_ENDIAN)
            .put(RTP_VERSION_2.toByte())
            .put(RTP_PAYLOAD_TYPE_OPUS.toByte())
            .putShort((sequence and 0xFFFF).toShort())
            .putInt(timestamp)
            .putInt(ssrc)
            .put(payload)
            .array()
}
