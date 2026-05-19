// Copyright Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

package dev.citra2device.receiver

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.media.MediaCodec
import android.media.MediaFormat
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.google.zxing.integration.android.IntentIntegrator
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import java.io.BufferedReader
import java.io.Closeable
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : ComponentActivity(), SurfaceHolder.Callback {
    private val executor = Executors.newCachedThreadPool()
    private val running = AtomicBoolean(false)
    private lateinit var surfaceView: SurfaceView
    private lateinit var statusText: TextView
    private var decoder: MediaCodec? = null
    private var controlSocket: Socket? = null
    private var controlWriter: PrintWriter? = null
    private var udpSocket: DatagramSocket? = null
    private var pendingSession: Session? = null
    private var activeSession: Session? = null

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let { connect(Uri.parse(it)) }
    }

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                launchScanner()
            } else {
                toast(getString(R.string.connection_failed, "camera permission denied"))
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hideSystemUi()
        buildUi()
        intent?.data?.let { connect(it) }
    }

    override fun onDestroy() {
        stopSession()
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        pendingSession?.let {
            pendingSession = null
            startSession(it, holder.surface)
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        stopSession()
    }

    private fun buildUi() {
        val root = FrameLayout(this)
        surfaceView = SurfaceView(this).apply {
            holder.addCallback(this@MainActivity)
            setOnTouchListener(::onTouch)
        }
        statusText = TextView(this).apply {
            setText(R.string.waiting_for_pairing)
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0x99000000.toInt())
            textSize = 16f
            setPadding(24, 16, 24, 16)
        }
        val scanButton = Button(this).apply {
            setText(R.string.scan_qr)
            setOnClickListener { requestCameraThenScan() }
        }
        val overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(statusText)
            addView(scanButton)
        }
        root.addView(surfaceView, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        root.addView(overlay, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        setContentView(root)
    }

    private fun requestCameraThenScan() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED) {
            launchScanner()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun launchScanner() {
        val options = ScanOptions()
            .setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
            .setPrompt("")
            .setBeepEnabled(false)
            .setOrientationLocked(false)
        scanLauncher.launch(options)
    }

    private fun connect(uri: Uri) {
        val session = Session.from(uri)
        if (session == null) {
            toast(getString(R.string.connection_failed, "invalid QR"))
            return
        }
        statusText.setText(R.string.connecting)
        val surface = surfaceView.holder.surface
        if (surface?.isValid == true) {
            startSession(session, surface)
        } else {
            pendingSession = session
        }
    }

    private fun startSession(session: Session, surface: Surface) {
        stopSession()
        activeSession = session
        running.set(true)
        executor.execute { runConnection(session, surface) }
    }

    private fun runConnection(session: Session, surface: Surface) {
        try {
            val udp = DatagramSocket(0)
            udpSocket = udp
            val socket = Socket(session.host, session.controlPort)
            controlSocket = socket
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val writer = PrintWriter(socket.getOutputStream(), true)
            controlWriter = writer
            writer.println("HELLO ${session.token} ${udp.localPort}")
            val response = reader.readLine() ?: throw IllegalStateException("empty host response")
            val parts = response.split(" ")
            if (parts.size < 4 || parts[0] != "OK") {
                throw IllegalStateException(response)
            }
            val width = parts[1].toInt()
            val height = parts[2].toInt()
            startDecoder(surface, width, height)
            runOnUiThread {
                statusText.setText(R.string.connected)
                statusText.visibility = View.GONE
            }
            executor.execute { readHostControl(reader) }
            receiveVideo(udp)
        } catch (e: Exception) {
            if (running.get()) {
                runOnUiThread {
                    statusText.visibility = View.VISIBLE
                    statusText.text = getString(R.string.connection_failed, e.message ?: "unknown")
                }
            }
            stopSession()
        }
    }

    private fun startDecoder(surface: Surface, width: Int, height: Int) {
        decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
            configure(MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height), surface, null, 0)
            start()
        }
    }

    private fun receiveVideo(socket: DatagramSocket) {
        val packetBuffer = ByteArray(1500)
        val frames = ConcurrentHashMap<Int, PendingFrame>()
        while (running.get()) {
            val packet = DatagramPacket(packetBuffer, packetBuffer.size)
            socket.receive(packet)
            val frame = VideoPacket.parse(packet.data, packet.length) ?: continue
            val pending = frames.computeIfAbsent(frame.sequence) {
                PendingFrame(frame.presentationTimeUs, frame.flags, frame.chunkCount)
            }
            pending.put(frame.chunkIndex, frame.payload)
            if (pending.isComplete) {
                frames.remove(frame.sequence)
                queueFrame(pending.toByteArray(), pending.presentationTimeUs, pending.flags)
            }
            if (frames.size > 64) {
                frames.keys.minOrNull()?.let { frames.remove(it) }
            }
        }
    }

    private fun queueFrame(data: ByteArray, presentationTimeUs: Long, frameFlags: Int) {
        val codec = decoder ?: return
        val inputIndex = codec.dequeueInputBuffer(10_000)
        if (inputIndex < 0) return
        val inputBuffer = codec.getInputBuffer(inputIndex) ?: return
        inputBuffer.clear()
        inputBuffer.put(data)
        val flags = if (frameFlags == FLAG_CONFIG) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0
        codec.queueInputBuffer(inputIndex, 0, data.size, presentationTimeUs, flags)

        val info = MediaCodec.BufferInfo()
        var outputIndex = codec.dequeueOutputBuffer(info, 0)
        while (outputIndex >= 0) {
            codec.releaseOutputBuffer(outputIndex, true)
            outputIndex = codec.dequeueOutputBuffer(info, 0)
        }
    }

    private fun readHostControl(reader: BufferedReader) {
        while (running.get()) {
            if (reader.readLine() == null) break
        }
    }

    private fun onTouch(view: View, event: MotionEvent): Boolean {
        val pointerIndex = event.actionIndex
        val x = (event.getX(pointerIndex) / view.width).coerceIn(0f, 1f)
        val y = (event.getY(pointerIndex) / view.height).coerceIn(0f, 1f)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> sendTouch("down", x, y)
            MotionEvent.ACTION_MOVE -> sendTouch("move", x, y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> sendTouch("up", x, y)
            MotionEvent.ACTION_CANCEL -> sendTouch("cancel", x, y)
        }
        return true
    }

    private fun sendTouch(action: String, x: Float, y: Float) {
        controlWriter?.println("TOUCH $action $x $y")
    }

    private fun stopSession() {
        running.set(false)
        controlWriter?.println("STOP")
        controlWriter = null
        controlSocket.closeQuietly()
        controlSocket = null
        udpSocket.closeQuietly()
        udpSocket = null
        try {
            decoder?.stop()
        } catch (_: Exception) {
        }
        try {
            decoder?.release()
        } catch (_: Exception) {
        }
        decoder = null
    }

    private fun hideSystemUi() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            window.insetsController?.hide(WindowInsets.Type.systemBars())
            window.insetsController?.systemBarsBehavior =
                WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun Closeable?.closeQuietly() {
        try {
            this?.close()
        } catch (_: Exception) {
        }
    }

    private data class Session(
        val host: String,
        val controlPort: Int,
        val token: String,
    ) {
        companion object {
            fun from(uri: Uri): Session? {
                if (uri.scheme != "azahar2device" || uri.host != "join") return null
                val host = uri.getQueryParameter("host") ?: return null
                val control = uri.getQueryParameter("control")?.toIntOrNull() ?: return null
                val token = uri.getQueryParameter("token") ?: return null
                return Session(host, control, token)
            }
        }
    }

    private class PendingFrame(
        val presentationTimeUs: Long,
        val flags: Int,
        private val chunkCount: Int,
    ) {
        private val chunks = arrayOfNulls<ByteArray>(chunkCount)
        val isComplete: Boolean
            get() = chunks.all { it != null }

        fun put(index: Int, payload: ByteArray) {
            if (index in chunks.indices) {
                chunks[index] = payload
            }
        }

        fun toByteArray(): ByteArray {
            val size = chunks.sumOf { it?.size ?: 0 }
            val result = ByteArray(size)
            var offset = 0
            chunks.forEach { chunk ->
                if (chunk != null) {
                    chunk.copyInto(result, offset)
                    offset += chunk.size
                }
            }
            return result
        }
    }

    private data class VideoPacket(
        val sequence: Int,
        val presentationTimeUs: Long,
        val flags: Int,
        val chunkIndex: Int,
        val chunkCount: Int,
        val payload: ByteArray,
    ) {
        companion object {
            fun parse(data: ByteArray, length: Int): VideoPacket? {
                if (length < HEADER_SIZE) return null
                val buffer = ByteBuffer.wrap(data, 0, length)
                if (buffer.int != MAGIC) return null
                val sequence = buffer.int
                val presentationTimeUs = buffer.long
                val flags = buffer.get().toInt()
                val chunkIndex = buffer.short.toInt()
                val chunkCount = buffer.short.toInt()
                val payload = ByteArray(length - HEADER_SIZE)
                buffer.get(payload)
                return VideoPacket(sequence, presentationTimeUs, flags, chunkIndex, chunkCount, payload)
            }
        }
    }

    companion object {
        private const val MAGIC = 0x415A3244
        private const val HEADER_SIZE = 21
        private const val FLAG_CONFIG = 2
    }
}
