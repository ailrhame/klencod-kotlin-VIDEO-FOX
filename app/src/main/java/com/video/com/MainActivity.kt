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
import androidx.core.content.ContextCompat

import com.google.mlkit.common.model.DownloadConditions
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
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.widget.ScrollView
import android.widget.CheckBox
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
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.nl.translate.TranslateLanguage
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
 * Video subtitle OCR + contextual on-device translation + optional LibreTranslate fallback.
 * V1 OCR recognizer uses ML Kit Latin script model. Additional script recognizers can be added later.
 */
class MainActivity : ComponentActivity() {
    private val gold = Color.rgb(255, 184, 0)
    private val bg = Color.rgb(11, 9, 20)
    private val panel = Color.rgb(23, 19, 36)
    private val muted = Color.rgb(160, 158, 174)

    private lateinit var root: LinearLayout
    private lateinit var playerContainer: FrameLayout
    private lateinit var playerView: PlayerView
    private lateinit var regionView: RegionSelectionView
    private lateinit var originalSubtitle: TextView
    private lateinit var arabicSubtitle: TextView
    private lateinit var statusText: TextView
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
    private val languageIdentifier by lazy { LanguageIdentification.getClient() }
    private var translator: Translator? = null
    private var retriever: MediaMetadataRetriever? = null
    private var selectedUri: Uri? = null
    private var region = RectF(0.08f, 0.72f, 0.92f, 0.96f) // normalized to video content
    private var regionChosen = false
    private var selectingRegion = false
    private var videoAspect = 16f / 9f
    private var candidateText = ""
    private var candidateHits = 0
    private var activeText = ""
    private var lastAcceptedAt = 0L
    private var contextLines = ArrayDeque<String>()
    private var lastTranslation = ""
    private var scanEnabled = false
    private var remoteEndpoint = ""
    private var remoteApiKey = ""
    private var aiEndpoint = ""
    private var aiApiKey = ""
    private var aiModel = ""
    private var preferRemote = false
    private var targetLanguage = "ar"
    private data class SubtitleCue(val startMs: Long, var endMs: Long, val source: String, var translated: String)
    private val subtitleCues = mutableListOf<SubtitleCue>()
    private var currentVideoName = "فيديو"
    private var currentVideoKey = ""
    private var lastSavedVideoUri = ""
    private var glossary = linkedMapOf<String, String>()
    private var subtitleFontSize = 17f
    private var subtitleBackground = true
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
        applySubtitleStyle()
        mainHandler.post(scanRunnable)
    }

    private fun buildUi() {
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

        val intro = text("شاهد الفيديو بترجمته الأصلية والعربية", 13f, muted)
        root.addView(intro)
        val choose = button("اختيار فيديو من الهاتف") { pickVideo.launch(arrayOf("video/*")) }
        root.addView(choose, matchWrap())
        root.addView(button("🦊 تشغيل العدسة العائمة داخل التطبيقات") { startFloatingLens() }, matchWrap())
        val tools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tools.addView(button("تصدير SRT") { if (subtitleCues.isEmpty()) toast("لا توجد ترجمة لتصديرها") else createSrt.launch("${safeFileName(currentVideoName)}.srt") }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(4) })
        tools.addView(button("استوديو الترجمة") { showSubtitleStudio() }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginStart = dp(4) })
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
        val subtitles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setBackgroundColor(0x99000000.toInt())
        }
        originalSubtitle = text("النص الأصلي سيظهر هنا", 15f, Color.WHITE).apply {
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        arabicSubtitle = text("الترجمة العربية ستظهر هنا", 17f, gold).apply {
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        subtitles.addView(originalSubtitle, matchWrap())
        subtitles.addView(arabicSubtitle, matchWrap())
        val subParams = FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM)
        subParams.setMargins(dp(10), 0, dp(10), dp(18))
        playerContainer.addView(subtitles, subParams)
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
        status("تم فتح الفيديو — حدد مكان الترجمة")
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
            if (activeText.isNotEmpty()) {
                activeText = ""
                mainHandler.post { originalSubtitle.text = ""; arabicSubtitle.text = "" }
            }
            return
        }
        if (clean.equals(candidateText, ignoreCase = true)) candidateHits++ else {
            candidateText = clean
            candidateHits = 1
        }
        // Require repeated recognition to reduce flicker and partial-frame OCR noise.
        if (candidateHits < 2 || clean.equals(activeText, ignoreCase = true)) return
        activeText = clean
        lastAcceptedAt = positionMs
        if (subtitleCues.isNotEmpty()) { subtitleCues.last().endMs = positionMs.coerceAtLeast(subtitleCues.last().startMs + 250); persistSubtitleCues() }
        contextLines.addLast(clean)
        while (contextLines.size > 6) contextLines.removeFirst()
        subtitleCues.add(SubtitleCue(positionMs, positionMs + 2500, clean, ""))
        persistSubtitleCues()
        mainHandler.post {
            originalSubtitle.text = clean
            arabicSubtitle.text = "جارٍ الترجمة…"
            status("تم اكتشاف نص جديد عند ${formatTime(positionMs)}")
        }
        translateSubtitle(clean, contextLines.toList())
    }

    private fun translateSubtitle(text: String, context: List<String>) {
        if (text.length > 500) return
        // Prefer a context-aware chat model when the user configures a compatible endpoint.
        if (aiEndpoint.isNotBlank() && aiModel.isNotBlank()) {
            contextualAiTranslate(context.distinct().takeLast(5), text) { result ->
                if (result != null) showTranslation(text, result) else translateWithFallback(text, context)
            }
            return
        }
        translateWithFallback(text, context)
    }

    private fun translateWithFallback(text: String, context: List<String>) {
        // Group nearby dialogue lines to preserve local context even in the offline translator.
        if (preferRemote && remoteEndpoint.isNotBlank()) {
            remoteTranslate(context.distinct().takeLast(4).joinToString("\n")) { result ->
                if (result != null) showTranslation(text, result.lineSequence().lastOrNull().orEmpty()) else localTranslate(text, context)
            }
        } else {
            localTranslate(text, context)
        }
    }

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
                val prompt = "ترجم الحوار التالي إلى العربية ترجمة أدبية طبيعية حسب المعنى والسياق، لا حرفياً. حافظ على نبرة الشخصية والمزاح والسخرية والتهديد والتعابير الاصطلاحية. لا تشرح ولا تضف كلاماً. أعد ترجمة السطر الأخير فقط. أسلوب الصياغة: $style.\nالسياق السابق والحالي:\n$dialogue\nالسطر المطلوب: $current"
                val messages = org.json.JSONArray()
                    .put(JSONObject().put("role", "system").put("content", "أنت مترجم حوارات محترف إلى العربية."))
                    .put(JSONObject().put("role", "user").put("content", prompt))
                val body = JSONObject().put("model", aiModel).put("messages", messages).put("temperature", 0.25)
                OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { it.write(body.toString()) }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val response = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
                val result = if (code in 200..299) JSONObject(response).optJSONArray("choices")?.optJSONObject(0)
                    ?.optJSONObject("message")?.optString("content")?.trim()?.takeIf { it.isNotBlank() } else null
                mainHandler.post { callback(result) }
            } catch (_: Exception) { mainHandler.post { callback(null) } }
            finally { connection?.disconnect() }
        }
    }

    private fun localTranslate(text: String, context: List<String>) {
        languageIdentifier.identifyLanguage(text)
            .addOnSuccessListener { language ->
                if (language == "und" || language == "ar") {
                    if (language == "ar") showTranslation(text, text) else fallbackRemoteOrMessage(text)
                    return@addOnSuccessListener
                }
                val source = TranslateLanguage.fromLanguageTag(language)
                val target = TranslateLanguage.fromLanguageTag(targetLanguage)
                if (source == null || target == null || source == target) {
                    fallbackRemoteOrMessage(text)
                    return@addOnSuccessListener
                }
                val current = TranslatorOptions.Builder()
                    .setSourceLanguage(source)
                    .setTargetLanguage(target)
                    .build()
                val engine = Translation.getClient(current)
                translator?.close()
                translator = engine
                mainHandler.post { status("تجهيز نموذج الترجمة المحلي: $language → $targetLanguage") }
                engine.downloadModelIfNeeded(DownloadConditions.Builder().build())
                    .addOnSuccessListener {
                        val batch = context.distinct().takeLast(4).joinToString("\n").ifBlank { text }
                engine.translate(batch)
                            .addOnSuccessListener { translated -> showTranslation(text, translated.lineSequence().lastOrNull().orEmpty().ifBlank { translated }) }
                            .addOnFailureListener { fallbackRemoteOrMessage(text) }
                    }
                    .addOnFailureListener { fallbackRemoteOrMessage(text) }
            }
            .addOnFailureListener { fallbackRemoteOrMessage(text) }
    }

    private fun fallbackRemoteOrMessage(text: String) {
        if (remoteEndpoint.isNotBlank()) remoteTranslate(text) { result ->
            if (result != null) showTranslation(text, result) else showTranslation(text, "تعذرت الترجمة — تحقق من النموذج أو الخدمة")
        } else showTranslation(text, "النموذج المحلي غير متاح لهذه اللغة")
    }

    private fun remoteTranslate(text: String, callback: (String?) -> Unit) {
        translationExecutor.execute {
            var connection: HttpURLConnection? = null
            try {
                var endpoint = remoteEndpoint.trim().trimEnd('/')
                if (!endpoint.endsWith("/translate")) endpoint += "/translate"
                connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 9000
                    readTimeout = 12000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
                val body = JSONObject().apply {
                    put("q", text)
                    put("source", "auto")
                    put("target", targetLanguage)
                    put("format", "text")
                    if (remoteApiKey.isNotBlank()) put("api_key", remoteApiKey)
                }
                OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { it.write(body.toString()) }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val response = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
                val translated = if (code in 200..299) JSONObject(response).optString("translatedText").takeIf { it.isNotBlank() } else null
                mainHandler.post { callback(translated) }
            } catch (_: Exception) {
                mainHandler.post { callback(null) }
            } finally { connection?.disconnect() }
        }
    }

    private fun showTranslation(sourceText: String, value: String) {
        val finalValue = applyGlossary(value)
        lastTranslation = finalValue
        subtitleCues.lastOrNull()?.takeIf { it.source == sourceText }?.translated = finalValue
        persistSubtitleCues()
        mainHandler.post {
            // A slow response for an earlier subtitle must not overwrite the current one.
            if (activeText == sourceText) arabicSubtitle.text = finalValue
        }
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
                listOf("سريع · 600ms", "متوازن · 900ms", "موفر للطاقة · 1400ms"))
            setSelection(when (scanIntervalMs) { in 0..700 -> 0; in 701..1100 -> 1; else -> 2 })
        }
        val style = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("طبيعية وسياقية", "حوار أنمي", "رسمية", "مختصرة"))
            setSelection(listOf("طبيعية وسياقية", "حوار أنمي", "رسمية", "مختصرة").indexOf(translationStyle).coerceAtLeast(0))
        }
        val size = SeekBar(this).apply { max = 14; progress = (subtitleFontSize - 13f).toInt().coerceIn(0, 14) }
        val backgroundCheck = CheckBox(this).apply { text = "خلفية داكنة للنص"; setTextColor(Color.WHITE); isChecked = subtitleBackground }
        box.addView(text("جودة الالتقاط واستهلاك البطارية", 14f, gold)); box.addView(interval)
        box.addView(text("أسلوب الصياغة العربية", 14f, gold)); box.addView(style)
        box.addView(text("حجم الترجمة", 14f, gold)); box.addView(size); box.addView(backgroundCheck)
        AlertDialog.Builder(this).setTitle("الجودة") .setView(box)
            .setPositiveButton("حفظ") { _, _ ->
                scanIntervalMs = when (interval.selectedItemPosition) { 0 -> 600L; 2 -> 1400L; else -> 900L }
                translationStyle = style.selectedItem.toString()
                subtitleFontSize = 13f + size.progress
                subtitleBackground = backgroundCheck.isChecked
                applySubtitleStyle()
                getSharedPreferences("video_fox", MODE_PRIVATE).edit().putLong("scan_interval", scanIntervalMs)
                    .putString("translation_style", translationStyle).putFloat("subtitle_size", subtitleFontSize)
                    .putBoolean("subtitle_bg", subtitleBackground).apply()
                mainHandler.removeCallbacks(scanRunnable); mainHandler.post(scanRunnable)
                status("تم حفظ إعدادات الجودة")
            }.setNegativeButton("إلغاء", null).show()
    }

    private fun applySubtitleStyle() {
        if (!::arabicSubtitle.isInitialized) return
        arabicSubtitle.textSize = subtitleFontSize
        originalSubtitle.textSize = (subtitleFontSize - 2f).coerceAtLeast(11f)
        (arabicSubtitle.parent as? View)?.setBackgroundColor(if (subtitleBackground) 0x99000000.toInt() else Color.TRANSPARENT)
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
        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14)) }
        if (subtitleCues.isEmpty()) list.addView(text("ابدأ تشغيل الفيديو مع تفعيل الترجمة لتظهر المقاطع هنا.", 14f, muted))
        subtitleCues.forEachIndexed { index, cue ->
            val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10)); setBackgroundColor(panel) }
            card.addView(text("${index + 1}. ${formatTime(cue.startMs)}", 11f, gold))
            card.addView(text(cue.source, 13f, Color.WHITE))
            card.addView(text(cue.translated.ifBlank { "بانتظار الترجمة" }, 15f, gold))
            card.addView(button("تعديل الترجمة") { editCue(index) })
            val lp = LinearLayout.LayoutParams(-1, -2); lp.bottomMargin = dp(8); list.addView(card, lp)
        }
        scroll.addView(list)
        AlertDialog.Builder(this).setTitle("استوديو الترجمة · ${subtitleCues.size} مقطع")
            .setView(scroll).setPositiveButton("تصدير SRT") { _, _ -> if (subtitleCues.isNotEmpty()) createSrt.launch("${safeFileName(currentVideoName)}.srt") }
            .setNegativeButton("إغلاق", null).show()
    }

    private fun editCue(index: Int) {
        val cue = subtitleCues.getOrNull(index) ?: return
        val input = EditText(this).apply { setText(cue.translated); minLines = 2; gravity = Gravity.TOP }
        AlertDialog.Builder(this).setTitle("تحرير الترجمة رقم ${index + 1}").setView(input)
            .setPositiveButton("حفظ") { _, _ -> cue.translated = input.text.toString().trim(); persistSubtitleCues(); if (index == subtitleCues.lastIndex) arabicSubtitle.text = cue.translated }
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
                        writer.write("${index + 1}\n${srtTime(start)} --> ${srtTime(end)}\n${cue.translated.ifBlank { cue.source }}\n\n")
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
            hint = "https://your-libretranslate-host"
            setText(remoteEndpoint)
            inputType = InputType.TYPE_TEXT_VARIATION_URI
        }
        val keyInput = EditText(this).apply {
            hint = "API Key (اختياري حسب الخدمة)"
            setText(remoteApiKey)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val aiEndpointInput = EditText(this).apply { hint = "OpenAI-compatible endpoint (اختياري)"; setText(aiEndpoint); inputType = InputType.TYPE_TEXT_VARIATION_URI }
        val aiKeyInput = EditText(this).apply { hint = "مفتاح AI (اختياري للخادم المحلي)"; setText(aiApiKey); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        val aiModelInput = EditText(this).apply { hint = "اسم النموذج"; setText(aiModel) }
        val remoteCheck = android.widget.CheckBox(this).apply {
            text = "تفضيل الخدمة الخارجية قبل المحلي"
            setTextColor(Color.WHITE)
            isChecked = preferRemote
        }
        box.addView(text("عنوان LibreTranslate", 13f, muted))
        box.addView(endpointInput)
        box.addView(text("مفتاح الخدمة", 13f, muted))
        box.addView(keyInput)
        box.addView(remoteCheck)
        box.addView(text("محرك المعنى والسياق · متوافق مع Chat Completions", 13f, gold))
        box.addView(aiEndpointInput); box.addView(aiModelInput); box.addView(aiKeyInput)
        AlertDialog.Builder(this)
            .setTitle("إعداد محرك الترجمة")
            .setView(box)
            .setPositiveButton("حفظ") { _, _ ->
                remoteEndpoint = endpointInput.text.toString().trim()
                remoteApiKey = keyInput.text.toString().trim()
                preferRemote = remoteCheck.isChecked
                aiEndpoint = aiEndpointInput.text.toString().trim()
                aiModel = aiModelInput.text.toString().trim()
                aiApiKey = aiKeyInput.text.toString().trim()
                getSharedPreferences("video_fox", MODE_PRIVATE).edit()
                    .putString("endpoint", remoteEndpoint).putString("api_key", remoteApiKey)
                    .putBoolean("prefer_remote", preferRemote).putString("ai_endpoint", aiEndpoint)
                    .putString("ai_model", aiModel).putString("ai_key", aiApiKey).apply()
                status("تم حفظ إعدادات الترجمة")
            }
            .setNegativeButton("إلغاء", null).show()
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences("video_fox", MODE_PRIVATE)
        remoteEndpoint = prefs.getString("endpoint", "") ?: ""
        remoteApiKey = prefs.getString("api_key", "") ?: ""
        preferRemote = prefs.getBoolean("prefer_remote", false)
        aiEndpoint = prefs.getString("ai_endpoint", "") ?: ""
        aiModel = prefs.getString("ai_model", "") ?: ""
        aiApiKey = prefs.getString("ai_key", "") ?: ""
        scanIntervalMs = prefs.getLong("scan_interval", 900L)
        translationStyle = prefs.getString("translation_style", "طبيعية وسياقية") ?: "طبيعية وسياقية"
        subtitleFontSize = prefs.getFloat("subtitle_size", 17f)
        subtitleBackground = prefs.getBoolean("subtitle_bg", true)
        val savedGlossary = runCatching { JSONObject(prefs.getString("glossary", "{}")) }.getOrNull()
        savedGlossary?.keys()?.forEach { key -> glossary[key] = savedGlossary.optString(key) }
    }

    private fun resetSubtitleState() {
        candidateText = ""
        candidateHits = 0
        activeText = ""
        contextLines.clear()
        mainHandler.post { originalSubtitle.text = "النص الأصلي"; arabicSubtitle.text = "الترجمة العربية" }
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
        try { languageIdentifier.close() } catch (_: Exception) { }
        try { translator?.close() } catch (_: Exception) { }
        if (::player.isInitialized) player.release()
        frameExecutor.shutdownNow()
        translationExecutor.shutdownNow()
        super.onDestroy()
    }
}


