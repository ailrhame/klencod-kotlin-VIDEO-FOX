package com.video.fox

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.provider.Settings
import android.view.WindowManager
import android.os.IBinder
import android.os.Build
import android.graphics.drawable.GradientDrawable
import androidx.core.content.ContextCompat

import androidx.activity.ComponentActivity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.setPadding
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.android.gms.tasks.Tasks
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import android.media.MediaMetadataRetriever

/**
 * VIDEO FOX V1.0
 * @N5CR4
 * Multi-script video OCR with context-first translation and a separate translation workspace.
 */
class MainActivity : ComponentActivity() {
    companion object { const val EXTRA_OPEN_STUDIO = "video_fox_open_translation_studio" }
    private val gold = Color.rgb(255, 184, 0)
    private val bg = Color.rgb(11, 9, 20)
    private val panel = Color.rgb(23, 19, 36)
    private val muted = Color.rgb(160, 158, 174)

    private lateinit var root: LinearLayout
    private lateinit var playerContainer: FrameLayout
    private lateinit var playerView: PlayerView
    private lateinit var regionView: RegionSelectionView
    private lateinit var statusText: TextView
    private var studioVisible = false
    private lateinit var player: ExoPlayer

    private val mainHandler = Handler(Looper.getMainLooper())
    private val frameExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val translationExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val frameBusy = AtomicBoolean(false)
    // Parallel script recognizers support mixed Latin/Japanese/Korean/Chinese subtitle frames.
    private val recognizers: List<TextRecognizer> by lazy { listOf(
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS),
        TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build()),
        TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build()),
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    ) }
    private var retriever: MediaMetadataRetriever? = null
    private var selectedUri: Uri? = null
    private var region = RectF(0.08f, 0.72f, 0.92f, 0.96f) // normalized to video content
    private var regionChosen = false
    private var selectingRegion = false
    private var videoAspect = 16f / 9f
    private var candidateText = ""
    private var candidateHits = 0
    private var activeText = ""
    private var contextLines = ArrayDeque<String>()
    private var lastTranslation = ""
    private var scanEnabled = false
    private var aiEndpoint = ""
    private var aiApiKey = ""
    private var aiModel = ""
    private data class SubtitleCue(val startMs: Long, var endMs: Long, val source: String, var translated: String)
    private val subtitleCues = mutableListOf<SubtitleCue>()
    private var currentVideoName = "فيديو"
    private var currentVideoKey = ""
    private var lastSavedVideoUri = ""
    private var glossary = linkedMapOf<String, String>()
    private var scanIntervalMs = 900L
    private var translationStyle = "طبيعية وسياقية"
    private val createSrt = registerForActivityResult(ActivityResultContracts.CreateDocument("application/x-subrip")) { uri ->
        if (uri != null) exportSrt(uri)
    }

    private val scanRunnable = object : Runnable {
        override fun run() {
            if (scanEnabled && ::player.isInitialized && player.isPlaying && selectedUri != null) {
                captureAndRecognize(player.currentPosition)
            }
            mainHandler.postDelayed(this, scanIntervalMs)
        }
    }

    private val pickVideo = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) openVideo(uri)
    }

    private val screenCaptureRequest = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val serviceIntent = Intent(this, VideoFoxLensService::class.java).apply {
                action = VideoFoxLensService.ACTION_START
                putExtra(VideoFoxLensService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(VideoFoxLensService.EXTRA_RESULT_DATA, result.data)
            }
            ContextCompat.startForegroundService(this, serviceIntent)
            toast("تم تشغيل عدسة VIDEO FOX")
        } else {
            toast("لم يتم منح إذن التقاط الشاشة")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        window.decorView.systemUiVisibility = 0
        buildUi()
        player = ExoPlayer.Builder(this).build()
        playerView.player = player
        playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) startScanning() else status("متوقف مؤقتاً")
            }
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.height > 0) {
                    videoAspect = (videoSize.width * videoSize.pixelWidthHeightRatio) / videoSize.height.toFloat()
                    regionView.invalidate()
                }
            }
        })
        loadSettings()
        mainHandler.post(scanRunnable)
        if (intent?.getBooleanExtra(EXTRA_OPEN_STUDIO, false) == true) showSubtitleStudio()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_STUDIO, false)) showSubtitleStudio()
    }

    private fun buildUi() {
        studioVisible = false
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(14))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val brand = TextView(this).apply {
            text = "VIDEO FOX  🦊"
            textSize = 23f
            setTextColor(gold)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        header.addView(brand, LinearLayout.LayoutParams(0, dp(48), 1f))
        val library = button("▤") { showLibrary() }
        header.addView(library, LinearLayout.LayoutParams(dp(48), dp(44)))
        val settings = button("⚙") { showFoxDashboard() }
        header.addView(settings, LinearLayout.LayoutParams(dp(48), dp(44)))
        root.addView(header)

        val intro = text("التقط الحوار، ثم راجعه مترجماً في مساحة مستقلة", 13f, muted)
        root.addView(intro)
        val choose = button("اختيار فيديو من الهاتف") { pickVideo.launch(arrayOf("video/*")) }
        root.addView(choose, matchWrap())
        root.addView(button("🦊 تشغيل عدسة VIDEO FOX العائمة") { startFloatingLens() }, matchWrap())
        root.addView(button("مركز الترجمة · عرض منفصل") { showSubtitleStudio() }, matchWrap())
        val tools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tools.addView(button("تصدير SRT") { if (subtitleCues.isEmpty()) toast("لا توجد ترجمة لتصديرها") else createSrt.launch("${safeFileName(currentVideoName)}.srt") }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(4) })
        tools.addView(button("مركز الترجمة") { showSubtitleStudio() }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginStart = dp(4) })
        root.addView(tools, matchWrap())

        playerContainer = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        playerView = PlayerView(this).apply {
            useController = true
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
        }
        playerContainer.addView(playerView, FrameLayout.LayoutParams(-1, -1))
        regionView = RegionSelectionView(this) { normalized ->
            region = normalized
            regionChosen = true
            selectingRegion = false
            regionView.setSelectionMode(false)
            status("تم حفظ منطقة الترجمة")
        }
        playerContainer.addView(regionView, FrameLayout.LayoutParams(-1, -1))
        // VIDEO FOX keeps the source video clean: translations are shown only in the separate studio.
        root.addView(playerContainer, LinearLayout.LayoutParams(-1, 0, 1f).apply {
            topMargin = dp(12)
            bottomMargin = dp(10)
        })

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(button("تحديد منطقة النص") { beginRegionSelection() }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(6) })
        actions.addView(button("بدء / إيقاف الترجمة") {
            if (selectedUri == null) toast("اختر فيديو أولاً") else {
                scanEnabled = !scanEnabled
                status(if (scanEnabled) "الترجمة مفعلة" else "الترجمة متوقفة")
                if (scanEnabled && player.isPlaying) captureAndRecognize(player.currentPosition)
            }
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(6) })
        root.addView(actions)
        statusText = text("جاهز — اختر فيديو للبدء", 12f, muted).apply { setPadding(2, dp(8), 2, 0) }
        root.addView(statusText)
        setContentView(root)
    }

    private fun startFloatingLens() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            toast("اسمح بالظهور فوق التطبيقات، ثم ارجع واضغط زر العدسة")
            return
        }
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureRequest.launch(manager.createScreenCaptureIntent())
    }

    private fun openVideo(uri: Uri) {
        selectedUri = uri
        currentVideoName = queryDisplayName(uri) ?: "فيديو ${System.currentTimeMillis()}"
        lastSavedVideoUri = uri.toString()
        currentVideoKey = uri.toString().hashCode().toUInt().toString(16)
        saveVideoToLibrary()
        try { contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) { }
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
        player.playWhenReady = true
        regionChosen = false
        region = RectF(0.08f, 0.72f, 0.92f, 0.96f)
        resetSubtitleState()
        subtitleCues.clear()
        loadSubtitleCues()
        status("تم فتح الفيديو — ستظهر النتائج في مركز الترجمة")
        frameExecutor.execute {
            try {
                retriever?.release()
                retriever = MediaMetadataRetriever().apply { setDataSource(this@MainActivity, uri) }
            } catch (e: Exception) {
                mainHandler.post { status("تعذر تجهيز تحليل الفيديو: ${e.localizedMessage ?: "خطأ"}") }
            }
        }
    }

    private fun beginRegionSelection() {
        if (selectedUri == null) { toast("اختر فيديو أولاً"); return }
        selectingRegion = true
        regionView.setSelectionMode(true)
        status("اسحب إصبعك لرسم مستطيل حول الترجمة")
    }

    private fun startScanning() {
        if (scanEnabled) status("الترجمة تعمل")
    }

    private fun captureAndRecognize(positionMs: Long) {
        if (!frameBusy.compareAndSet(false, true)) return
        frameExecutor.execute {
            var handedOff = false
            var frameBitmap: Bitmap? = null
            var cropBitmap: Bitmap? = null
            try {
                val sourceRetriever = retriever ?: return@execute
                frameBitmap = sourceRetriever.getFrameAtTime(positionMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST)
                    ?: return@execute
                val source = frameBitmap!!
                val rect = RectF(region)
                val left = (rect.left * source.width).toInt().coerceIn(0, source.width - 1)
                val top = (rect.top * source.height).toInt().coerceIn(0, source.height - 1)
                val right = (rect.right * source.width).toInt().coerceIn(left + 1, source.width)
                val bottom = (rect.bottom * source.height).toInt().coerceIn(top + 1, source.height)
                cropBitmap = Bitmap.createBitmap(source, left, top, right - left, bottom - top)
                val frameForCleanup = frameBitmap
                val cropForCleanup = cropBitmap!!
                val input = InputImage.fromBitmap(cropForCleanup, 0)
                val recognitionTasks = recognizers.map { engine ->
                    engine.process(input).continueWith { task -> if (task.isSuccessful) task.result?.text.orEmpty() else "" }
                }
                Tasks.whenAllSuccess<String>(recognitionTasks)
                    .addOnSuccessListener { results ->
                        val raw = results.filter { it.isNotBlank() }.distinct().joinToString(" ")
                        processRecognizedText(raw, positionMs)
                    }
                    .addOnFailureListener { e -> mainHandler.post { status("OCR: ${e.localizedMessage ?: "تعذر التعرف"}") } }
                    .addOnCompleteListener {
                        try { cropForCleanup.recycle() } catch (_: Exception) { }
                        try { frameForCleanup?.recycle() } catch (_: Exception) { }
                        frameBusy.set(false)
                    }
                handedOff = true
            } catch (e: Exception) {
                mainHandler.post { status("خطأ تحليل الإطار: ${e.localizedMessage ?: "غير معروف"}") }
            } finally {
                if (!handedOff) {
                    try { cropBitmap?.recycle() } catch (_: Exception) { }
                    try { frameBitmap?.recycle() } catch (_: Exception) { }
                    frameBusy.set(false)
                }
            }
        }
    }

    private fun processRecognizedText(raw: String, positionMs: Long) {
        val clean = raw.replace('\n', ' ').replace(Regex("\\s+"), " ").trim()
        if (clean.length < 2) {
            activeText = ""
            return
        }
        if (clean.equals(candidateText, ignoreCase = true)) candidateHits++ else {
            candidateText = clean
            candidateHits = 1
        }
        // Require repeated recognition to reduce flicker and partial-frame OCR noise.
        if (candidateHits < 2 || clean.equals(activeText, ignoreCase = true)) return
        activeText = clean
        if (subtitleCues.isNotEmpty()) { subtitleCues.last().endMs = positionMs.coerceAtLeast(subtitleCues.last().startMs + 250); persistSubtitleCues() }
        contextLines.addLast(clean)
        while (contextLines.size > 6) contextLines.removeFirst()
        subtitleCues.add(SubtitleCue(positionMs, positionMs + 2500, clean, ""))
        persistSubtitleCues()
        mainHandler.post { status("تم التقاط مقطع جديد عند ${formatTime(positionMs)} — جارٍ بناء الترجمة السياقية") }
        translateSubtitle(clean, contextLines.toList())
    }

    private fun translateSubtitle(text: String, context: List<String>) {
        if (text.length > 1200) return
        if (aiEndpoint.isBlank() || aiModel.isBlank()) {
            mainHandler.post { status("المحرك السياقي غير مضبوط. افتح إعدادات محرك المعنى لإكمال الترجمة") }
            return
        }
        contextualAiTranslate(context.distinct().takeLast(8), text) { result ->
            if (result != null) showTranslation(text, result)
            else mainHandler.post { status("تعذرت الترجمة السياقية لهذا المقطع؛ لم يتم استبدالها بترجمة حرفية") }
        }
    }

    // Deliberately no literal/local fallback: strict mode requires a context-aware engine.
    private fun contextualAiTranslate(context: List<String>, current: String, callback: (String?) -> Unit) {
        translationExecutor.execute {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(aiEndpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"; connectTimeout = 12000; readTimeout = 25000; doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    if (aiApiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer $aiApiKey")
                }
                val dialogue = context.joinToString("\n")
                val style = translationStyle
                val glossaryText = glossary.entries.take(120).joinToString("\n") { "${it.key} = ${it.value}" }
                val prompt = "مهمتك ترجمة حوار مرئي من مسلسل أو فيلم أو أنمي أو فيديو إلى العربية الفصحى السلسة. اعتمد على معنى المشهد والسياق ونبرة المتحدث، لا على مقابلة الكلمات كلمة بكلمة. أعد صياغة التعابير الاصطلاحية والنكات والسخرية والتهديد والانفعال بما يؤدي معناها الطبيعي بالعربية. حافظ على أسماء الشخصيات والمصطلحات المتكررة وفق المعجم المرفق. لا تخترع أحداثاً أو معلومات غير موجودة، ولا تشرح ولا تضف مقدمات أو علامات اقتباس. أخرج الترجمة العربية وحدها للمقطع المطلوب، ولا تترجم أسطر السياق السابقة مرة أخرى. إذا كان النص ناقصاً بسبب OCR فاستنتج فقط ما يسمح به السياق ولا تكمل كلاماً غير ظاهر. أسلوب الصياغة: $style.\nالمعجم:\n$glossaryText\nالسياق المتتابع:\n$dialogue\nالمقطع المطلوب ترجمته الآن:\n$current"
                val messages = org.json.JSONArray()
                    .put(JSONObject().put("role", "system").put("content", "أنت مترجم حوارات محترف. تنقل المعنى المقصود إلى عربية طبيعية سياقية لا حرفية، وتحافظ على نبرة المتحدث واتساق المصطلحات. لا تختلق ولا تشرح. أخرج الترجمة وحدها."))
                    .put(JSONObject().put("role", "user").put("content", prompt))
                val body = JSONObject().put("model", aiModel).put("messages", messages).put("temperature", 0.25)
                OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { it.write(body.toString()) }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val response = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
                val rawResult = if (code in 200..299) JSONObject(response).optJSONArray("choices")?.optJSONObject(0)
                    ?.optJSONObject("message")?.optString("content")?.trim().orEmpty() else ""
                val result = rawResult.takeIf { candidate ->
                    candidate.isNotBlank() && !candidate.equals(current, ignoreCase = true) && candidate.any { it in '\u0600'..'\u06FF' }
                }
                mainHandler.post { callback(result) }
            } catch (_: Exception) { mainHandler.post { callback(null) } }
            finally { connection?.disconnect() }
        }
    }

    private fun showTranslation(sourceText: String, value: String) {
        val finalValue = applyGlossary(value).trim()
        if (finalValue.isBlank()) return
        lastTranslation = finalValue
        subtitleCues.lastOrNull()?.takeIf { it.source == sourceText }?.translated = finalValue
        persistSubtitleCues()
        mainHandler.post { status("حُفظت ترجمة سياقية جديدة في مركز الترجمة") }
    }

    private fun showFoxDashboard() {
        val items = arrayOf("إعدادات محرك الترجمة", "الجودة وسرعة الالتقاط", "المعجم الشخصي", "استوديو الترجمة", "مكتبة الفيديو")
        AlertDialog.Builder(this).setTitle("VIDEO FOX · مركز التحكم")
            .setItems(items) { _, which -> when (which) {
                0 -> showRemoteSettings()
                1 -> showQualitySettings()
                2 -> showGlossaryEditor()
                3 -> showSubtitleStudio()
                4 -> showLibrary()
            } }.show()
    }

    private fun showQualitySettings() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16)) }
        val interval = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("دقيق · 600ms", "متوازن · 900ms", "موفر للطاقة · 1400ms"))
            setSelection(when (scanIntervalMs) { in 0..700 -> 0; in 701..1100 -> 1; else -> 2 })
        }
        val style = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("طبيعية وسياقية", "حوار أنمي", "رسمية", "مختصرة"))
            setSelection(listOf("طبيعية وسياقية", "حوار أنمي", "رسمية", "مختصرة").indexOf(translationStyle).coerceAtLeast(0))
        }
        box.addView(text("فاصل التقاط النص", 14f, gold)); box.addView(interval)
        box.addView(text("أسلوب الصياغة العربية", 14f, gold)); box.addView(style)
        AlertDialog.Builder(this).setTitle("الجودة والسياق").setView(box)
            .setPositiveButton("حفظ") { _, _ ->
                scanIntervalMs = when (interval.selectedItemPosition) { 0 -> 600L; 2 -> 1400L; else -> 900L }
                translationStyle = style.selectedItem.toString()
                getSharedPreferences("video_fox", MODE_PRIVATE).edit()
                    .putLong("scan_interval", scanIntervalMs).putString("translation_style", translationStyle).apply()
                mainHandler.removeCallbacks(scanRunnable); mainHandler.post(scanRunnable)
                status("تم حفظ إعدادات الالتقاط والصياغة")
            }.setNegativeButton("إلغاء", null).show()
    }

    private fun showGlossaryEditor() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16)) }
        val source = EditText(this).apply { hint = "المصطلح الأصلي" }
        val target = EditText(this).apply { hint = "المعنى العربي المفضل" }
        box.addView(source); box.addView(target)
        val entries = glossary.entries.joinToString("\n") { "${it.key} ← ${it.value}" }
        box.addView(text(if (entries.isBlank()) "لا توجد مصطلحات محفوظة" else entries, 12f, muted))
        AlertDialog.Builder(this).setTitle("المعجم الشخصي").setView(box)
            .setPositiveButton("إضافة") { _, _ ->
                val a = source.text.toString().trim(); val b = target.text.toString().trim()
                if (a.isNotBlank() && b.isNotBlank()) { glossary[a] = b; saveGlossary(); toast("تمت إضافة المصطلح") }
            }.setNeutralButton("إغلاق", null).show()
    }

    private fun applyGlossary(value: String): String {
        var result = value
        glossary.forEach { (source, target) -> result = result.replace(source, target, ignoreCase = true) }
        return result
    }

    private fun saveGlossary() {
        getSharedPreferences("video_fox", MODE_PRIVATE).edit().putString("glossary", JSONObject(glossary as Map<*, *>).toString()).apply()
    }

    private fun showSubtitleStudio() {
        studioVisible = true
        if (::player.isInitialized) player.pause()
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(14))
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(button("‹") { returnToHome() }, LinearLayout.LayoutParams(dp(48), dp(44)))
        val title = text("مركز الترجمة", 21f, gold).apply { typeface = android.graphics.Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER_VERTICAL }
        header.addView(title, LinearLayout.LayoutParams(0, dp(48), 1f))
        header.addView(button("⚙") { showRemoteSettings() }, LinearLayout.LayoutParams(dp(48), dp(44)))
        page.addView(header)
        page.addView(text("الترجمة محفوظة هنا بشكل مستقل عن الفيديو والتطبيقات الأخرى", 12f, muted))
        if (aiEndpoint.isBlank() || aiModel.isBlank()) {
            val warning = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12))
                background = GradientDrawable().apply { setColor(0xFF221B29.toInt()); cornerRadius = dp(14).toFloat(); setStroke(dp(1), 0xFFFFB800.toInt()) }
            }
            warning.addView(text("محرك الترجمة السياقية غير مضبوط", 14f, gold))
            warning.addView(text("لن يعرض VIDEO FOX ترجمة حرفية بديلة. اضبط نموذجاً متوافقاً مع Chat Completions للترجمة حسب السياق.", 12f, Color.WHITE))
            warning.addView(button("إعداد المحرك") { showRemoteSettings() })
            val warningParams = LinearLayout.LayoutParams(-1, -2); warningParams.topMargin = dp(10); warningParams.bottomMargin = dp(4)
            page.addView(warning, warningParams)
        }

        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(12), 0, dp(18)) }
        val lensEntries = loadLensTranslationEntries()
        list.addView(text("مقاطع الفيديو المختارة · ${subtitleCues.size}", 15f, gold))
        if (subtitleCues.isEmpty()) {
            list.addView(text("لا توجد مقاطع من فيديو الهاتف بعد. اختر فيديو وفعّل الالتقاط.", 13f, muted))
        } else {
            subtitleCues.forEachIndexed { index, cue ->
                val card = translationCard()
                card.addView(text("${index + 1} · ${formatTime(cue.startMs)}", 11f, gold))
                card.addView(text(cue.source, 13f, Color.WHITE))
                card.addView(text(cue.translated.ifBlank { "بانتظار المحرك السياقي — لم تُستخدم ترجمة حرفية" }, 15f, if (cue.translated.isBlank()) muted else gold))
                if (cue.translated.isBlank()) card.addView(button("إعادة الترجمة السياقية") { retryVideoCue(index) })
                card.addView(button("تحرير الترجمة") { editCue(index) })
                addTranslationCard(list, card)
            }
        }

        list.addView(text("جلسات العدسة العائمة · ${lensEntries.length()}", 15f, gold).apply { setPadding(0, dp(14), 0, dp(4)) })
        if (lensEntries.length() == 0) {
            list.addView(text("لم تُسجل العدسة أي مقاطع بعد. شغّل العدسة من داخل التطبيق الذي يعرض الفيديو.", 13f, muted))
        } else {
            var shownSession = ""
            for (i in 0 until lensEntries.length()) {
                val item = lensEntries.optJSONObject(i) ?: continue
                val session = item.optString("sessionId")
                if (session != shownSession) {
                    shownSession = session
                    val firstTime = item.optLong("capturedAt")
                    val sessionLabel = java.text.SimpleDateFormat("yyyy/MM/dd · HH:mm", Locale.getDefault()).format(java.util.Date(firstTime))
                    list.addView(text("جلسة التقاط · $sessionLabel", 13f, gold).apply { setPadding(0, dp(10), 0, dp(4)) })
                }
                val source = item.optString("source")
                val translated = item.optString("translated")
                val time = item.optLong("capturedAt")
                val card = translationCard()
                card.addView(text("${i + 1} · ${java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(java.util.Date(time))}", 11f, gold))
                card.addView(text(source, 13f, Color.WHITE))
                card.addView(text(translated.ifBlank { when (item.optString("status")) { "error" -> "تعذرت الترجمة السياقية — أعد المحاولة بعد فحص المحرك"; "needs_engine" -> "المحرك غير مضبوط — أدخل إعدادات محرك المعنى"; else -> "بانتظار محرك الترجمة السياقية" } }, 15f, if (translated.isBlank()) muted else gold))
                if (translated.isBlank()) card.addView(button("إعادة المحاولة بالمحرك السياقي") { retryLensEntry(item.optString("id")) })
                card.addView(button("تحرير الترجمة") { editLensEntry(item.optString("id")) })
                addTranslationCard(list, card)
            }
        }
        if (subtitleCues.isNotEmpty()) list.addView(button("تصدير ترجمة الفيديو SRT") {
            createSrt.launch("${safeFileName(currentVideoName)}.srt")
        }, matchWrap())
        list.addView(button("إعداد محرك الترجمة السياقية") { showRemoteSettings() }, matchWrap())
        scroll.addView(list)
        page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(page)
    }

    private fun translationCard() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12))
        background = GradientDrawable().apply { setColor(panel); cornerRadius = dp(16).toFloat(); setStroke(dp(1), 0x553F354F) }
    }

    private fun addTranslationCard(parent: LinearLayout, card: LinearLayout) {
        val params = LinearLayout.LayoutParams(-1, -2)
        params.bottomMargin = dp(9)
        parent.addView(card, params)
    }

    private fun loadLensTranslationEntries(): org.json.JSONArray {
        val raw = getSharedPreferences("video_fox_lens", MODE_PRIVATE).getString("entries", "[]") ?: "[]"
        return runCatching { org.json.JSONArray(raw) }.getOrDefault(org.json.JSONArray())
    }

    private fun retryVideoCue(index: Int) {
        val cue = subtitleCues.getOrNull(index) ?: return
        if (aiEndpoint.isBlank() || aiModel.isBlank()) { showRemoteSettings(); return }
        val context = subtitleCues.take(index + 1).takeLast(8).map { it.source }
        status("إعادة بناء الترجمة من سياق المقاطع السابقة…")
        contextualAiTranslate(context, cue.source) { translated ->
            if (translated.isNullOrBlank()) toast("لم ينجح المحرك السياقي؛ لم يتم استخدام ترجمة حرفية")
            else { cue.translated = applyGlossary(translated); persistSubtitleCues(); showSubtitleStudio() }
        }
    }

    private fun retryLensEntry(id: String) {
        if (aiEndpoint.isBlank() || aiModel.isBlank()) { showRemoteSettings(); return }
        val entries = loadLensTranslationEntries()
        val index = (0 until entries.length()).firstOrNull { entries.optJSONObject(it)?.optString("id") == id } ?: return
        val item = entries.optJSONObject(index) ?: return
        val context = item.optJSONArray("context")?.let { arr -> (0 until arr.length()).map { arr.optString(it) } } ?: listOf(item.optString("source"))
        contextualAiTranslate(context, item.optString("source")) { translated ->
            if (translated.isNullOrBlank()) toast("لم ينجح المحرك السياقي؛ لم يتم استخدام ترجمة حرفية")
            else {
                item.put("translated", applyGlossary(translated)); item.put("status", "done")
                getSharedPreferences("video_fox_lens", MODE_PRIVATE).edit().putString("entries", entries.toString()).apply()
                showSubtitleStudio()
            }
        }
    }

    private fun editLensEntry(id: String) {
        val entries = loadLensTranslationEntries()
        var found: JSONObject? = null
        for (i in 0 until entries.length()) {
            val item = entries.optJSONObject(i) ?: continue
            if (item.optString("id") == id) { found = item; break }
        }
        val item = found ?: return
        val editor = EditText(this).apply { setText(item.optString("translated")); minLines = 3; gravity = Gravity.TOP }
        AlertDialog.Builder(this).setTitle("تحرير الترجمة").setView(editor)
            .setPositiveButton("حفظ") { _, _ ->
                item.put("translated", editor.text.toString().trim())
                item.put("status", "done")
                getSharedPreferences("video_fox_lens", MODE_PRIVATE).edit().putString("entries", entries.toString()).apply()
                showSubtitleStudio()
            }.setNegativeButton("إلغاء", null).show()
    }

    private fun editCue(index: Int) {
        val cue = subtitleCues.getOrNull(index) ?: return
        val input = EditText(this).apply { setText(cue.translated); minLines = 2; gravity = Gravity.TOP }
        AlertDialog.Builder(this).setTitle("تحرير الترجمة رقم ${index + 1}").setView(input)
            .setPositiveButton("حفظ") { _, _ -> cue.translated = input.text.toString().trim(); persistSubtitleCues(); showSubtitleStudio() }
            .setNegativeButton("إلغاء", null).show()
    }

    private fun showLibrary() {
        val prefs = getSharedPreferences("video_fox", MODE_PRIVATE)
        val items = prefs.getString("video_library", "[]") ?: "[]"
        val arr = runCatching { org.json.JSONArray(items) }.getOrDefault(org.json.JSONArray())
        if (arr.length() == 0) { AlertDialog.Builder(this).setTitle("مكتبة VIDEO FOX").setMessage("ستظهر هنا الفيديوهات التي فتحتها سابقاً.").setPositiveButton("حسناً", null).show(); return }
        val labels = (0 until arr.length()).map { arr.optJSONObject(it)?.optString("name", "فيديو") ?: "فيديو" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("مكتبة الفيديو").setItems(labels) { _, which ->
            val uri = arr.optJSONObject(which)?.optString("uri").orEmpty()
            if (uri.isNotBlank()) openVideo(Uri.parse(uri))
        }.setNegativeButton("إغلاق", null).show()
    }

    private fun saveVideoToLibrary() {
        val prefs = getSharedPreferences("video_fox", MODE_PRIVATE)
        val old = runCatching { org.json.JSONArray(prefs.getString("video_library", "[]")) }.getOrDefault(org.json.JSONArray())
        val updated = org.json.JSONArray().put(JSONObject().put("name", currentVideoName).put("uri", lastSavedVideoUri))
        for (i in 0 until old.length()) {
            val item = old.optJSONObject(i) ?: continue
            if (item.optString("uri") != lastSavedVideoUri && updated.length() < 30) updated.put(item)
        }
        prefs.edit().putString("video_library", updated.toString()).apply()
    }

    private fun persistSubtitleCues() {
        if (currentVideoKey.isBlank()) return
        val array = org.json.JSONArray()
        subtitleCues.forEach { cue -> array.put(JSONObject().put("start", cue.startMs).put("end", cue.endMs).put("source", cue.source).put("translated", cue.translated)) }
        getSharedPreferences("video_fox", MODE_PRIVATE).edit().putString("cues_$currentVideoKey", array.toString()).apply()
    }

    private fun loadSubtitleCues() {
        if (currentVideoKey.isBlank()) return
        val raw = getSharedPreferences("video_fox", MODE_PRIVATE).getString("cues_$currentVideoKey", "[]") ?: "[]"
        val array = runCatching { org.json.JSONArray(raw) }.getOrDefault(org.json.JSONArray())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            subtitleCues.add(SubtitleCue(item.optLong("start"), item.optLong("end"), item.optString("source"), item.optString("translated")))
        }
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull()

    private fun exportSrt(uri: Uri) {
        try {
            contentResolver.openOutputStream(uri)?.use { output ->
                OutputStreamWriter(output, Charsets.UTF_8).use { writer ->
                    subtitleCues.forEachIndexed { index, cue ->
                        val start = cue.startMs
                        val end = cue.endMs.coerceAtLeast(start + 500)
                        if (cue.translated.isNotBlank()) writer.write("${index + 1}\n${srtTime(start)} --> ${srtTime(end)}\n${cue.translated}\n\n")
                    }
                }
            }
            toast("تم تصدير ملف الترجمة")
        } catch (e: Exception) { toast("فشل التصدير: ${e.localizedMessage}") }
    }

    private fun srtTime(ms: Long): String {
        val h = ms / 3600000; val m = (ms / 60000) % 60; val sec = (ms / 1000) % 60; val milli = ms % 1000
        return String.format(Locale.US, "%02d:%02d:%02d,%03d", h, m, sec, milli)
    }

    private fun safeFileName(value: String) = value.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9_-]"), "_").take(48).ifBlank { "video_fox" }

    private fun showRemoteSettings() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18)) }
        val endpointInput = EditText(this).apply {
            hint = "https://.../v1/chat/completions"
            setText(aiEndpoint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val modelInput = EditText(this).apply { hint = "معرّف النموذج السياقي"; setText(aiModel) }
        val keyInput = EditText(this).apply {
            hint = "مفتاح API (اتركه فارغاً للخادم المحلي)"
            setText(aiApiKey)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        box.addView(text("محرك الترجمة السياقية", 16f, gold))
        box.addView(text("VIDEO FOX لا يستخدم الترجمة الحرفية كبديل صامت. يلزم خادم متوافق مع OpenAI Chat Completions؛ يمكن أن يكون خادماً محلياً تديره بنفسك.", 13f, muted))
        box.addView(text("عنوان Chat Completions", 13f, Color.WHITE)); box.addView(endpointInput)
        box.addView(text("اسم النموذج", 13f, Color.WHITE)); box.addView(modelInput)
        box.addView(text("مفتاح الوصول", 13f, Color.WHITE)); box.addView(keyInput)
        AlertDialog.Builder(this).setTitle("إعداد محرك المعنى")
            .setView(box)
            .setPositiveButton("حفظ") { _, _ ->
                aiEndpoint = endpointInput.text.toString().trim()
                aiModel = modelInput.text.toString().trim()
                aiApiKey = keyInput.text.toString().trim()
                getSharedPreferences("video_fox", MODE_PRIVATE).edit()
                    .putString("ai_endpoint", aiEndpoint).putString("ai_model", aiModel).putString("ai_key", aiApiKey).apply()
                status(if (aiEndpoint.isNotBlank() && aiModel.isNotBlank()) "تم حفظ محرك الترجمة السياقية" else "لم يتم تفعيل المحرك: العنوان واسم النموذج مطلوبان")
                if (studioVisible) showSubtitleStudio()
            }
            .setNegativeButton("إلغاء", null).show()
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences("video_fox", MODE_PRIVATE)
        aiEndpoint = prefs.getString("ai_endpoint", "") ?: ""
        aiModel = prefs.getString("ai_model", "") ?: ""
        aiApiKey = prefs.getString("ai_key", "") ?: ""
        scanIntervalMs = prefs.getLong("scan_interval", 900L)
        translationStyle = prefs.getString("translation_style", "طبيعية وسياقية") ?: "طبيعية وسياقية"
        val savedGlossary = runCatching { JSONObject(prefs.getString("glossary", "{}")) }.getOrNull()
        savedGlossary?.keys()?.forEach { key -> glossary[key] = savedGlossary.optString(key) }
    }

    private fun resetSubtitleState() {
        candidateText = ""
        candidateHits = 0
        activeText = ""
        contextLines.clear()
    }

    private fun contentRectFor(view: View): RectF {
        val vw = view.width.toFloat().coerceAtLeast(1f)
        val vh = view.height.toFloat().coerceAtLeast(1f)
        val viewAspect = vw / vh
        return if (viewAspect > videoAspect) {
            val w = vh * videoAspect
            val x = (vw - w) / 2f
            RectF(x, 0f, x + w, vh)
        } else {
            val h = vw / videoAspect
            val y = (vh - h) / 2f
            RectF(0f, y, vw, y + h)
        }
    }

    private inner class RegionSelectionView(context: Context, val onDone: (RectF) -> Unit) : View(context) {
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = gold; style = Paint.Style.STROKE; strokeWidth = dp(2).toFloat()
        }
        private val shadePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55000000 }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x22FFB800 }
        private var startX = 0f
        private var startY = 0f
        private var current = RectF()
        private var drawing = false
        private var mode = false
        fun setSelectionMode(enabled: Boolean) { mode = enabled; visibility = if (enabled) VISIBLE else GONE; invalidate() }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (!mode) return
            val content = contentRectFor(this)
            canvas.drawRect(content, shadePaint)
            if (drawing || !current.isEmpty) {
                canvas.drawRect(current, fillPaint)
                canvas.drawRect(current, borderPaint)
            }
            val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = dp(13).toFloat(); textAlign = Paint.Align.CENTER }
            canvas.drawText("ارسم مستطيلاً حول سطر الترجمة ثم ارفع إصبعك", width / 2f, dp(28).toFloat(), hint)
        }
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (!mode) return false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.x; startY = event.y
                    current = RectF(startX, startY, startX, startY)
                    drawing = true; invalidate(); return true
                }
                MotionEvent.ACTION_MOVE -> {
                    current.set(startX, startY, event.x, event.y); current.sort(); invalidate(); return true
                }
                MotionEvent.ACTION_UP -> {
                    current.set(startX, startY, event.x, event.y); current.sort(); drawing = false
                    val content = contentRectFor(this)
                    val clipped = RectF(current).apply {
                        left = left.coerceIn(content.left, content.right)
                        right = right.coerceIn(content.left, content.right)
                        top = top.coerceIn(content.top, content.bottom)
                        bottom = bottom.coerceIn(content.top, content.bottom)
                    }
                    if (clipped.width() > dp(20) && clipped.height() > dp(8)) {
                        val normalized = RectF(
                            ((clipped.left - content.left) / content.width()).coerceIn(0f, 1f),
                            ((clipped.top - content.top) / content.height()).coerceIn(0f, 1f),
                            ((clipped.right - content.left) / content.width()).coerceIn(0f, 1f),
                            ((clipped.bottom - content.top) / content.height()).coerceIn(0f, 1f)
                        )
                        onDone(normalized)
                    } else {
                        Toast.makeText(this@MainActivity, "المنطقة صغيرة جداً، حاول مرة أخرى", Toast.LENGTH_SHORT).show()
                    }
                    invalidate(); return true
                }
            }
            return true
        }
    }

    private fun returnToHome() {
        studioVisible = false
        buildUi()
        playerView.player = player
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (studioVisible) returnToHome() else super.onBackPressed()
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 13f
        setTextColor(Color.BLACK)
        setBackgroundTintList(android.content.res.ColorStateList.valueOf(gold))
        setOnClickListener { action() }
    }

    private fun text(value: String, size: Float, color: Int) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color)
    }

    private fun matchWrap() = LinearLayout.LayoutParams(-1, -2)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun status(value: String) { if (::statusText.isInitialized) statusText.text = value }
    private fun toast(value: String) = Toast.makeText(this, value, Toast.LENGTH_SHORT).show()
    private fun formatTime(ms: Long): String = String.format(Locale.US, "%02d:%02d", ms / 60000, (ms / 1000) % 60)

    override fun onPause() {
        super.onPause()
        if (::player.isInitialized) player.pause()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(scanRunnable)
        scanEnabled = false
        try { retriever?.release() } catch (_: Exception) { }
        try { recognizers.forEach { it.close() } } catch (_: Exception) { }
        if (::player.isInitialized) player.release()
        frameExecutor.shutdownNow()
        translationExecutor.shutdownNow()
        super.onDestroy()
    }
}


