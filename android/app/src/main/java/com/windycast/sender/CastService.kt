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
    private var encoder: MediaCodec? = null
    private var display: VirtualDisplay? = null
    private var inputSurface: Surface? = null
    private var recorder: AudioRecord? = null
    private var videoThread: Thread? = null
    private var audioThread: Thread? = null
    @Volatile private var streaming = false
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() { stopSelf() }
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
        val preset = intent.getIntExtra(EXTRA_PROFILE, 0).coerceIn(0, 2)
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
                if (text.contains("keyframe")) {
                    try {
                        encoder?.setParameters(android.os.Bundle().apply {
                            putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                        })
                    } catch (_: Exception) {}
                }
            }
            override fun onFailure(ws: WebSocket, error: Throwable, response: Response?) { stopSelf() }
            override fun onClosed(ws: WebSocket, code: Int, reason: String) { stopSelf() }
        })
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun beginCapture(resultCode: Int, consent: Intent, withAudio: Boolean, preset: Int) {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mediaProjection = manager.getMediaProjection(resultCode, consent)
            ?: throw IllegalStateException("Screen sharing permission was not granted")
        projection = mediaProjection
        mediaProjection.registerCallback(projectionCallback, handler)
        val metrics = DisplayMetrics()
        (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
        val longest = max(metrics.widthPixels, metrics.heightPixels).coerceAtLeast(1)
        val maxLongEdge = if (preset == 1) 1280 else 1920
        val scale = minOf(1f, maxLongEdge.toFloat() / longest)
        val width = ((metrics.widthPixels * scale).roundToInt() / 2 * 2).coerceAtLeast(2)
        val height = ((metrics.heightPixels * scale).roundToInt() / 2 * 2).coerceAtLeast(2)
        val fps = if (preset == 1) 30 else 60
        val bitrate = when (preset) { 1 -> 4_000_000; 2 -> 20_000_000; else -> 12_000_000 }

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT,
                android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        }
        val videoEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder = videoEncoder
        videoEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = videoEncoder.createInputSurface()
        videoEncoder.start()
        streaming = true
        socket?.send(JSONObject()
            .put("type", "start").put("width", width).put("height", height)
            .put("fps", fps).put("bitrate", bitrate).toString())
        display = mediaProjection.createVirtualDisplay(
            "WindyCast", width, height, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            inputSurface, null, handler
        )
        videoThread = Thread({ drainVideo(videoEncoder) }, "WindyCast-video").apply { start() }
        if (withAudio) startAudio(mediaProjection)
        else socket?.send(JSONObject().put("type", "audio-disabled").toString())
    }

    private fun sendPacket(kind: Int, flags: Int, timestampUs: Long, payload: ByteArray) {
        val ws = socket ?: return
        // Avoid accumulating seconds of lag on slow Wi-Fi. Next keyframe will resync.
        if (ws.queueSize() > 2_000_000L && kind == 1 && flags == 0) return
        val bytes = ByteBuffer.allocate(10 + payload.size)
            .put(kind.toByte()).put(flags.toByte()).putLong(timestampUs)
            .put(payload).array()
        ws.send(bytes.toByteString())
    }

    private fun drainVideo(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        try {
            while (streaming) {
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
                            sendPacket(if (isConfig) 3 else 1,
                                if (isKey) 1 else 0, info.presentationTimeUs, bytes)
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
