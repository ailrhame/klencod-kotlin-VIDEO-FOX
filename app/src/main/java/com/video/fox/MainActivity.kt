package com.video.fox

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import java.io.File

private const val FOX_CHANNEL = "video_fox_capture"
const val ACTION_CAPTURE_START = "com.video.fox.CAPTURE_START"
const val ACTION_CAPTURE_STOP = "com.video.fox.CAPTURE_STOP"

class MainActivity : ComponentActivity() {
    private val orange = Color.rgb(255, 132, 36)
    private val cream = Color.rgb(255, 247, 235)
    private val dark = Color.rgb(30, 25, 23)
    private lateinit var content: LinearLayout
    private lateinit var projectionManager: android.media.projection.MediaProjectionManager

    private val videoPicker = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let(::importVideo) }

    private val projectionRequest = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val service = Intent(this, ScreenCaptureService::class.java).apply {
                action = ACTION_CAPTURE_START
                putExtra("resultCode", result.resultCode)
                putExtra("projectionData", result.data)
            }
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(service) else startService(service)
            startOverlay()
            toast("بدأ تسجيل الشاشة. أوقفه من إشعار Video Fox.")
        } else {
            toast("لم يتم منح إذن تسجيل الشاشة.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
        FoxNotifications.createChannels(this)
        buildShell()
        showHome()
    }

    private fun buildShell() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(cream)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(22, 22, 22, 16)
            setBackgroundColor(dark)
        }
        header.addView(label("🦊 VIDEO FOX", 25f, orange, true))
        header.addView(label("ترجمة النصوص والكلام إلى العربية • @N5CR4", 13f, Color.WHITE))
        root.addView(header)

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 18, 18, 12)
        }
        root.addView(ScrollView(this).apply { addView(content) },
            LinearLayout.LayoutParams(-1, 0, 1f))

        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(dark)
        }
        listOf("الرئيسية" to ::showHome, "الفيديوهات" to ::showVideos, "الإعدادات" to ::showSettings)
            .forEach { (name, action) ->
                nav.addView(Button(this).apply {
                    text = name
                    setTextColor(orange)
                    setBackgroundColor(dark)
                    setOnClickListener { action() }
                }, LinearLayout.LayoutParams(0, 56, 1f))
            }
        root.addView(nav)
        root.addView(label("Made by: @N5CR4", 12f, orange).apply {
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 8)
            setBackgroundColor(dark)
        })
        setContentView(root)
    }

    private fun showHome() {
        content.removeAllViews()
        content.addView(label("مرحباً بك في Video Fox 🦊", 23f, dark, true))
        content.addView(label(
            "يعالج التطبيق مسارين: OCR للنصوص الظاهرة، وتفريغ الصوت عبر واجهة ASR تضبطها في الإعدادات. ينتج ملف ترجمة SRT عربي بجانب الفيديو.",
            15f
        ))
        actionButton("🎥 تسجيل الشاشة بالعدسة العائمة") { requestScreenCapture() }
        actionButton("📂 استيراد فيديو") { videoPicker.launch("video/*") }
        actionButton("🎞 مكتبة الفيديوهات والترجمات") { showVideos() }
        actionButton("⚙️ إعدادات Qwen و ASR") { showSettings() }
        content.addView(label("ملاحظة: لا يُستبدل الفيديو الأصلي. يُنشأ ملف SRT منفصل للمحافظة على الصوت الأصلي دون إعادة ترميزه.", 13f))
    }

    private fun requestScreenCapture() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            toast("فعّل الظهور فوق التطبيقات ثم ارجع واضغط تسجيل الشاشة.")
            return
        }
        projectionRequest.launch(projectionManager.createScreenCaptureIntent())
    }

    private fun showSettings() {
        content.removeAllViews()
        content.addView(label("إعدادات الترجمة", 23f, dark, true))
        val prefs = getSharedPreferences("video_fox_settings", MODE_PRIVATE)
        val endpoint = editField("رابط Qwen المتوافق مع OpenAI", prefs.getString("apiUrl", "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions") ?: "")
        val model = editField("اسم نموذج Qwen", prefs.getString("model", "qwen-plus") ?: "qwen-plus")
        val key = editField("مفتاح API", prefs.getString("apiKey", "") ?: "", password = true)
        val asr = editField("رابط ASR متوافق مع /audio/transcriptions (اختياري)", prefs.getString("asrUrl", "") ?: "")
        val asrKey = editField("مفتاح ASR (اختياري، يترك فارغاً لاستخدام مفتاح Qwen)", prefs.getString("asrKey", "") ?: "", password = true)
        listOf(endpoint, model, key, asr, asrKey).forEach { content.addView(it) }
        actionButton("حفظ الإعدادات") {
            prefs.edit()
                .putString("apiUrl", endpoint.text.toString().trim())
                .putString("model", model.text.toString().trim())
                .putString("apiKey", key.text.toString().trim())
                .putString("asrUrl", asr.text.toString().trim())
                .putString("asrKey", asrKey.text.toString().trim())
                .apply()
            toast("تم حفظ الإعدادات")
        }
        content.addView(label("تنبيه: لا تضع مفتاح API في مستودع عام. الطلبات تُرسل إلى العناوين التي تدخلها.", 13f))
    }

    private fun editField(hintText: String, value: String, password: Boolean = false): EditText =
        EditText(this).apply {
            hint = hintText
            setText(value)
            setSingleLine(true)
            if (password) inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

    private fun importVideo(uri: Uri) {
        val folder = File(filesDir, "videos").apply { mkdirs() }
        val displayName = queryName(uri).replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val destination = File(folder, "${System.currentTimeMillis()}_$displayName")
        try {
            val input = contentResolver.openInputStream(uri) ?: error("تعذر فتح الفيديو")
            input.use { source -> destination.outputStream().use(source::copyTo) }
            showImported(destination)
        } catch (e: Exception) {
            toast("فشل استيراد الفيديو: ${e.message}")
        }
    }

    private fun queryName(uri: Uri): String {
        var name = "video.mp4"
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (index >= 0) name = cursor.getString(index) ?: name
            }
        }
        return name
    }

    private fun showImported(file: File) {
        content.removeAllViews()
        content.addView(label("تم استيراد الفيديو", 22f, dark, true))
        content.addView(label(file.name, 13f))
        actionButton("بدء OCR + تفريغ الصوت + الترجمة") {
            content.addView(label("بدأت المعالجة في الخلفية. تابع الإشعار.", 14f))
            TranslationPipeline.start(this, file)
        }
        actionButton("العودة للرئيسية") { showHome() }
    }

    private fun showVideos() {
        content.removeAllViews()
        content.addView(label("مكتبة الفيديوهات", 23f, dark, true))
        val folder = File(filesDir, "videos")
        val items = folder.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
        if (items.isEmpty()) {
            content.addView(label("لا توجد ملفات بعد.", 15f))
            return
        }
        items.forEach { file ->
            actionButton("▶ ${file.name}") {
                content.removeAllViews()
                val video = VideoView(this)
                video.setVideoURI(Uri.fromFile(file))
                content.addView(video, LinearLayout.LayoutParams(-1, 300))
                video.setOnPreparedListener { it.start() }
                actionButton("فتح ملف الترجمة SRT") {
                    val srt = File(file.parentFile, file.nameWithoutExtension + ".ar.srt")
                    if (srt.exists()) {
                        val textView = TextView(this).apply {
                            text = srt.readText()
                            textSize = 13f
                            setTextColor(dark)
                        }
                        content.addView(ScrollView(this).apply { addView(textView) })
                    } else toast("لم يتم إنشاء ملف ترجمة لهذا الفيديو بعد.")
                }
                actionButton("عودة") { showVideos() }
            }
        }
    }

    private fun startOverlay() {
        if (Settings.canDrawOverlays(this)) {
            val intent = Intent(this, FoxOverlayService::class.java)
            if (Build.VERSION.SDK_INT >= 26) startForegroundServiceIfNeeded(intent) else startService(intent)
        }
    }

    private fun startForegroundServiceIfNeeded(intent: Intent) {
        // The overlay itself is not a foreground service; startService is appropriate here.
        startService(intent)
    }

    private fun actionButton(caption: String, action: () -> Unit) {
        content.addView(Button(this).apply {
            text = caption
            textSize = 14f
            setTextColor(Color.WHITE)
            setBackgroundColor(orange)
            setOnClickListener { action() }
        }, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 10, 0, 6) })
    }

    private fun label(value: String, size: Float, color: Int = dark, bold: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(4, 9, 4, 9)
        }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