/**
 * Cross-app floating lens. Screen capture starts only after Android's MediaProjection
 * consent dialog. OCR is performed on-device and translation uses ML Kit models.
 * @N5CR4
 */
class VideoFoxLensService : Service() {
    companion object {
        const val ACTION_START = "com.video.fox.action.START_LENS"
        const val EXTRA_RESULT_CODE = "video_fox_result_code"
        const val EXTRA_RESULT_DATA = "video_fox_result_data"
        private const val CHANNEL_ID = "video_fox_capture"
        private const val NOTIFICATION_ID = 5105
    }

    private lateinit var windowManager: WindowManager
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var lensView: TextView? = null
    private var subtitlePanel: LinearLayout? = null
    private var sourceLabel: TextView? = null
    private var translatedLabel: TextView? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    private var scanning = false
    private var lastFrameAt = 0L
    private var lastText = ""
    private var stableHits = 0
    private var activeLanguage = ""
    private var translator: Translator? = null
    private val languageIdentifier by lazy { LanguageIdentification.getClient() }
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
        if (intent?.action != ACTION_START) {
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val consentData: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        if (consentData == null || resultCode != android.app.Activity.RESULT_OK) {
            stopSelf()
            return START_NOT_STICKY
        }
        startScreenCapture(resultCode, consentData)
        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "VIDEO FOX screen translation",
                NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("VIDEO FOX · العدسة العائمة")
            .setContentText("اضغط العدسة لبدء أو إيقاف ترجمة الشاشة")
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setOngoing(true)
            .build()
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
            projection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    stopSelf()
                }
            }, mainHandler)

            virtualDisplay = projection?.createVirtualDisplay(
                "VIDEO_FOX_TRANSLATION_LENS",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null,
                mainHandler
            )

            imageReader?.setOnImageAvailableListener({ reader ->
                if (!scanning || System.currentTimeMillis() - lastFrameAt < 1000L) {
                    reader.acquireLatestImage()?.close()
                } else {
                    lastFrameAt = System.currentTimeMillis()
                    processScreenFrame(reader)
                }
            }, mainHandler)

            showLens()
        } catch (_: Exception) {
            stopSelf()
        }
    }

    private fun showLens() {
        val density = resources.displayMetrics.density
        val overlayType = if (Build.VERSION.SDK_INT >= 26) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val lens = TextView(this).apply {
            text = "🦊"
            textSize = 23f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundColor(0xEEFFB800.toInt())
            elevation = 12f * density
            setOnTouchListener(object : View.OnTouchListener {
                private var downX = 0f
                private var downY = 0f
                private var initialX = 0
                private var initialY = 0
                private var dragged = false

                override fun onTouch(view: View, event: MotionEvent): Boolean {
                    val params = view.layoutParams as WindowManager.LayoutParams
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            downX = event.rawX
                            downY = event.rawY
                            initialX = params.x
                            initialY = params.y
                            dragged = false
                            return true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val dx = (event.rawX - downX).toInt()
                            val dy = (event.rawY - downY).toInt()
                            if (kotlin.math.abs(dx) + kotlin.math.abs(dy) > 10) dragged = true
                            params.x = initialX + dx
                            params.y = initialY + dy
                            runCatching { windowManager.updateViewLayout(view, params) }
                            return true
                        }
                        MotionEvent.ACTION_UP -> {
                            if (!dragged) toggleScanning()
                            return true
                        }
                    }
                    return true
                }
            })
        }
        lensView = lens

        val lensParams = WindowManager.LayoutParams(
            (58 * density).toInt(),
            (58 * density).toInt(),
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = (18 * density).toInt()
            y = (180 * density).toInt()
        }

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding((12 * density).toInt(), (8 * density).toInt(), (12 * density).toInt(), (8 * density).toInt())
            setBackgroundColor(0xE8080710.toInt())
            elevation = 10f * density
            visibility = View.GONE
        }
        sourceLabel = TextView(this).apply {
            text = ""
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
        }
        translatedLabel = TextView(this).apply {
            text = "اضغط العدسة لبدء الترجمة"
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFC928.toInt())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        panel.addView(sourceLabel, LinearLayout.LayoutParams(-1, -2))
        panel.addView(translatedLabel, LinearLayout.LayoutParams(-1, -2))
        subtitlePanel = panel

        val panelParams = WindowManager.LayoutParams(
            -1,
            -2,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
            y = (72 * density).toInt()
        }

        try {
            windowManager.addView(panel, panelParams)
            windowManager.addView(lens, lensParams)
        } catch (_: Exception) {
            stopSelf()
        }
    }

    private fun toggleScanning() {
        scanning = !scanning
        lensView?.text = if (scanning) "Ⅱ" else "🦊"
        subtitlePanel?.visibility = if (scanning) View.VISIBLE else View.GONE
        updateSubtitle("", if (scanning) "جارٍ قراءة النص من الشاشة…" else "الترجمة متوقفة")
    }

    private fun processScreenFrame(reader: ImageReader) {
        if (!busy.compareAndSet(false, true)) {
            reader.acquireLatestImage()?.close()
            return
        }
        val image = reader.acquireLatestImage()
        if (image == null) {
            busy.set(false)
            return
        }

        worker.execute {
            var frameBitmap: Bitmap? = null
            var croppedBitmap: Bitmap? = null
            var scaledBitmap: Bitmap? = null
            var mlKitOwnsBitmaps = false
            try {
                val plane = image.planes[0]
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * image.width
                val paddedWidth = image.width + rowPadding / pixelStride

                frameBitmap = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
                frameBitmap.copyPixelsFromBuffer(buffer)
                croppedBitmap = Bitmap.createBitmap(frameBitmap, 0, 0, image.width, image.height)

                val maxWidth = 1000
                scaledBitmap = if (croppedBitmap.width > maxWidth) {
                    Bitmap.createScaledBitmap(
                        croppedBitmap,
                        maxWidth,
                        (croppedBitmap.height * maxWidth.toFloat() / croppedBitmap.width).toInt().coerceAtLeast(1),
                        true
                    )
                } else {
                    croppedBitmap
                }

                val input = InputImage.fromBitmap(scaledBitmap, 0)
                val ocrTasks = recognizers.map { recognizer ->
                    recognizer.process(input).continueWith { task ->
                        if (task.isSuccessful) task.result?.text.orEmpty() else ""
                    }
                }
                mlKitOwnsBitmaps = true

                Tasks.whenAllSuccess<String>(ocrTasks)
                    .addOnSuccessListener { results ->
                        val bestText = results
                            .filter { it.isNotBlank() }
                            .distinct()
                            .maxByOrNull { it.length }
                            .orEmpty()
                        acceptRecognizedText(bestText)
                    }
                    .addOnCompleteListener {
                        runCatching { if (scaledBitmap !== croppedBitmap) scaledBitmap?.recycle() }
                        runCatching { croppedBitmap?.recycle() }
                        runCatching { frameBitmap?.recycle() }
                        busy.set(false)
                    }
            } catch (_: Exception) {
                busy.set(false)
            } finally {
                image.close()
                if (!mlKitOwnsBitmaps) {
                    runCatching { if (scaledBitmap !== croppedBitmap) scaledBitmap?.recycle() }
                    runCatching { croppedBitmap?.recycle() }
                    runCatching { frameBitmap?.recycle() }
                }
            }
        }
    }

    private fun acceptRecognizedText(text: String) {
        val clean = text.replace(Regex("\\s+"), " ").trim()
        if (clean.length < 2) return

        if (clean.equals(lastText, ignoreCase = true)) {
            stableHits++
        } else {
            lastText = clean
            stableHits = 1
        }
        if (stableHits < 2) return

        updateSubtitle(clean, "جارٍ الترجمة…")
        languageIdentifier.identifyLanguage(clean)
            .addOnSuccessListener { languageTag ->
                if (languageTag == "ar") {
                    updateSubtitle(clean, clean)
                    return@addOnSuccessListener
                }

                val sourceLanguage = TranslateLanguage.fromLanguageTag(languageTag)
                val targetLanguage = TranslateLanguage.ARABIC
                if (sourceLanguage == null || sourceLanguage == targetLanguage) {
                    updateSubtitle(clean, "لغة النص غير مدعومة محلياً")
                    return@addOnSuccessListener
                }

                if (translator == null || activeLanguage != languageTag) {
                    translator?.close()
                    activeLanguage = languageTag
                    translator = Translation.getClient(
                        TranslatorOptions.Builder()
                            .setSourceLanguage(sourceLanguage)
                            .setTargetLanguage(targetLanguage)
                            .build()
                    )
                }

                translator?.downloadModelIfNeeded(DownloadConditions.Builder().build())
                    ?.addOnSuccessListener {
                        translator?.translate(clean)
                            ?.addOnSuccessListener { translated -> updateSubtitle(clean, translated) }
                            ?.addOnFailureListener { updateSubtitle(clean, "تعذرت ترجمة هذا النص") }
                    }
                    ?.addOnFailureListener { updateSubtitle(clean, "تعذر تجهيز نموذج اللغة؛ تحقق من الإنترنت") }
            }
            .addOnFailureListener { updateSubtitle(clean, "تعذر تحديد لغة النص") }
    }

    private fun updateSubtitle(source: String, translated: String) {
        mainHandler.post {
            sourceLabel?.text = source
            translatedLabel?.text = translated
        }
    }

    override fun onDestroy() {
        scanning = false
        runCatching { imageReader?.setOnImageAvailableListener(null, null) }
        runCatching { virtualDisplay?.release() }
        runCatching { imageReader?.close() }
        runCatching { projection?.stop() }
        runCatching { translator?.close() }
        runCatching { languageIdentifier.close() }
        runCatching { recognizers.forEach { it.close() } }
        runCatching { lensView?.let { windowManager.removeView(it) } }
        runCatching { subtitlePanel?.let { windowManager.removeView(it) } }
        worker.shutdownNow()
        super.onDestroy()
    }
}