/**
 * VIDEO FOX floating capture controller.
 * The lens never renders translations over the source application. It only controls OCR capture
 * and opens the separate in-app Translation Studio. Contextual AI is the only translation path.
 * @N5CR4
 */
class VideoFoxLensService : Service() {
    companion object {
        const val ACTION_START = "com.video.fox.action.START_LENS"
        const val EXTRA_RESULT_CODE = "video_fox_result_code"
        const val EXTRA_RESULT_DATA = "video_fox_result_data"
        private const val CHANNEL_ID = "video_fox_capture"
        private const val NOTIFICATION_ID = 5105
        private const val STORE_NAME = "video_fox_lens"
        private const val STORE_KEY = "entries"
        private const val MAX_STORED_ENTRIES = 2000
    }

    private lateinit var windowManager: WindowManager
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var lensView: FoxLensView? = null
    private var controlsPanel: LinearLayout? = null
    private var controlsStatus: TextView? = null
    private var scanButton: TextView? = null
    private var controlsVisible = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    private var scanning = false
    private var lastFrameAt = 0L
    private var lastText = ""
    private var stableHits = 0
    private var sessionId = ""
    private val contextLines = ArrayDeque<String>()
    private val recognizers by lazy {
        listOf(
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS),
            TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build()),
            TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build()),
            TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_START) { stopSelf(); return START_NOT_STICKY }
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(NOTIFICATION_ID, notification)

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val consentData: Intent? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        if (consentData == null || resultCode != android.app.Activity.RESULT_OK) { stopSelf(); return START_NOT_STICKY }
        startScreenCapture(resultCode, consentData)
        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, "VIDEO FOX capture", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        return builder.setContentTitle("VIDEO FOX · عدسة الالتقاط")
            .setContentText("الترجمة تُحفظ في مركز مستقل — لا تظهر فوق الفيديو")
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setOngoing(true).build()
    }

    private fun startScreenCapture(resultCode: Int, consentData: Intent) {
        try {
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(resultCode, consentData)
            val metrics = resources.displayMetrics
            val width = metrics.widthPixels.coerceAtLeast(1)
            val height = metrics.heightPixels.coerceAtLeast(1)
            val density = metrics.densityDpi
            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
            projection?.registerCallback(object : MediaProjection.Callback() { override fun onStop() { stopSelf() } }, mainHandler)
            virtualDisplay = projection?.createVirtualDisplay(
                "VIDEO_FOX_CAPTURE_ONLY", width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader?.surface, null, mainHandler
            )
            imageReader?.setOnImageAvailableListener({ reader ->
                if (!scanning || System.currentTimeMillis() - lastFrameAt < 1100L) reader.acquireLatestImage()?.close()
                else { lastFrameAt = System.currentTimeMillis(); processScreenFrame(reader) }
            }, mainHandler)
            showLensAndControls()
        } catch (_: Exception) { stopSelf() }
    }

    private fun showLensAndControls() {
        val density = resources.displayMetrics.density
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val lens = FoxLensView(this).apply { setOnTouchListener(LensDragTouchListener()) }
        lensView = lens
        val lensParams = WindowManager.LayoutParams(
            (58 * density).toInt(), (58 * density).toInt(), type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.RIGHT; x = (12 * density).toInt(); y = (190 * density).toInt() }
        try { windowManager.addView(lens, lensParams) } catch (_: Exception) { stopSelf(); return }
        createControlsPanel(type, density)
    }

    private fun createControlsPanel(type: Int, density: Float) {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((14 * density).toInt(), (12 * density).toInt(), (14 * density).toInt(), (12 * density).toInt())
            background = GradientDrawable().apply { setColor(0xF214111D.toInt()); cornerRadius = 22 * density; setStroke(1.4f * density, 0xFFFFC43D.toInt()) }
            elevation = 18 * density
            visibility = View.GONE
        }
        val title = TextView(this).apply { text = "VIDEO FOX  ·  N5CR4"; textSize = 14f; setTextColor(0xFFFFC43D.toInt()); typeface = android.graphics.Typeface.DEFAULT_BOLD }
        controlsStatus = TextView(this).apply { text = "العدسة جاهزة — الترجمة منفصلة"; textSize = 12f; setTextColor(0xFFD5D0DF.toInt()); setPadding(0, (7*density).toInt(), 0, (10*density).toInt()) }
        scanButton = panelAction(if (scanning) "إيقاف الالتقاط" else "بدء الالتقاط", density) { toggleScanning() }
        val studioButton = panelAction("فتح مركز الترجمة  ↗", density) { openTranslationStudio() }
        val closeButton = panelAction("إغلاق لوحة التحكم", density) { toggleControls(false) }
        panel.addView(title); panel.addView(controlsStatus); panel.addView(scanButton); panel.addView(studioButton); panel.addView(closeButton)
        controlsPanel = panel
        val params = WindowManager.LayoutParams(
            (230 * density).toInt(), -2, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.RIGHT; x = (12*density).toInt(); y = (260*density).toInt() }
        try { windowManager.addView(panel, params) } catch (_: Exception) { controlsPanel = null }
    }

    private fun panelAction(label: String, density: Float, action: () -> Unit): TextView = TextView(this).apply {
        text = label; textSize = 13f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
        setPadding((10*density).toInt(), (11*density).toInt(), (10*density).toInt(), (11*density).toInt())
        background = GradientDrawable().apply { setColor(0xFF25202F.toInt()); cornerRadius = 13*density; setStroke(1f*density, 0x664A405A) }
        setOnClickListener { action() }
        val lp = LinearLayout.LayoutParams(-1, -2); lp.topMargin = (5*density).toInt(); layoutParams = lp
    }

    private fun toggleControls(show: Boolean) {
        controlsVisible = show
        controlsPanel?.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun toggleScanning() {
        scanning = !scanning
        if (scanning) {
            sessionId = System.currentTimeMillis().toString()
            contextLines.clear(); lastText = ""; stableHits = 0
            toggleControls(false) // keep our control drawer out of the captured video frame
        }
        lensView?.invalidate()
        scanButton?.text = if (scanning) "إيقاف الالتقاط" else "بدء الالتقاط"
        controlsStatus?.text = if (scanning) "جارٍ التقاط النص — النتائج لا تظهر فوق الفيديو" else "تم إيقاف الالتقاط — افتح مركز الترجمة للمراجعة"
    }

    private inner class LensDragTouchListener : View.OnTouchListener {
        private var downX = 0f; private var downY = 0f
        private var startX = 0; private var startY = 0
        private var dragged = false
        override fun onTouch(view: View, event: MotionEvent): Boolean {
            val params = view.layoutParams as WindowManager.LayoutParams
            when (event.action) {
                MotionEvent.ACTION_DOWN -> { downX = event.rawX; downY = event.rawY; startX = params.x; startY = params.y; dragged = false; return true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt(); val dy = (event.rawY - downY).toInt()
                    if (kotlin.math.abs(dx) + kotlin.math.abs(dy) > 9) dragged = true
                    if (dragged) {
                        val horizontalGravity = params.gravity and Gravity.HORIZONTAL_GRAVITY_MASK
                        params.x = (if (horizontalGravity == Gravity.RIGHT) startX - dx else startX + dx).coerceAtLeast(0)
                        params.y = (startY + dy).coerceAtLeast(8)
                        runCatching { windowManager.updateViewLayout(view, params) }
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragged) snapLensToEdge(view) else toggleControls(!controlsVisible)
                    return true
                }
            }
            return true
        }
    }

    private fun snapLensToEdge(view: View) {
        val params = view.layoutParams as WindowManager.LayoutParams
        val width = resources.displayMetrics.widthPixels
        val horizontalGravity = params.gravity and Gravity.HORIZONTAL_GRAVITY_MASK
        val centerX = if (horizontalGravity == Gravity.RIGHT) width - params.x - view.width / 2 else params.x + view.width / 2
        params.gravity = Gravity.TOP or (if (centerX < width / 2) Gravity.LEFT else Gravity.RIGHT)
        params.x = (10 * resources.displayMetrics.density).toInt()
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    private fun openTranslationStudio() {
        scanning = false
        lensView?.invalidate()
        scanButton?.text = "بدء الالتقاط"
        controlsStatus?.text = "تم إيقاف الالتقاط لعرض النتائج بشكل منفصل"
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(MainActivity.EXTRA_OPEN_STUDIO, true)
        }
        runCatching { startActivity(intent); toggleControls(false) }
    }

    private fun processScreenFrame(reader: ImageReader) {
        if (!busy.compareAndSet(false, true)) { reader.acquireLatestImage()?.close(); return }
        val image = reader.acquireLatestImage()
        if (image == null) { busy.set(false); return }
        worker.execute {
            var full: Bitmap? = null; var crop: Bitmap? = null; var scaled: Bitmap? = null
            var mlKitOwns = false
            try {
                val plane = image.planes[0]; val buffer = plane.buffer
                val pixelStride = plane.pixelStride; val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * image.width
                val paddedWidth = image.width + rowPadding / pixelStride
                val fullBitmap = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
                full = fullBitmap
                fullBitmap.copyPixelsFromBuffer(buffer)
                val cropBitmap = Bitmap.createBitmap(fullBitmap, 0, 0, image.width, image.height)
                crop = cropBitmap
                val maxWidth = 1200
                val scaledBitmap = if (cropBitmap.width > maxWidth) Bitmap.createScaledBitmap(cropBitmap, maxWidth, (cropBitmap.height * maxWidth.toFloat() / cropBitmap.width).toInt().coerceAtLeast(1), true) else cropBitmap
                scaled = scaledBitmap
                val input = InputImage.fromBitmap(scaledBitmap, 0)
                val tasks = recognizers.map { engine -> engine.process(input).continueWith { task -> if (task.isSuccessful) task.result?.text.orEmpty() else "" } }
                mlKitOwns = true
                Tasks.whenAllSuccess<String>(tasks).addOnSuccessListener { results ->
                    val candidates = results.filter { it.isNotBlank() }.distinct()
                    val best = candidates.maxByOrNull { scoreOcrText(it) }.orEmpty()
                    acceptRecognizedText(best)
                }.addOnCompleteListener {
                    runCatching { if (scaledBitmap !== cropBitmap) scaledBitmap.recycle() }; runCatching { cropBitmap.recycle() }; runCatching { fullBitmap.recycle() }; busy.set(false)
                }
            } catch (_: Exception) { busy.set(false) }
            finally {
                image.close()
                if (!mlKitOwns) { runCatching { if (scaled !== crop) scaled?.recycle() }; runCatching { crop?.recycle() }; runCatching { full?.recycle() } }
            }
        }
    }

    private fun scoreOcrText(value: String): Int {
        val clean = value.replace(Regex("\\s+"), " ").trim()
        val letters = clean.count { it.isLetter() }
        val noisy = clean.count { it.isISOControl() || it in "|_~`" }
        return letters * 3 + clean.length - noisy * 5
    }

    private fun acceptRecognizedText(raw: String) {
        val clean = raw.replace(Regex("\\s+"), " ").trim()
        if (clean.length < 3) return
        if (clean.equals(lastText, ignoreCase = true)) stableHits++ else { lastText = clean; stableHits = 1 }
        if (stableHits < 2) return
        // Stop multiple saves of the same subtitle while it remains on screen.
        val now = System.currentTimeMillis()
        val prefs = getSharedPreferences(STORE_NAME, MODE_PRIVATE)
        val latest = runCatching { org.json.JSONArray(prefs.getString(STORE_KEY, "[]")) }.getOrDefault(org.json.JSONArray())
        if (latest.length() > 0) {
            val prior = latest.optJSONObject(latest.length() - 1)
            if (prior?.optString("source")?.equals(clean, true) == true && now - prior.optLong("capturedAt") < 12000L) return
        }
        if (contextLines.lastOrNull()?.equals(clean, true) != true) contextLines.addLast(clean)
        while (contextLines.size > 8) contextLines.removeFirst()
        val id = "${sessionId}_${now}"
        val entry = JSONObject().put("id", id).put("sessionId", sessionId).put("capturedAt", now)
            .put("source", clean).put("context", org.json.JSONArray(contextLines.toList())).put("translated", "").put("status", "pending")
        latest.put(entry)
        trimAndSaveEntries(latest)
        val config = getSharedPreferences("video_fox", MODE_PRIVATE)
        val endpoint = config.getString("ai_endpoint", "").orEmpty()
        val model = config.getString("ai_model", "").orEmpty()
        val key = config.getString("ai_key", "").orEmpty()
        val style = config.getString("translation_style", "طبيعية وسياقية").orEmpty()
        val glossary = runCatching { JSONObject(config.getString("glossary", "{}")) }.getOrDefault(JSONObject())
        if (endpoint.isBlank() || model.isBlank()) {
            updateStoredEntry(id, "", "needs_engine")
            mainHandler.post { controlsStatus?.text = "تم الحفظ؛ اضبط محرك الترجمة السياقية من التطبيق" }
            return
        }
        requestContextualTranslation(id, clean, contextLines.toList(), endpoint, model, key, style, glossary)
    }

    private fun requestContextualTranslation(
        id: String, current: String, context: List<String>, endpoint: String, model: String,
        apiKey: String, style: String, glossary: JSONObject
    ) {
        worker.execute {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"; connectTimeout = 15000; readTimeout = 35000; doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    if (apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer $apiKey")
                }
                val glossaryText = glossary.keys().asSequence().take(120).joinToString("\n") { key -> "$key = ${glossary.optString(key)}" }
                val dialogue = context.takeLast(8).joinToString("\n")
                val systemPrompt = "أنت مترجم محترف للحوار المرئي إلى العربية. تترجم المعنى المقصود بحسب السياق ونبرة الشخصيات، لا ترجمة كلمة بكلمة. حافظ على اتساق المصطلحات والأسماء. لا تخترع سياقاً ولا تضف تفسيراً. أخرج الترجمة العربية وحدها."
                val userPrompt = "ترجم المقطع الأخير فقط إلى عربية طبيعية وغير حرفية، مع الحفاظ على المعنى والنبرة والإيقاع. تعامل مع السخرية والتعابير الاصطلاحية والتلميحات بحسب السياق. لا تعرض النص الأصلي ولا تشرح. إذا كان OCR ناقصاً فلا تخمن إلا بقدر ما يسمح السياق.\nالأسلوب: $style\nالمعجم: \n$glossaryText\nالسياق المتتابع: \n$dialogue\nالمقطع المطلوب: \n$current"
                val messages = org.json.JSONArray()
                    .put(JSONObject().put("role", "system").put("content", systemPrompt))
                    .put(JSONObject().put("role", "user").put("content", userPrompt))
                val body = JSONObject().put("model", model).put("messages", messages).put("temperature", 0.25)
                OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { it.write(body.toString()) }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val response = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
                val rawTranslation = if (code in 200..299) JSONObject(response).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content")?.trim().orEmpty() else ""
                val translated = rawTranslation.takeIf { it.isNotBlank() && !it.equals(current, true) && it.any { ch -> ch in '\u0600'..'\u06FF' } }.orEmpty()
                if (translated.isNotBlank()) {
                    var finalText = translated
                    val keys = glossary.keys().asSequence().toList().sortedByDescending { it.length }
                    keys.forEach { key -> finalText = finalText.replace(key, glossary.optString(key), true) }
                    updateStoredEntry(id, finalText, "done")
                } else updateStoredEntry(id, "", "error")
            } catch (_: Exception) { updateStoredEntry(id, "", "error") }
            finally { connection?.disconnect() }
        }
    }

    private fun updateStoredEntry(id: String, translated: String, status: String) {
        val prefs = getSharedPreferences(STORE_NAME, MODE_PRIVATE)
        val entries = runCatching { org.json.JSONArray(prefs.getString(STORE_KEY, "[]")) }.getOrDefault(org.json.JSONArray())
        for (i in 0 until entries.length()) {
            val item = entries.optJSONObject(i) ?: continue
            if (item.optString("id") == id) { item.put("translated", translated); item.put("status", status); break }
        }
        trimAndSaveEntries(entries)
        mainHandler.post { controlsStatus?.text = if (status == "done") "اكتملت ترجمة مقطع — افتح مركز الترجمة" else if (status == "error") "لم ينجح المحرك السياقي — لم نستخدم ترجمة حرفية" else "اضبط محرك الترجمة السياقية" }
    }

    private fun trimAndSaveEntries(entries: org.json.JSONArray) {
        val start = (entries.length() - MAX_STORED_ENTRIES).coerceAtLeast(0)
        val compact = org.json.JSONArray()
        for (i in start until entries.length()) entries.optJSONObject(i)?.let { compact.put(it) }
        getSharedPreferences(STORE_NAME, MODE_PRIVATE).edit().putString(STORE_KEY, compact.toString()).apply()
    }

    override fun onDestroy() {
        scanning = false
        runCatching { imageReader?.setOnImageAvailableListener(null, null) }
        runCatching { virtualDisplay?.release() }; runCatching { imageReader?.close() }; runCatching { projection?.stop() }
        runCatching { recognizers.forEach { it.close() } }
        runCatching { lensView?.let { windowManager.removeView(it) } }
        runCatching { controlsPanel?.let { windowManager.removeView(it) } }
        worker.shutdownNow()
        super.onDestroy()
    }

    private inner class FoxLensView(context: Context) : View(context) {
        private val d = resources.displayMetrics.density
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f; val cy = height / 2f; val r = minOf(width, height) * 0.46f
            fill.color = 0xFF0E0B15.toInt(); fill.style = Paint.Style.FILL
            canvas.drawCircle(cx, cy, r, fill)
            fill.color = if (scanning) 0xFFFFD05A.toInt() else 0xFFFFB800.toInt(); fill.style = Paint.Style.STROKE; fill.strokeWidth = 2.5f * d
            canvas.drawCircle(cx, cy, r - 1.5f*d, fill)
            fill.style = Paint.Style.FILL; fill.color = 0xFFFFC43D.toInt()
            val face = android.graphics.Path().apply {
                moveTo(cx-r*0.52f, cy-r*0.05f); lineTo(cx-r*0.44f, cy-r*0.72f); lineTo(cx-r*0.08f, cy-r*0.48f)
                lineTo(cx+r*0.08f, cy-r*0.48f); lineTo(cx+r*0.44f, cy-r*0.72f); lineTo(cx+r*0.52f, cy-r*0.05f)
                quadTo(cx, cy+r*0.72f, cx-r*0.52f, cy-r*0.05f); close()
            }
            canvas.drawPath(face, fill)
            fill.color = 0xFF17121E.toInt()
            canvas.drawOval(cx-r*0.34f, cy-r*0.08f, cx-r*0.04f, cy+r*0.12f, fill)
            canvas.drawOval(cx+r*0.04f, cy-r*0.08f, cx+r*0.34f, cy+r*0.12f, fill)
            fill.color = 0xFFFFF0C2.toInt()
            canvas.drawCircle(cx-r*0.19f, cy+0.01f*r, 1.7f*d, fill); canvas.drawCircle(cx+r*0.19f, cy+0.01f*r, 1.7f*d, fill)
            fill.color = if (scanning) 0xFF53D68A.toInt() else 0xFF8B8297.toInt()
            canvas.drawCircle(cx+r*0.60f, cy+r*0.55f, 4.5f*d, fill)
        }
    }
}
