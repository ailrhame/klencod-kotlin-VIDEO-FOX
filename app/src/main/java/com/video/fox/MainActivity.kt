
package com.video.fox

import android.app.*
import android.content.*
import android.graphics.*
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.*
import android.provider.OpenableColumns
import android.view.*
import android.widget.*
import android.content.pm.ServiceInfo
import java.io.File
import java.io.IOException

private const val CHANNEL = "video_fox_capture"
private const val ACTION_START = "com.video.fox.START"
private const val ACTION_STOP = "com.video.fox.STOP"

class MainActivity : Activity() {

    private val orange = Color.rgb(255, 132, 36)
    private val cream = Color.rgb(255, 247, 235)
    private val dark = Color.rgb(30, 25, 23)

    private lateinit var root: LinearLayout
    private lateinit var content: LinearLayout
    private lateinit var projectionManager: MediaProjectionManager

    private val videoPicker =
        registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.GetContent()
        ) { uri ->
            uri?.let { importVideo(it) }
        }

    private val capturePermission =
        registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                startService(
                    Intent(this, ScreenCaptureService::class.java).apply {
                        action = ACTION_START
                        putExtra("resultCode", result.resultCode)
                        putExtra("data", result.data)
                    }
                )
                startOverlay()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        projectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        createNotificationChannel()
        buildShell()
        showHome()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL,
                "Video Fox Screen Capture",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun buildShell() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(cream)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(22, 22, 22, 16)
            setBackgroundColor(dark)
        }

        header.addView(text("🦊 VIDEO FOX", 25f, orange, true))
        header.addView(text("ترجمة الفيديو • Made by: @N5CR4", 13f, Color.WHITE))

        root.addView(header)

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 18, 18, 12)
        }

        root.addView(
            ScrollView(this).apply {
                addView(content)
            },
            LinearLayout.LayoutParams(0, 0, 1f)
        )

        val navigation = LinearLayout(this).apply {
            setBackgroundColor(dark)
            orientation = LinearLayout.HORIZONTAL
        }

        listOf(
            "الرئيسية" to ::showHome,
            "الفيديوهات" to ::showVideos,
            "الإعدادات" to ::showSettings
        ).forEach { (name, action) ->
            navigation.addView(
                Button(this).apply {
                    text = name
                    setTextColor(orange)
                    setBackgroundColor(dark)
                    setOnClickListener { action() }
                },
                LinearLayout.LayoutParams(0, 58, 1f)
            )
        }

        root.addView(navigation)
        root.addView(
            text("Made by: @N5CR4", 12f, orange).apply {
                gravity = Gravity.CENTER
                setPadding(0, 8, 0, 8)
                setBackgroundColor(dark)
            }
        )

        setContentView(root)
    }

    private fun showHome() {
        content.removeAllViews()

        content.addView(text("مرحباً بك في Video Fox 🦊", 23f, dark, true))
        content.addView(text("حوّل الفيديو إلى ترجمة عربية متزامنة.", 15f))

        cardButton("🎥 تسجيل الشاشة بالعدسة العائمة") {
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 100
                )
            }

            if (!Settings.canDrawOverlays(this)) {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            } else {
                capturePermission.launch(projectionManager.createScreenCaptureIntent())
            }
        }

        cardButton("📂 رفع فيديو من الملفات") {
            videoPicker.launch("video/*")
        }

        cardButton("🎞 الفيديوهات المعالجة") {
            showVideos()
        }

        cardButton("⚙️ إعدادات الترجمة") {
            showSettings()
        }

        content.addView(
            text(
                "المحركات: ترجمة محلية + Qwen API\n" +
                    "الصوت الأصلي محفوظ في ملف الناتج.",
                14f,
                dark
            )
        )
    }

    private fun showSettings() {
        content.removeAllViews()

        content.addView(text("إعدادات Video Fox", 24f, dark, true))

        val api = EditText(this).apply {
            hint = "رابط Qwen API"
            setSingleLine()
        }

        val model = EditText(this).apply {
            hint = "اسم النموذج"
            setSingleLine()
        }

        val key = EditText(this).apply {
            hint = "مفتاح API"
            setSingleLine()
        }

        content.addView(api)
        content.addView(model)
        content.addView(key)

        cardButton("حفظ الإعدادات") {
            getSharedPreferences("fox", MODE_PRIVATE).edit()
                .putString("api", api.text.toString())
                .putString("model", model.text.toString())
                .putString("key", key.text.toString())
                .apply()

            toast("تم حفظ الإعدادات")
        }

        content.addView(
            text("Made by: @N5CR4", 13f, orange)
        )
    }

    private fun importVideo(uri: Uri) {
        val name = queryName(uri)
        val output = File(filesDir, "videos").apply { mkdirs() }
        val destination = File(output, "${System.currentTimeMillis()}_$name")

        try {
            contentResolver.openInputStream(uri)?.use { input ->
                destination.outputStream().use { out ->
                    input.copyTo(out)
                }
            }

            content.removeAllViews()
            content.addView(text("تم استيراد الفيديو", 22f, dark, true))
            content.addView(text(destination.name, 14f))

            cardButton("بدء معالجة الترجمة") {
                TranslationPipeline.start(this, destination)
                showVideos()
            }

            cardButton("العودة للرئيسية") {
                showHome()
            }
        } catch (e: Exception) {
            toast("فشل استيراد الفيديو: ${e.message}")
        }
    }

    private fun queryName(uri: Uri): String {
        var name = "video.mp4"

        contentResolver.query(uri, null, null, null, null)?.use {
            if (it.moveToFirst()) {
                val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) name = it.getString(index)
            }
        }

        return name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
    }

    private fun showVideos() {
        content.removeAllViews()
        content.addView(text("مكتبة الفيديوهات", 24f, dark, true))

        val folder = File(filesDir, "videos")
        val files = folder.listFiles()?.sortedByDescending { it.lastModified() }
            ?: emptyList()

        if (files.isEmpty()) {
            content.addView(text("لا توجد فيديوهات بعد.", 16f))
            return
        }

        files.forEach { file ->
            cardButton("▶ ${file.name}") {
                val uri = Uri.fromFile(file)
                val player = android.widget.VideoView(this)
                player.setVideoURI(uri)
                content.removeAllViews()
                content.addView(player, LinearLayout.LayoutParams(
                    -1, 320
                ))
                player.setOnPreparedListener { mp ->
                    mp.isLooping = false
                    player.start()
                }
                cardButton("إيقاف") { player.pause() }
                cardButton("عودة") { showVideos() }
            }
        }
    }

    private fun startOverlay() {
        if (Settings.canDrawOverlays(this)) {
            startService(Intent(this, FoxOverlayService::class.java))
        }
    }

    private fun cardButton(label: String, action: () -> Unit) {
        val button = Button(this).apply {
            text = label
            textSize = 15f
            setTextColor(Color.WHITE)
            setBackgroundColor(orange)
            setPadding(12, 14, 12, 14)
            setOnClickListener { action() }
        }

        content.addView(
            button,
            LinearLayout.LayoutParams(-1, -2).apply {
                setMargins(0, 12, 0, 8)
            }
        )
    }

    private fun text(
        value: String,
        size: Float,
        color: Int = dark,
        bold: Boolean = false
    ): TextView = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(null, Typeface.BOLD)
        setPadding(4, 10, 4, 10)
    }

    private fun toast(value: String) {
        Toast.makeText(this, value, Toast.LENGTH_LONG).show()
    }
}

