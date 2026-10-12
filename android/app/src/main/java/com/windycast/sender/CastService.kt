package com.windycast.sender

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.view.Display
import java.util.concurrent.Executors
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Surface
import android.view.WindowManager
import androidx.core.content.ContextCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.roundToInt

class CastService : Service() {
    companion object {
        const val ACTION_START = "com.windycast.sender.START"
        const val ACTION_STOP = "com.windycast.sender.STOP"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_TOKEN = "token"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        const val EXTRA_AUDIO = "audio"
        const val EXTRA_PROFILE = "profile"
        private const val CHANNEL_ID = "windycast_screen"
    }

    private val handler = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder().pingInterval(15, TimeUnit.SECONDS).build()
    private var socket: WebSocket? = null
    private var projection: MediaProjection? = null
    @Volatile private var encoder: MediaCodec? = null
    private var display: VirtualDisplay? = null
    private var inputSurface: Surface? = null
    private var recorder: AudioRecord? = null
    private var videoThread: Thread? = null
    private var audioThread: Thread? = null
    @Volatile private var streaming = false
    @Volatile private var activeWidth = 0
    @Volatile private var activeHeight = 0
    // Android 14+ app-only captures can have a different orientation from the
    // physical display; captured content dimensions take priority when known.
    @Volatile private var capturedContentSize: Pair<Int, Int>? = null
    @Volatile private var reconfiguring = false
    private var presetChoice = 0
    private var captureDensityDpi = 0
    private val rotationExecutor = Executors.newSingleThreadExecutor()
    private val displayManager by lazy { getSystemService(Context.DISPLAY_SERVICE) as DisplayManager }
    private var rotationPollActive = false
    private val rotationPoll = object : Runnable {
        override fun run() {
            if (!rotationPollActive || !streaming) return
            scheduleDisplayCheck()
            handler.postDelayed(this, 900L)
        }
    }
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) {
                handler.postDelayed({ scheduleDisplayCheck() }, 300L)
            }
        }
    }
    @Volatile private var waitingForVideoKeyframe = false
    private var lastKeyframeRequestMs = 0L
    private var videoEncodedThisSecond = 0
    private var videoSentThisSecond = 0
    private var videoDroppedThisSecond = 0
    private var statsClockMs = android.os.SystemClock.elapsedRealtime()
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() { stopSelf() }
        override fun onCapturedContentResize(width: Int, height: Int) {
            if (width > 0 && height > 0) {
                capturedContentSize = width to height
                scheduleResize(width, height)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "WindyCast screen sharing", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun notification(): Notification {
        val stopIntent = Intent(this, CastService::class.java).apply { action = ACTION_STOP }
        val stop = PendingIntent.getService(this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 2,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("WindyCast mirroring")
            .setContentText("Sharing screen with Windows • Tap to manage")
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_media_pause, "Stop mirroring", stop)
            .setOngoing(true).build()
    }

    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent.action != ACTION_START) return START_NOT_STICKY
        // Must be foreground with mediaProjection type before obtaining the projection.
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(42, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else startForeground(42, notification())
        if (streaming || socket != null) return START_NOT_STICKY
        val address = intent.getStringExtra(EXTRA_HOST) ?: return START_NOT_STICKY
        val token = intent.getStringExtra(EXTRA_TOKEN) ?: return START_NOT_STICKY
        val port = intent.getIntExtra(EXTRA_PORT, 47839)
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
        val consent = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            intent.getParcelableExtra(EXTRA_RESULT_DATA) as? Intent
        } ?: return START_NOT_STICKY
        val hasAudioPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val withAudio = intent.getBooleanExtra(EXTRA_AUDIO, false) && hasAudioPermission
        val preset = intent.getIntExtra(EXTRA_PROFILE, 0).coerceIn(0, 4)
        val request = Request.Builder()
            .url("ws://" + address + ":" + port + "/stream?token=" + token)
            .build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Thread({
                    try { beginCapture(resultCode, consent, withAudio, preset) }
                    catch (error: Exception) {
                        ws.send(JSONObject().put("type", "error")
                            .put("message", error.message ?: "Capture failed").toString())
                        stopSelf()
                    }
                }, "WindyCast-start").start()
            }
            override fun onMessage(ws: WebSocket, text: String) {
                if (text.contains("\"keyframe\"")) requestKeyframe()
            }
            override fun onFailure(ws: WebSocket, error: Throwable, response: Response?) { stopSelf() }
            override fun onClosed(ws: WebSocket, code: Int, reason: String) { stopSelf() }
        })
        return START_NOT_STICKY
    }

    private data class EncoderChoice(
        val codec: MediaCodec,
        val surface: Surface,
        val width: Int,
        val height: Int,
        val fps: Int,
        val bitrate: Int
    )

    @Suppress("DEPRECATION")
    private fun screenMetrics(): DisplayMetrics {
        val result = DisplayMetrics()
        (getSystemService(Context.WINDOW_SERVICE) as WindowManager)
            .defaultDisplay.getRealMetrics(result)
        return result
    }

    /**
     * The Ultra preset is *up to* native 4K; it does not upscale 1080p.
     * Encoder configuration failures fall back through smaller capture modes.
     */
    private fun createVideoEncoder(sourceWidth: Int, sourceHeight: Int): EncoderChoice {
        val targetEdge = when (presetChoice) {
            1, 4 -> 1280
            3 -> 3840
            else -> 1920
        }
        val targetFps = if (presetChoice == 1 || presetChoice == 3) 30 else 60
        var failure: Exception? = null
        for (longEdge in listOf(targetEdge, 2560, 1920, 1280, 960).filter { it <= targetEdge }.distinct()) {
            val scale = minOf(1f, longEdge.toFloat() / max(sourceWidth, sourceHeight).coerceAtLeast(1))
            val width = ((sourceWidth * scale).roundToInt() / 2 * 2).coerceAtLeast(2)
            val height = ((sourceHeight * scale).roundToInt() / 2 * 2).coerceAtLeast(2)
            for (fps in listOf(targetFps, 30).distinct()) {
                val bitrate = when {
                    longEdge > 2560 -> 12_000_000
                    longEdge > 1920 -> 8_000_000
                    longEdge > 1280 -> if (presetChoice == 2) 8_000_000 else 5_000_000
                    presetChoice == 4 -> 3_000_000
                    else -> 2_500_000
                }
                var candidate: MediaCodec? = null
                try {
                    candidate = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                    val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                        setInteger(MediaFormat.KEY_COLOR_FORMAT,
                            android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                        setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                        setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                        // A priority of 0 requests realtime encoding on encoders
                        // that support Android's codec priority hint.
                        setInteger(MediaFormat.KEY_PRIORITY, 0)
                    }
                    candidate.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    val surface = candidate.createInputSurface()
                    try { candidate.start() }
                    catch (err: Exception) { surface.release(); throw err }
                    return EncoderChoice(candidate, surface, width, height, fps, bitrate)
                } catch (err: Exception) {
                    failure = err
                    try { candidate?.release() } catch (_: Exception) {}
                }
            }
        }
        throw IllegalStateException("No available H.264 encoder: " + failure?.message)
    }

    private fun announceVideo(choice: EncoderChoice) {
        socket?.send(JSONObject().put("type", "start")
            .put("width", choice.width).put("height", choice.height)
            .put("fps", choice.fps).put("bitrate", choice.bitrate)
            .put("orientation", if (choice.width > choice.height) "landscape" else "portrait")
            .toString())
    }

    @Suppress("DEPRECATION")
    private fun beginCapture(resultCode: Int, consent: Intent, withAudio: Boolean, preset: Int) {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mediaProjection = manager.getMediaProjection(resultCode, consent)
            ?: throw IllegalStateException("Screen sharing permission was not granted")
        projection = mediaProjection
        mediaProjection.registerCallback(projectionCallback, handler)
        presetChoice = preset
        val metrics = screenMetrics()
        captureDensityDpi = metrics.densityDpi
        val choice = createVideoEncoder(metrics.widthPixels, metrics.heightPixels)
        encoder = choice.codec
        inputSurface = choice.surface
        activeWidth = choice.width
        activeHeight = choice.height
        streaming = true
        waitingForVideoKeyframe = false
        videoEncodedThisSecond = 0
        videoSentThisSecond = 0
        videoDroppedThisSecond = 0
        statsClockMs = android.os.SystemClock.elapsedRealtime()
        announceVideo(choice)
        display = mediaProjection.createVirtualDisplay(
            "WindyCast", choice.width, choice.height, captureDensityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            choice.surface, null, handler
        )
        videoThread = Thread({ drainVideo(choice.codec) }, "WindyCast-video").apply { start() }
        displayManager.registerDisplayListener(displayListener, handler)
        rotationPollActive = true
        handler.postDelayed(rotationPoll, 900L)
        if (withAudio) startAudio(mediaProjection)
        else socket?.send(JSONObject().put("type", "audio-disabled").toString())
    }

    private fun scheduleDisplayCheck() {
        if (!streaming) return
        val captured = capturedContentSize
        if (captured != null) {
            scheduleResize(captured.first, captured.second)
        } else {
            val metrics = screenMetrics()
            scheduleResize(metrics.widthPixels, metrics.heightPixels)
        }
    }

    private fun scheduleResize(sourceWidth: Int, sourceHeight: Int) {
        if (!streaming || sourceWidth < 100 || sourceHeight < 100 || reconfiguring) return
        if ((activeWidth > activeHeight) == (sourceWidth > sourceHeight)) return
        reconfiguring = true
        rotationExecutor.execute {
            try { resizeEncoder(sourceWidth, sourceHeight) }
            catch (error: Exception) {
                socket?.send(JSONObject().put("type", "error")
                    .put("message", "Rotation failed: " + error.message).toString())
                stopSelf()
            } finally { reconfiguring = false }
        }
    }

    private fun resizeEncoder(sourceWidth: Int, sourceHeight: Int) {
        if (!streaming || display == null) return
        // Android 14+ permits just one createVirtualDisplay per consent token.
        // Reuse its surface and resize in-place without losing Wi-Fi or audio.
        val oldCodec = encoder
        val oldSurface = inputSurface
        encoder = null
        try { display?.setSurface(null) } catch (_: Exception) {}
        try { videoThread?.join(700L) } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        try { oldCodec?.stop() } catch (_: Exception) {}
        try { oldCodec?.release() } catch (_: Exception) {}
        try { oldSurface?.release() } catch (_: Exception) {}
        if (!streaming) return
        val choice = createVideoEncoder(sourceWidth, sourceHeight)
        inputSurface = choice.surface
        encoder = choice.codec
        activeWidth = choice.width
        activeHeight = choice.height
        waitingForVideoKeyframe = false
        videoEncodedThisSecond = 0
        videoSentThisSecond = 0
        videoDroppedThisSecond = 0
        statsClockMs = android.os.SystemClock.elapsedRealtime()
        announceVideo(choice)
        display?.resize(choice.width, choice.height, captureDensityDpi)
        display?.setSurface(choice.surface)
        videoThread = Thread({ drainVideo(choice.codec) }, "WindyCast-video").apply { start() }
    }

    private fun requestKeyframe() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastKeyframeRequestMs < 700) return
        lastKeyframeRequestMs = now
        try {
            encoder?.setParameters(android.os.Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            })
        } catch (_: Exception) {
            // Some hardware encoders do not support on-demand sync frames.
        }
    }

    private fun sendPacket(kind: Int, flags: Int, timestampUs: Long, payload: ByteArray): Boolean {
        val ws = socket ?: return false
        val queuedBytes = ws.queueSize()
        // Low-latency mode favors fresh frames over buffered frames.
        // Tune the other profiles more conservatively for larger IDR sizes.
        val videoCongestionLimit = if (presetChoice == 4) 350_000L else 700_000L
        val keyframeRecoveryLimit = if (presetChoice == 4) 130_000L else 180_000L
        // Audio is best-effort. Never queue many seconds of stale game audio.
        if (kind == 2 && queuedBytes > 500_000L) return false
        if (kind == 1) {
            val keyFrame = flags and 1 != 0
            // Never drop an isolated H.264 delta frame and carry on with its
            // dependent frames: that produces the corruption seen in v0.1.
            if (queuedBytes > videoCongestionLimit) {
                if (!waitingForVideoKeyframe) {
                    waitingForVideoKeyframe = true
                    ws.send(JSONObject().put("type", "resync").toString())
                }
                videoDroppedThisSecond++
                requestKeyframe()
                return false
            }
            if (waitingForVideoKeyframe) {
                if (!keyFrame || queuedBytes > keyframeRecoveryLimit) {
                    videoDroppedThisSecond++
                    requestKeyframe()
                    return false
                }
                waitingForVideoKeyframe = false
            }
        }
        val bytes = ByteBuffer.allocate(10 + payload.size)
            .put(kind.toByte()).put(flags.toByte()).putLong(timestampUs)
            .put(payload).array()
        val accepted = ws.send(bytes.toByteString())
        if (!accepted && kind == 1) {
            waitingForVideoKeyframe = true
            videoDroppedThisSecond++
            requestKeyframe()
        }
        return accepted
    }

    private fun reportVideoStats() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - statsClockMs < 1000L) return
        val elapsed = (now - statsClockMs).coerceAtLeast(1L)
        val ws = socket
        ws?.send(JSONObject()
            .put("type", "stats")
            .put("encodedFps", (videoEncodedThisSecond * 1000L / elapsed).toInt())
            .put("sentFps", (videoSentThisSecond * 1000L / elapsed).toInt())
            .put("dropped", videoDroppedThisSecond)
            .put("queueKb", (ws.queueSize() / 1024L).toInt())
            .toString())
        videoEncodedThisSecond = 0
        videoSentThisSecond = 0
        videoDroppedThisSecond = 0
        statsClockMs = now
    }

    private fun drainVideo(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        try {
            while (streaming && encoder === codec) {
                when (val index = codec.dequeueOutputBuffer(info, 10_000L)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> continue
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val format = codec.outputFormat
                        val headers = java.io.ByteArrayOutputStream()
                        for (key in listOf("csd-0", "csd-1")) {
                            val buf = format.getByteBuffer(key) ?: continue
                            val bytes = ByteArray(buf.remaining())
                            buf.get(bytes)
                            if (bytes.isNotEmpty()) headers.write(bytes)
                        }
                        if (headers.size() > 0) sendPacket(3, 0, 0L, headers.toByteArray())
                    }
                    else -> if (index >= 0) {
                        val buffer = codec.getOutputBuffer(index)
                        if (buffer != null && info.size > 0) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            val bytes = ByteArray(info.size)
                            buffer.get(bytes)
                            val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                            val isKey = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                            if (isConfig) {
                                sendPacket(3, 0, info.presentationTimeUs, bytes)
                            } else {
                                videoEncodedThisSecond++
                                if (sendPacket(1, if (isKey) 1 else 0, info.presentationTimeUs, bytes)) {
                                    videoSentThisSecond++
                                }
                                reportVideoStats()
                            }
                        }
                        codec.releaseOutputBuffer(index, false)
                    }
                }
            }
        } catch (_: IllegalStateException) {
            // Expected while stopping the projection.
        } catch (_: Exception) {
            stopSelf()
        }
    }

    private fun startAudio(mediaProjection: MediaProjection) {
        try {
            val capture = android.media.AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .build()
            val sampleRate = 48000
            val minimum = AudioRecord.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val audioRecord = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(capture)
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
                .setBufferSizeInBytes(max(19200, minimum))
                .build()
            require(audioRecord.state == AudioRecord.STATE_INITIALIZED) { "Audio capture unavailable" }
            recorder = audioRecord
            audioRecord.startRecording()
            audioThread = Thread({
                val samples = ShortArray(960) // 20ms @ 48kHz
                try {
                    while (streaming) {
                        val count = audioRecord.read(samples, 0, samples.size, AudioRecord.READ_BLOCKING)
                        if (count > 0) {
                            val bytes = ByteBuffer.allocate(count * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                            for (i in 0 until count) bytes.putShort(samples[i])
                            sendPacket(2, 0, System.nanoTime() / 1000L, bytes.array())
                        }
                    }
                } catch (_: Exception) {}
            }, "WindyCast-audio").apply { start() }
        } catch (_: Exception) {
            socket?.send(JSONObject().put("type", "audio-disabled").toString())
        }
    }

    override fun onDestroy() {
        streaming = false
        rotationPollActive = false
        handler.removeCallbacks(rotationPoll)
        try { displayManager.unregisterDisplayListener(displayListener) } catch (_: Exception) {}
        rotationExecutor.shutdownNow()
        try { recorder?.stop() } catch (_: Exception) {}
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null
        try { display?.release() } catch (_: Exception) {}
        display = null
        try { encoder?.stop() } catch (_: Exception) {}
        try { encoder?.release() } catch (_: Exception) {}
        encoder = null
        try { inputSurface?.release() } catch (_: Exception) {}
        inputSurface = null
        try { projection?.unregisterCallback(projectionCallback) } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        socket?.close(1000, "Mirroring stopped")
        socket = null
        client.dispatcher.executorService.shutdown()
        super.onDestroy()
    }
}
