package com.windycast.sender

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.media.projection.MediaProjectionConfig
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

class MainActivity : ComponentActivity() {
    private var host = ""
    private var port = 47839
    private var token = ""
    private lateinit var details: TextView
    private lateinit var status: TextView
    private lateinit var audioCheck: CheckBox
    private lateinit var quality: Spinner
    private val projectionManager by lazy {
        getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    private val scanner = registerForActivityResult(ScanContract()) { result ->
        if (result.contents != null) setPairing(result.contents)
    }
    private val audioPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        // Denied audio permission still allows video-only capture.
        launchScreenPrompt()
    }
    private val screenPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK || result.data == null) {
            status.text = "Screen capture cancelled."
            return@registerForActivityResult
        }
        val intent = Intent(this, CastService::class.java).apply {
            action = CastService.ACTION_START
            putExtra(CastService.EXTRA_HOST, host)
            putExtra(CastService.EXTRA_PORT, port)
            putExtra(CastService.EXTRA_TOKEN, token)
            putExtra(CastService.EXTRA_RESULT_CODE, result.resultCode)
            putExtra(CastService.EXTRA_RESULT_DATA, result.data)
            putExtra(CastService.EXTRA_AUDIO, audioCheck.isChecked &&
                ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            putExtra(CastService.EXTRA_PROFILE, quality.selectedItemPosition)
        }
        ContextCompat.startForegroundService(this, intent)
        status.text = "Connecting… Your screen will appear on Windows. Use Stop to end sharing."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        if (::status.isInitialized) {
            val reason = getSharedPreferences("windycast", MODE_PRIVATE)
                .getString("lastStopReason", null)
            if (!reason.isNullOrBlank()) {
                status.text = reason
            } else {
                // A Game Turbo memory cleanup can kill WindyCast's process
                // outright, preventing callbacks. Persist a diagnostic marker
                // so we can report that interruption on the next launch.
                val previouslyCapturing = getSharedPreferences("windycast", MODE_PRIVATE)
                    .getBoolean("captureExpected", false)
                if (previouslyCapturing && !CastService.runningInProcess) {
                    status.text = "WindyCast was closed by Android while mirroring. On Xiaomi: check Game Turbo memory cleanup, lock WindyCast in recent apps, and try launching the game before starting capture."
                }
            }
        }
    }

    private fun setPairing(code: String) {
        try {
            val uri = Uri.parse(code.trim())
            require(uri.scheme == "windycast" && uri.host == "connect") { "Not a WindyCast pairing code" }
            val address = uri.getQueryParameter("host") ?: ""
            val portText = uri.getQueryParameter("port") ?: ""
            val secret = uri.getQueryParameter("token") ?: ""
            require(address.matches(Regex("[0-9.]{7,15}"))) { "Invalid PC address" }
            val parsedPort = portText.toInt()
            require(parsedPort in 1..65535) { "Invalid port" }
            require(secret.matches(Regex("[a-fA-F0-9]{48}"))) { "Invalid pairing token" }
            host = address
            port = parsedPort
            token = secret
            details.text = "Paired PC  •  $host:$port"
            status.text = "Ready. Tap Start mirroring."
        } catch (error: Exception) {
            status.text = "QR code error: ${error.message}"
        }
    }

    private fun startFlow() {
        if (host.isBlank() || token.isBlank()) {
            status.text = "Scan your Windows QR code first."
            return
        }
        if (audioCheck.isChecked &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            audioPermission.launch(Manifest.permission.RECORD_AUDIO)
        } else launchScreenPrompt()
    }

    private fun launchScreenPrompt() {
        // Android 14+ often defaults to "share one app". That capture may
        // stop when the user opens a game. WindyCast mirrors the whole phone,
        // so ask explicitly for the entire display (consent is still required).
        val consentIntent = if (Build.VERSION.SDK_INT >= 34) {
            projectionManager.createScreenCaptureIntent(
                MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            projectionManager.createScreenCaptureIntent()
        }
        screenPermission.launch(consentIntent)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun rounded(color: String, radius: Int = 14) = GradientDrawable().apply {
        setColor(Color.parseColor(color)); cornerRadius = dp(radius).toFloat()
    }
    private fun label(value: String, size: Float, color: String = "#F0F6FF", bold: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(Color.parseColor(color))
            if (bold) setTypeface(null, Typeface.BOLD)
        }
    private fun actionButton(textValue: String, color: String, click: () -> Unit) =
        Button(this).apply {
            text = textValue
            isAllCaps = false
            textSize = 15f
            setTextColor(Color.parseColor(if (color == "#42E2C5") "#082A2D" else "#FFFFFF"))
            background = rounded(color, 11)
            setOnClickListener { click() }
        }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(26), dp(22), dp(28))
            setBackgroundColor(Color.parseColor("#090D19"))
        }
        root.addView(label("◈  WindyCast", 29f, "#42E2C5", true))
        root.addView(label("Play on Android. Stream on Windows.", 14f, "#9DAECA").apply {
            setPadding(0, dp(9), 0, dp(25))
        })
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded("#141E30")
            setPadding(dp(20), dp(24), dp(20), dp(22))
        }
        card.addView(label("Connect to your PC", 21f, bold = true))
        card.addView(label("Both devices must use the same Wi-Fi or LAN.", 13f, "#9DAECA").apply {
            setPadding(0, dp(7), 0, dp(16))
        })
        details = label("No PC paired yet", 14f, "#5EE4CB")
        card.addView(details)
        card.addView(actionButton("▦   Scan PC QR code", "#29415C") {
            scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt("Point at your PC's WindyCast QR").setBeepEnabled(false)
                .setOrientationLocked(false))
        }.apply {
            val lp = LinearLayout.LayoutParams(-1, dp(54))
            lp.topMargin = dp(19)
            layoutParams = lp
        })
        root.addView(card)
        root.addView(label("STREAM QUALITY", 12f, "#98ADC8", true).apply {
            setPadding(0, dp(30), 0, dp(10))
        })
        quality = Spinner(this)
        val options = arrayOf(
            "Gaming — 1080p / 60 FPS (5 Mbps)",
            "Balanced — 720p / 30 FPS (2.5 Mbps)",
            "High Quality — 1080p / 60 FPS (8 Mbps)",
            "Ultra — Native up to 4K / 30 FPS (12 Mbps)",
            "Low Latency — 720p / 60 FPS (3 Mbps)",
            "Ultra 60 FPS — Native up to 4K / 60 FPS (adaptive)"
        )
        quality.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, options)
        quality.setBackgroundColor(Color.parseColor("#1D2C43"))
        // Responsiveness is the default. Players can change to quality modes.
        quality.setSelection(4)
        root.addView(quality, LinearLayout.LayoutParams(-1, dp(55)))
        root.addView(label("Auto-rotate follows the game. Ultra uses native pixels and auto-falls back when the phone cannot encode 4K/60.", 12f, "#8CA2BF").apply {
            setPadding(0, dp(10), 0, 0)
        })
        audioCheck = CheckBox(this).apply {
            text = "Include game audio (supported games)"
            setTextColor(Color.parseColor("#D8E6F5"))
            isChecked = true
            setPadding(0, dp(15), 0, dp(20))
        }
        root.addView(audioCheck)
        root.addView(label("XIAOMI / HYPEROS GAME TURBO", 12f, "#A1B7D2", true).apply {
            setPadding(0, dp(18), 0, dp(6))
        })
        root.addView(label(
            "For Xiaomi 14T Pro: Security → Game Turbo → Settings. Disable memory cleaning/background app cleanup where available. Lock WindyCast in Recent Apps and allow unrestricted battery activity. Try starting Game Turbo before mirroring.",
            12f, "#C8D9EE").apply { setPadding(0, dp(4), 0, dp(8)) })
        root.addView(actionButton("⚙  WindyCast App Info / Battery", "#29415C") {
            // This is ONLY Android's app settings, not Xiaomi's Game Turbo.
            status.text = "App Info: find Battery saver and set No restrictions if available. For Game Turbo, open Xiaomi Security → Game Turbo manually."
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName")))
        }.apply {
            val lp = LinearLayout.LayoutParams(-1, dp(50))
            lp.bottomMargin = dp(14)
            layoutParams = lp
        })
        root.addView(label("For game streaming, WindyCast requests permission to share your entire display, not just one app. Please approve the Android prompt.", 12f, "#91AACA").apply {
            setPadding(0, 0, 0, dp(12))
        })
        root.addView(actionButton("▶   Start mirroring", "#42E2C5") { startFlow() },
            LinearLayout.LayoutParams(-1, dp(57)))
        root.addView(actionButton("■   Stop mirroring", "#344259") {
            getSharedPreferences("windycast", MODE_PRIVATE).edit()
                .putBoolean("captureExpected", false).remove("lastStopReason").apply()
            stopService(Intent(this, CastService::class.java))
            status.text = "Mirroring stopped."
        }.apply {
            val lp = LinearLayout.LayoutParams(-1, dp(54))
            lp.topMargin = dp(13)
            layoutParams = lp
        })
        status = label("Scan your PC's QR code to begin.", 13f, "#B5C6D9").apply {
            gravity = Gravity.CENTER
            setPadding(dp(5), dp(24), dp(5), 0)
        }
        root.addView(status)
        root.addView(label("Local Wi-Fi only • No account • No watermark", 12f, "#7690AF").apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(25), 0, 0)
        })
        setContentView(ScrollView(this).apply { addView(root) })
    }
}