/* ==================== Screen Capture ==================== */

class ScreenCaptureService : Service() {

    private var projection: MediaProjection? = null
    private var recorder: MediaRecorder? = null
    private var display: VirtualDisplay? = null
    private var output: File? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        when (intent?.action) {
            ACTION_START -> begin(intent)
            ACTION_STOP -> stopCapture()
        }
        return START_NOT_STICKY
    }

    private fun notification(): Notification {
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, ScreenCaptureService::class.java).apply {
                action = ACTION_STOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Video Fox")
            .setContentText("تسجيل الشاشة جارٍ")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .addAction(
                android.R.drawable.ic_media_pause,
                "إيقاف",
                stop
            )
            .setOngoing(true)
            .build()
    }

    private fun begin(intent: Intent) {
        startForeground(
            22,
            notification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )

        val resultCode = intent.getIntExtra("resultCode", Activity.RESULT_CANCELED)
        val data = intent.getParcelableExtra<Intent>("data") ?: return

        projection = (getSystemService(MEDIA_PROJECTION_SERVICE)
            as MediaProjectionManager).getMediaProjection(resultCode, data)

        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        output = File(
            getExternalFilesDir(null),
            "fox_${System.currentTimeMillis()}.mp4"
        )

        recorder = if (Build.VERSION.SDK_INT >= 31 {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        recorder?.apply {
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setVideoSize(width, height)
            setVideoFrameRate(30)
            setVideoEncodingBitRate(8_000_000)
            setOutputFile(output!!.absolutePath)
            prepare()
            start()
        }

        display = projection?.createVirtualDisplay(
            "VideoFox",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            recorder?.surface,
            null,
            null
        )
    }

    private fun stopCapture() {
        try {
            display?.release()
            display = null

            recorder?.stop()
            recorder?.reset()
            recorder?.release()
            recorder = null

            projection?.stop()
            projection = null
        } catch (_: Exception) {
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }
}

/* ==================== Floating Fox Lens ==================== */

class FoxOverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var lens: View? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        lens = TextView(this).apply {
            text = "🦊"
            textSize = 25f
            gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(255, 132, 36))
            setPadding(18, 18, 18, 18)
            setOnClickListener {
                startActivity(
                    Intent(this@FoxOverlayService, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                stopSelf()
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 20
            y = 250
        }

        windowManager?.addView(lens, params)
    }

    override fun onDestroy() {
        lens?.let {
            try {
                windowManager?.removeView(it)
            } catch (_: Exception) {
            }
        }
        super.onDestroy()
    }
}

/* ==================== Translation Pipeline ==================== */

object TranslationPipeline {

    fun start(context: Context, source: File) {
        val notificationManager =
            context.getSystemService(NotificationManager::class.java)

        val channel = NotificationChannel(
            "fox_processing",
            "Video Fox Processing",
            NotificationManager.IMPORTANCE_LOW
        )

        notificationManager.createNotificationChannel(channel)

        notificationManager.notify(
            44,
            Notification.Builder(context, "fox_processing")
                .setContentTitle("Video Fox")
                .setContentText("الفيديو جاهز للمعالجة")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .build()
        )

        /*
         * Pipeline integration:
         *
         * 1. FFmpegKit / Media3 Transformer (external dependency required)
         *    demux source video/audio without playback.
         *
         * 2. Whisper / Vosk / local ASR:
         *    extract speech -> timestamps -> transcription.
         *
         * 3. Qwen API:
         *    translate timestamped text to Arabic.
         *
         * 4. Local translation model:
         *    optional offline translation engine.
         *
         * 5. Render Arabic subtitles (ASS/SRT -> burn-in or soft subtitles).
         *
         * 6. Mux original audio + translated video:
         *    preserve original audio codec whenever possible.
         *
         * No MediaPlayer or VideoView is used during processing.
         *
         * This method is a placeholder until an actual ASR,
         * translation model and media-processing engine are integrated.
         */
    }
}
