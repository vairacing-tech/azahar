// Copyright Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

package dev.azahar.secondscreen

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Spinner
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
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : ComponentActivity(), SurfaceHolder.Callback {
    private val executor = Executors.newCachedThreadPool()
    private val controlExecutor = Executors.newSingleThreadExecutor()
    private val controlWriteLock = Any()
    private val running = AtomicBoolean(false)
    private val preferences: SharedPreferences by lazy {
        getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    }
    private lateinit var surfaceView: AspectSurfaceView
    private lateinit var overlayView: LinearLayout
    private lateinit var statusText: TextView
    private var selectedResolution = ResolutionMultiplier.X2
    private var selectedAspectMode = AspectMode.FourThree
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
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        loadSettings()
        buildUi()
        surfaceView.post { hideSystemUi() }
        intent?.data?.let { connect(it) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.data?.let { connect(it) }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemUi()
        }
    }

    override fun onDestroy() {
        stopSession()
        executor.shutdownNow()
        controlExecutor.shutdownNow()
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
        val root = FrameLayout(this).apply {
            setBackgroundColor(0xFF000000.toInt())
        }
        surfaceView = AspectSurfaceView(this).apply {
            aspectMode = selectedAspectMode
            holder.addCallback(this@MainActivity)
            keepScreenOn = true
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
        val resolutionLabel = settingsLabel(R.string.resolution_multiplier)
        val resolutionSpinner = settingsSpinner(
            ResolutionMultiplier.entries.map { it.label },
            ResolutionMultiplier.entries.indexOf(selectedResolution)
        ) { position ->
            selectedResolution = ResolutionMultiplier.entries[position]
            saveSettings()
        }
        val aspectLabel = settingsLabel(R.string.aspect_mode)
        val aspectSpinner = settingsSpinner(
            AspectMode.entries.map { it.label },
            AspectMode.entries.indexOf(selectedAspectMode)
        ) { position ->
            selectedAspectMode = AspectMode.entries[position]
            surfaceView.aspectMode = selectedAspectMode
            saveSettings()
        }
        overlayView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
            addView(statusText)
            addView(resolutionLabel)
            addView(resolutionSpinner)
            addView(aspectLabel)
            addView(aspectSpinner)
            addView(scanButton)
        }
        root.addView(
            surfaceView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
        )
        root.addView(overlayView, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        setContentView(root)
    }

    private fun settingsLabel(textResId: Int): TextView {
        return TextView(this).apply {
            setText(textResId)
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 14f
            setPadding(0, 18, 0, 4)
        }
    }

    private fun settingsSpinner(
        entries: List<String>,
        selectedPosition: Int,
        onSelected: (Int) -> Unit
    ): Spinner {
        val spinnerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, entries).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        return Spinner(this).apply {
            adapter = spinnerAdapter
            setSelection(selectedPosition.coerceIn(entries.indices), false)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    onSelected(position)
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
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
        try {
            val options = ScanOptions()
                .setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
                .setPrompt("")
                .setBeepEnabled(false)
                .setOrientationLocked(false)
            scanLauncher.launch(options)
        } catch (e: Exception) {
            toast(getString(R.string.connection_failed, e.message ?: "scanner unavailable"))
        }
    }

    private fun connect(uri: Uri) {
        val session = try {
            Session.from(uri)
        } catch (_: Exception) {
            null
        }
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
            val udp = DatagramSocket(0).apply {
                receiveBufferSize = VIDEO_SOCKET_BUFFER_BYTES
            }
            udpSocket = udp
            val socket = Socket(session.host, session.controlPort)
            controlSocket = socket
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val writer = PrintWriter(socket.getOutputStream(), true)
            controlWriter = writer
            writer.println(
                "HELLO ${session.token} ${udp.localPort} " +
                    "${selectedResolution.multiplier} ${selectedAspectMode.protocolValue}"
            )
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
                overlayView.visibility = View.GONE
            }
            executor.execute { readHostControl(reader) }
            receiveVideo(udp)
        } catch (e: Exception) {
            if (running.get()) {
                runOnUiThread {
                    overlayView.visibility = View.VISIBLE
                    statusText.visibility = View.VISIBLE
                    statusText.text = getString(R.string.connection_failed, e.message ?: "unknown")
                }
            }
            stopSession()
        }
    }

    private fun startDecoder(surface: Surface, width: Int, height: Int) {
        val decoderInfo = selectHardwareAvcDecoder(width, height)
        decoder = createConfiguredDecoder(decoderInfo, surface, width, height).apply {
            start()
            applyLowLatencyRuntimeParameters(this)
        }
    }

    private fun selectHardwareAvcDecoder(width: Int, height: Int): MediaCodecInfo {
        val candidates = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter {
                !it.isEncoder &&
                    it.supportedTypes.any { type -> type.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) } &&
                    it.isHardwareCodec() &&
                    !it.isSecureOrTunneledCodec() &&
                    it.supportsAvcSize(width, height, CAST_FPS)
            }
            .sortedWith(
                compareByDescending<MediaCodecInfo> { it.preferenceScore() }
                    .thenBy { it.name }
            )

        val decoderInfo = candidates.firstOrNull()
            ?: throw IllegalStateException("No hardware AVC decoder")
        val vendor = if (decoderInfo.isQualcommCodec()) "Qualcomm" else "hardware"
        Log.i(TAG, "Using $vendor decoder ${decoderInfo.name} ${width}x${height}@$CAST_FPS")
        return decoderInfo
    }

    private fun createConfiguredDecoder(
        decoderInfo: MediaCodecInfo,
        surface: Surface,
        width: Int,
        height: Int,
    ): MediaCodec {
        return tryCreateConfiguredDecoder(decoderInfo, surface, width, height, tuned = true)
            ?: tryCreateConfiguredDecoder(decoderInfo, surface, width, height, tuned = false)
            ?: throw IllegalStateException("Unable to configure hardware AVC decoder ${decoderInfo.name}")
    }

    private fun tryCreateConfiguredDecoder(
        decoderInfo: MediaCodecInfo,
        surface: Surface,
        width: Int,
        height: Int,
        tuned: Boolean,
    ): MediaCodec? {
        var codec: MediaCodec? = null
        return try {
            codec = MediaCodec.createByCodecName(decoderInfo.name)
            codec.configure(createDecoderFormat(width, height, tuned), surface, null, 0)
            codec
        } catch (e: Exception) {
            try {
                codec?.release()
            } catch (_: Exception) {
            }
            if (tuned) {
                Log.w(TAG, "Tuned decoder config failed, retrying baseline", e)
            }
            null
        }
    }

    private fun createDecoderFormat(width: Int, height: Int, tuned: Boolean): MediaFormat {
        return MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                setInteger(MediaFormat.KEY_OPERATING_RATE, CAST_FPS)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                setInteger(MediaFormat.KEY_LATENCY, 0)
            }
            if (tuned) {
                setInteger("low-latency", 1)
                setInteger("vendor.qti-ext-dec-low-latency.enable", 1)
            }
        }
    }

    private fun applyLowLatencyRuntimeParameters(codec: MediaCodec) {
        try {
            codec.setParameters(Bundle().apply {
                putInt("low-latency", 1)
                putInt("vendor.qti-ext-dec-low-latency.enable", 1)
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
            while (frames.size > MAX_PENDING_FRAMES) {
                frames.keys.minOrNull()?.let { frames.remove(it) }
            }
        }
    }

    private fun queueFrame(data: ByteArray, presentationTimeUs: Long, frameFlags: Int) {
        val codec = decoder ?: return
        try {
            val inputIndex = codec.dequeueInputBuffer(DECODER_INPUT_TIMEOUT_US)
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
        } catch (_: IllegalStateException) {
            stopSession()
        }
    }

    private fun readHostControl(reader: BufferedReader) {
        try {
            while (running.get()) {
                if (reader.readLine() == null) break
            }
        } catch (_: Exception) {
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
        sendControlLine("TOUCH $action $x $y")
    }

    private fun sendControlLine(line: String) {
        val writer = controlWriter ?: return
        try {
            controlExecutor.execute {
                try {
                    synchronized(controlWriteLock) {
                        writer.println(line)
                    }
                } catch (_: Exception) {
                }
            }
        } catch (_: RejectedExecutionException) {
        }
    }

    private fun stopSession() {
        running.set(false)
        sendControlLine("STOP")
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
            val controller = window.decorView.windowInsetsController ?: return
            controller.hide(WindowInsets.Type.systemBars())
            controller.systemBarsBehavior =
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

    private fun loadSettings() {
        val multiplier = preferences.getInt(KEY_RESOLUTION_MULTIPLIER, ResolutionMultiplier.X2.multiplier)
        selectedResolution = ResolutionMultiplier.fromMultiplier(multiplier)
        val aspect = preferences.getString(KEY_ASPECT_MODE, AspectMode.FourThree.protocolValue)
        selectedAspectMode = AspectMode.fromProtocolValue(aspect)
    }

    private fun saveSettings() {
        preferences.edit()
            .putInt(KEY_RESOLUTION_MULTIPLIER, selectedResolution.multiplier)
            .putString(KEY_ASPECT_MODE, selectedAspectMode.protocolValue)
            .apply()
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

    private enum class ResolutionMultiplier(val multiplier: Int, val label: String) {
        X1(1, "1x - 320x240"),
        X2(2, "2x - 640x480"),
        X3(3, "3x - 960x720"),
        X4(4, "4x - 1280x960");

        companion object {
            fun fromMultiplier(multiplier: Int): ResolutionMultiplier {
                return entries.firstOrNull { it.multiplier == multiplier } ?: X2
            }
        }
    }

    private enum class AspectMode(
        val protocolValue: String,
        val label: String,
        val displayAspectRatio: Float?,
    ) {
        FourThree("4:3", "4:3", 4f / 3f),
        SixteenNine("16:9", "16:9", 16f / 9f),
        Fill("fill", "Fill", null);

        companion object {
            fun fromProtocolValue(value: String?): AspectMode {
                return entries.firstOrNull { it.protocolValue.equals(value, ignoreCase = true) }
                    ?: FourThree
            }
        }
    }

    companion object {
        private const val TAG = "AzaharSecondScreen"
        private const val PREFERENCES_NAME = "azahar_second_screen"
        private const val KEY_RESOLUTION_MULTIPLIER = "resolution_multiplier"
        private const val KEY_ASPECT_MODE = "aspect_mode"
        private const val CAST_FPS = 60
        private const val MAX_PENDING_FRAMES = 8
        private const val DECODER_INPUT_TIMEOUT_US = 1_000L
        private const val VIDEO_SOCKET_BUFFER_BYTES = 256 * 1024
        private const val MAGIC = 0x415A3244
        private const val HEADER_SIZE = 21
        private const val FLAG_CONFIG = 2
    }

    private class AspectSurfaceView(context: android.content.Context) : SurfaceView(context) {
        var aspectMode: AspectMode = AspectMode.FourThree
            set(value) {
                field = value
                requestLayout()
            }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val targetAspectRatio = aspectMode.displayAspectRatio
            if (targetAspectRatio == null) {
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
                return
            }

            val availableWidth = MeasureSpec.getSize(widthMeasureSpec)
            val availableHeight = MeasureSpec.getSize(heightMeasureSpec)
            if (availableWidth == 0 || availableHeight == 0) {
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
                return
            }

            val availableAspect = availableWidth.toFloat() / availableHeight.toFloat()
            val measuredWidth: Int
            val measuredHeight: Int
            if (availableAspect > targetAspectRatio) {
                measuredHeight = availableHeight
                measuredWidth = (availableHeight * targetAspectRatio).toInt()
            } else {
                measuredWidth = availableWidth
                measuredHeight = (availableWidth / targetAspectRatio).toInt()
            }
            setMeasuredDimension(measuredWidth, measuredHeight)
        }
    }
}
