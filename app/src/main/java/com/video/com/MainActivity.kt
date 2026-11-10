package com.video.fox

import androidx.activity.ComponentActivity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.ScrollView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.setPadding
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.DownloadConditions
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONObject
import org.json.JSONArray
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.io.File
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import android.media.MediaMetadataRetriever

private data class VideoFoxCue(
    var startMs: Long,
    var endMs: Long,
    var source: String,
    var translated: String = "",
    var language: String = "und"
)

/**
 * VIDEO FOX V2

 * @N5CR4
 * Video subtitle OCR, editable subtitle timeline, local knowledge, quality checks,
 * SRT/VTT export and safe project backup. @N5CR4

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
    private val latinRecognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val japaneseRecognizer by lazy { TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build()) }
    private val koreanRecognizer by lazy { TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build()) }
    private val chineseRecognizer by lazy { TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()) }
    private val languageIdentifier by lazy { LanguageIdentification.getClient() }
    private var translator: Translator? = null
    private var retriever: MediaMetadataRetriever? = null
    private var selectedUri: Uri? = null
    private var region = RectF(0.08f, 0.72f, 0.92f, 0.96f) // normalized to video content
    private var regionChosen = false
    private var selectingRegion = false
    private var appendSelectedRegion = false
    private val regions = mutableListOf<RectF>()
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
    private var preferRemote = false
    private var targetLanguage = "ar"
    private var sourceOcrLanguage = "auto"
    private val cues = mutableListOf<VideoFoxCue>()
    private val cueUndo = ArrayDeque<String>()
    private val cueRedo = ArrayDeque<String>()
    private var pendingDocumentText = ""
    private var pendingArchiveBytes: ByteArray? = null
    private var customTypeface: Typeface? = null
    private var hideOriginalSubtitle = false
    private var autoFitSubtitle = true
    private var lastCueStartMs = -1L
    private val knowledgePrefs by lazy { getSharedPreferences("video_fox_knowledge", MODE_PRIVATE) }

    private val scanRunnable = object : Runnable {
        override fun run() {
            if (::player.isInitialized && player.isPlaying && selectedUri != null) {
                if (scanEnabled) captureAndRecognize(player.currentPosition)
                updateOverlayForPlayback(player.currentPosition)
            }
            mainHandler.postDelayed(this, 650L)
        }
    }

    private val pickVideo = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) openVideo(uri)
    }

    private val exportSrt = registerForActivityResult(ActivityResultContracts.CreateDocument("application/x-subrip")) { uri ->
        if (uri != null) runCatching {
            contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { it.write(pendingDocumentText) }
            toast("تم تصدير ملف SRT")
        }.onFailure { toast("تعذر حفظ SRT: ${it.localizedMessage}") }
    }
    private val exportVtt = registerForActivityResult(ActivityResultContracts.CreateDocument("text/vtt")) { uri ->
        if (uri != null) runCatching {
            contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { it.write(pendingDocumentText) }
            toast("تم تصدير ملف VTT")
        }.onFailure { toast("تعذر حفظ VTT: ${it.localizedMessage}") }
    }
    private val exportProject = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val bytes = pendingArchiveBytes
        if (uri != null && bytes != null) runCatching {
            contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            toast("تم حفظ نسخة VIDEO FOX")
        }.onFailure { toast("تعذر حفظ النسخة: ${it.localizedMessage}") }
        pendingArchiveBytes = null
    }
    private val importProject = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching {
            contentResolver.openInputStream(uri)?.use { restoreProject(it) }
            refreshTimelineStatus()
            toast("تم استيراد مشروع الفيديو")
        }.onFailure { toast("فشل استيراد المشروع: ${it.localizedMessage}") }
    }
    private val importFont = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching {
            contentResolver.openInputStream(uri)?.use { input ->
                val target = File(filesDir, "video_fox_custom_font.ttf")
                target.outputStream().use { input.copyTo(it) }
                customTypeface = Typeface.createFromFile(target)
                applySubtitleStyle()
                getSharedPreferences("video_fox", MODE_PRIVATE).edit().putBoolean("custom_font", true).apply()
                toast("تم استيراد الخط وتطبيقه")
            }
        }.onFailure { toast("تعذر فتح ملف الخط") }
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
        val settings = button("⚙") { showRemoteSettings() }
        header.addView(settings, LinearLayout.LayoutParams(dp(48), dp(44)))
        root.addView(header)

        val intro = text("شاهد الفيديو بترجمته الأصلية والعربية", 13f, muted)
        root.addView(intro)
        val choose = button("اختيار فيديو من الهاتف") { pickVideo.launch(arrayOf("video/*")) }
        root.addView(choose, matchWrap())

        playerContainer = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        playerView = PlayerView(this).apply {
            useController = true
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
        }
        playerContainer.addView(playerView, FrameLayout.LayoutParams(-1, -1))
        regionView = RegionSelectionView(this) { normalized ->
            region = RectF(normalized)
            if (!appendSelectedRegion) regions.clear()
            regions.add(RectF(normalized))
            regionChosen = true
            selectingRegion = false
            appendSelectedRegion = false
            saveRegions()
            regionView.setSelectionMode(false)
            status("تم حفظ منطقة OCR · ${regions.size} منطقة")
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
        actions.addView(button("تحديد منطقة النص") { beginRegionSelection(false) }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(4) })
        actions.addView(button("إضافة منطقة OCR") { beginRegionSelection(true) }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(4); marginEnd = dp(4) })
        actions.addView(button("بدء / إيقاف الترجمة") {
            if (selectedUri == null) toast("اختر فيديو أولاً") else {
                scanEnabled = !scanEnabled
                status(if (scanEnabled) "الترجمة مفعلة" else "الترجمة متوقفة")
                if (scanEnabled && player.isPlaying) captureAndRecognize(player.currentPosition)
            }
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(6) })
        root.addView(actions)

        val toolsRow1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        toolsRow1.addView(button("الترجمة الزمنية") { showTimeline() }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(4) })
        toolsRow1.addView(button("تصدير SRT") { exportSubtitles(false) }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginStart = dp(4); marginEnd = dp(4) })
        toolsRow1.addView(button("تصدير VTT") { exportSubtitles(true) }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginStart = dp(4) })
        root.addView(toolsRow1)

        val toolsRow2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        toolsRow2.addView(button("المعرفة والقاموس") { showKnowledge() }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(4) })
        toolsRow2.addView(button("فحص الجودة") { showQualityReport() }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginStart = dp(4); marginEnd = dp(4) })
        toolsRow2.addView(button("تراجع / إعادة") { showHistoryActions() }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginStart = dp(4) })
        root.addView(toolsRow2)

        val toolsRow3 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        toolsRow3.addView(button("حفظ مشروع ZIP") { backupProject() }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(4) })
        toolsRow3.addView(button("استيراد مشروع") { importProject.launch(arrayOf("application/zip", "application/octet-stream")) }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginStart = dp(4); marginEnd = dp(4) })
        toolsRow3.addView(button("الخط والمظهر") { showSubtitleAppearance() }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginStart = dp(4) })
        root.addView(toolsRow3)

        statusText = text("جاهز — اختر فيديو للبدء", 12f, muted).apply { setPadding(2, dp(8), 2, 0) }
        root.addView(statusText)
        setContentView(root)
    }

    private fun openVideo(uri: Uri) {
        val sessionPrefs = getSharedPreferences("video_fox", MODE_PRIVATE)
        val resumePosition = if (sessionPrefs.getString("last_video_uri", "") == uri.toString()) sessionPrefs.getLong("last_position_ms", 0L) else 0L
        selectedUri = uri
        sessionPrefs.edit().putString("last_video_uri", uri.toString()).apply()
        loadCuesForVideo(uri)
        try { contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) { }
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
        if (resumePosition > 0L) player.seekTo(resumePosition)
        player.playWhenReady = true
        regionChosen = false
        region = RectF(0.08f, 0.72f, 0.92f, 0.96f)
        loadRegions()
        resetSubtitleState()
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

    private fun beginRegionSelection(append: Boolean) {
        if (selectedUri == null) { toast("اختر فيديو أولاً"); return }
        appendSelectedRegion = append
        selectingRegion = true
        regionView.setSelectionMode(true)
        status(if (append) "ارسم منطقة OCR إضافية" else "اسحب إصبعك لرسم منطقة الترجمة الرئيسية")
    }

    private fun startScanning() {
        if (scanEnabled) status("الترجمة تعمل")
    }

    private fun captureAndRecognize(positionMs: Long) {
        if (!frameBusy.compareAndSet(false, true)) return
        frameExecutor.execute {
            var frameBitmap: Bitmap? = null
            val crops = mutableListOf<Bitmap>()
            try {
                val sourceRetriever = retriever ?: throw IllegalStateException("مصدر الفيديو غير جاهز")
                frameBitmap = sourceRetriever.getFrameAtTime(positionMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST)
                    ?: throw IllegalStateException("تعذر استخراج إطار الفيديو")
                val source = frameBitmap!!
                val activeRegions = if (regions.isEmpty()) listOf(region) else regions.toList()
                activeRegions.forEach { rect ->
                    val left = (rect.left * source.width).toInt().coerceIn(0, source.width - 1)
                    val top = (rect.top * source.height).toInt().coerceIn(0, source.height - 1)
                    val right = (rect.right * source.width).toInt().coerceIn(left + 1, source.width)
                    val bottom = (rect.bottom * source.height).toInt().coerceIn(top + 1, source.height)
                    crops.add(Bitmap.createBitmap(source, left, top, right - left, bottom - top))
                }
                val combined = StringBuilder()
                fun processNext(index: Int) {
                    if (index >= crops.size) {
                        processRecognizedText(combined.toString(), positionMs)
                        crops.forEach { runCatching { it.recycle() } }
                        runCatching { source.recycle() }
                        frameBusy.set(false)
                        return
                    }
                    selectedRecognizer().process(InputImage.fromBitmap(crops[index], 0))
                        .addOnSuccessListener { result ->
                            val text = result.textBlocks.joinToString(" ") { it.text }.trim()
                            if (text.isNotBlank() && !combined.contains(text)) {
                                if (combined.isNotEmpty()) combined.append(' ')
                                combined.append(text)
                            }
                        }
                        .addOnFailureListener { e ->
                            mainHandler.post { status("OCR: ${e.localizedMessage ?: "تعذر التعرف"}") }
                        }
                        .addOnCompleteListener {
                            runCatching { crops[index].recycle() }
                            processNext(index + 1)
                        }
                }
                processNext(0)
            } catch (e: Exception) {
                crops.forEach { runCatching { it.recycle() } }
                runCatching { frameBitmap?.recycle() }
                mainHandler.post { status("خطأ تحليل الإطار: ${e.localizedMessage ?: "غير معروف"}") }
                frameBusy.set(false)
            }
        }
    }

    private fun processRecognizedText(raw: String, positionMs: Long) {
        val clean = raw.replace('\n', ' ').replace(Regex("\\s+"), " ").trim()
        if (clean.length < 2) {
            if (activeText.isNotEmpty()) {
                cues.lastOrNull()?.takeIf { it.source.equals(activeText, true) }?.let { it.endMs = maxOf(it.startMs + 300L, positionMs) }
                persistCues()
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
        if (candidateHits < 2) return
        if (clean.equals(activeText, ignoreCase = true)) {
            cues.lastOrNull()?.takeIf { it.source.equals(clean, true) }?.let {
                it.endMs = maxOf(it.endMs, positionMs + 850L)
                persistCues()
            }
            return
        }
        activeText = clean
        lastAcceptedAt = positionMs
        if (cues.isNotEmpty() && cues.last().endMs >= positionMs - 1200L && cues.last().source.equals(clean, true)) {
            cues.last().endMs = maxOf(cues.last().endMs, positionMs + 700L)
        } else {
            if (cues.isNotEmpty() && cues.last().endMs > positionMs) cues.last().endMs = positionMs
            pushCueUndo()
            cues.add(VideoFoxCue(positionMs, positionMs + 1200L, clean))
            lastCueStartMs = positionMs
        }
        persistCues()
        contextLines.addLast(clean)
        while (contextLines.size > 4) contextLines.removeFirst()
        mainHandler.post {
            originalSubtitle.text = clean
            arabicSubtitle.text = "جارٍ الترجمة…"
            applySubtitleStyle()
            status("تم اكتشاف نص جديد عند ${formatTime(positionMs)}")
        }
        translateSubtitle(clean, contextLines.toList())
    }

    private fun translateSubtitle(text: String, context: List<String>) {
        if (text.length > 500) return
        exactGlossary(text)?.let { learned ->
            updateCueTranslation(text, learned)
            showTranslation(text, learned)
            return
        }
        if (preferRemote && remoteEndpoint.isNotBlank()) {
            remoteTranslate(text) { result ->
                if (result != null) showTranslation(text, result) else localTranslate(text, context)
            }
        } else {
            localTranslate(text, context)
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
                        engine.translate(text)
                            .addOnSuccessListener { translated -> showTranslation(text, translated) }
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
        lastTranslation = value
        updateCueTranslation(sourceText, value)
        mainHandler.post {
            // A slow response for an earlier subtitle must not overwrite the current one.
            if (activeText == sourceText) arabicSubtitle.text = value
        }
    }


    private fun saveRegions() {
        val array = JSONArray()
        regions.forEach { r ->
            array.put(JSONArray().put(r.left.toDouble()).put(r.top.toDouble()).put(r.right.toDouble()).put(r.bottom.toDouble()))
        }
        getSharedPreferences("video_fox", MODE_PRIVATE).edit()
            .putString("ocr_regions", array.toString())
            .putString("ocr_regions_video_uri", selectedUri?.toString().orEmpty()).apply()
    }

    private fun loadRegions() {
        val prefs = getSharedPreferences("video_fox", MODE_PRIVATE)
        val savedFor = prefs.getString("ocr_regions_video_uri", "")
        val raw = if (savedFor == selectedUri?.toString()) prefs.getString("ocr_regions", "[]") else "[]"
        val array = runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
        regions.clear()
        for (i in 0 until array.length()) {
            val item = array.optJSONArray(i) ?: continue
            if (item.length() < 4) continue
            val left = item.optDouble(0, 0.08).toFloat().coerceIn(0f, 1f)
            val top = item.optDouble(1, 0.72).toFloat().coerceIn(0f, 1f)
            val right = item.optDouble(2, 0.92).toFloat().coerceIn(left + 0.01f, 1f)
            val bottom = item.optDouble(3, 0.96).toFloat().coerceIn(top + 0.01f, 1f)
            regions.add(RectF(left, top, right, bottom))
        }
        if (regions.isEmpty()) regions.add(RectF(region))
        region = RectF(regions.last())
    }

    private fun updateOverlayForPlayback(positionMs: Long) {
        val cue = cues.lastOrNull { positionMs >= it.startMs && positionMs <= it.endMs }
        if (cue != null) {
            if (originalSubtitle.text.toString() != cue.source) originalSubtitle.text = cue.source
            val translated = cue.translated.ifBlank { "جارٍ تجهيز الترجمة…" }
            if (arabicSubtitle.text.toString() != translated) arabicSubtitle.text = translated
        } else if (activeText.isBlank() || cues.lastOrNull()?.let { positionMs > it.endMs } == true) {
            if (originalSubtitle.text.isNotEmpty()) originalSubtitle.text = ""
            if (arabicSubtitle.text.isNotEmpty()) arabicSubtitle.text = ""
        }
    }

    private fun selectedRecognizer(): TextRecognizer = when (sourceOcrLanguage) {
        "ja" -> japaneseRecognizer
        "ko" -> koreanRecognizer
        "zh" -> chineseRecognizer
        else -> latinRecognizer
    }

    private fun updateCueTranslation(source: String, translated: String) {
        val cue = cues.lastOrNull { it.source.equals(source, true) && it.translated.isBlank() }
            ?: cues.lastOrNull { it.source.equals(source, true) }
        if (cue != null) {
            cue.translated = translated
            persistCues()
        }
    }

    private fun cuesJson(): JSONArray = JSONArray().also { array ->
        cues.forEach { cue ->
            array.put(JSONObject().put("start", cue.startMs).put("end", cue.endMs)
                .put("source", cue.source).put("translated", cue.translated).put("language", cue.language))
        }
    }

    private fun persistCues() {
        runCatching {
            getSharedPreferences("video_fox", MODE_PRIVATE).edit()
                .putString("cues_json", cuesJson().toString())
                .putString("cues_video_uri", selectedUri?.toString().orEmpty()).apply()
        }
    }

    private fun loadCuesForVideo(uri: Uri) {
        val prefs = getSharedPreferences("video_fox", MODE_PRIVATE)
        cues.clear()
        if (prefs.getString("cues_video_uri", "") == uri.toString()) {
            runCatching {
                val array = JSONArray(prefs.getString("cues_json", "[]"))
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val source = item.optString("source").trim()
                    if (source.isNotBlank()) cues.add(VideoFoxCue(
                        item.optLong("start"), item.optLong("end").coerceAtLeast(item.optLong("start") + 1),
                        source, item.optString("translated"), item.optString("language", "und")
                    ))
                }
            }
        }
        refreshTimelineStatus()
    }

    private fun refreshTimelineStatus() {
        status(if (cues.isEmpty()) "جاهز — لا توجد مقاطع محفوظة بعد" else "المقاطع الزمنية المحفوظة: ${cues.size}")
    }

    private fun pushCueUndo() {
        cueUndo.addLast(cuesJson().toString())
        while (cueUndo.size > 20) cueUndo.removeFirst()
        cueRedo.clear()
    }

    private fun restoreCueSnapshot(json: String) {
        cues.clear()
        val array = JSONArray(json)
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            cues.add(VideoFoxCue(o.optLong("start"), o.optLong("end"), o.optString("source"), o.optString("translated"), o.optString("language", "und")))
        }
        persistCues()
        refreshTimelineStatus()
    }

    private fun showHistoryActions() {
        AlertDialog.Builder(this).setTitle("سجل التعديلات")
            .setItems(arrayOf("تراجع عن آخر تعديل", "إعادة آخر تعديل")) { _, which ->
                if (which == 0 && cueUndo.isNotEmpty()) {
                    cueRedo.addLast(cuesJson().toString())
                    restoreCueSnapshot(cueUndo.removeLast())
                } else if (which == 1 && cueRedo.isNotEmpty()) {
                    cueUndo.addLast(cuesJson().toString())
                    restoreCueSnapshot(cueRedo.removeLast())
                } else toast("لا توجد خطوة متاحة")
            }.setNegativeButton("إغلاق", null).show()
    }

    private fun showTimeline() {
        if (cues.isEmpty()) { toast("لا توجد مقاطع بعد — شغّل الترجمة أولاً"); return }
        val labels = cues.mapIndexed { index, cue ->
            "${index + 1}. ${formatTime(cue.startMs)} → ${formatTime(cue.endMs)}\n${cue.source.take(70)}\n↳ ${cue.translated.ifBlank { "بانتظار الترجمة" }.take(70)}"
        }.toTypedArray()
        AlertDialog.Builder(this).setTitle("الترجمة الزمنية · ${cues.size} مقطع")
            .setItems(labels) { _, which -> editCue(which) }
            .setPositiveButton("إغلاق", null)
            .setNeutralButton("انتقال لأول مقطع") { _, _ ->
                if (::player.isInitialized) player.seekTo(cues.firstOrNull()?.startMs ?: 0L)
            }
            .show()
    }

    private fun editCue(index: Int) {
        val cue = cues.getOrNull(index) ?: return
        val sourceInput = EditText(this).apply { setText(cue.source); hint = "النص الأصلي"; minLines = 2 }
        val translatedInput = EditText(this).apply { setText(cue.translated); hint = "الترجمة العربية"; minLines = 2 }
        val startInput = EditText(this).apply { setText(cue.startMs.toString()); hint = "بداية المقطع بالميلي ثانية"; inputType = InputType.TYPE_CLASS_NUMBER }
        val endInput = EditText(this).apply { setText(cue.endMs.toString()); hint = "نهاية المقطع بالميلي ثانية"; inputType = InputType.TYPE_CLASS_NUMBER }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16)) }
        listOf(sourceInput, translatedInput, startInput, endInput).forEach { box.addView(it, matchWrap()) }
        AlertDialog.Builder(this).setTitle("تحرير المقطع ${index + 1}").setView(box)
            .setPositiveButton("حفظ") { _, _ ->
                val start = startInput.text.toString().toLongOrNull() ?: cue.startMs
                val end = endInput.text.toString().toLongOrNull() ?: cue.endMs
                if (end <= start || sourceInput.text.isBlank()) { toast("تحقق من النص والتوقيت"); return@setPositiveButton }
                pushCueUndo()
                cue.startMs = start; cue.endMs = end
                cue.source = sourceInput.text.toString().trim()
                cue.translated = translatedInput.text.toString().trim()
                persistCues(); refreshTimelineStatus()
            }
            .setNeutralButton("حفظ الترجمة في القاموس") { _, _ ->
                val translated = translatedInput.text.toString().trim()
                if (translated.isNotBlank()) {
                    saveGlossary(cue.source, translated, "تصحيح من الخط الزمني")
                    pushCueUndo(); cue.translated = translated; persistCues()
                    toast("تم حفظ التصحيح في القاموس المحلي")
                }
            }
            .setNegativeButton("حذف") { _, _ ->
                pushCueUndo(); cues.removeAt(index); persistCues(); refreshTimelineStatus()
            }.show()
    }

    private fun exportSubtitles(webVtt: Boolean) {
        if (cues.isEmpty()) { toast("لا توجد ترجمة لتصديرها"); return }
        val sorted = cues.sortedBy { it.startMs }
        val content = buildString {
            if (webVtt) append("WEBVTT\n\n")
            sorted.forEachIndexed { index, cue ->
                val start = cue.startMs.coerceAtLeast(0)
                val end = cue.endMs.coerceAtLeast(start + 1)
                if (!webVtt) append("${index + 1}\n")
                append("${subtitleTimestamp(start, webVtt)} --> ${subtitleTimestamp(end, webVtt)}\n")
                if (cue.translated.isNotBlank()) append(cue.translated) else append(cue.source)
                append("\n\n")
            }
        }
        pendingDocumentText = content
        if (webVtt) exportVtt.launch("VIDEO_FOX_subtitles.vtt") else exportSrt.launch("VIDEO_FOX_subtitles.srt")
    }

    private fun subtitleTimestamp(ms: Long, vtt: Boolean): String {
        val h = ms / 3600000
        val m = (ms / 60000) % 60
        val sec = (ms / 1000) % 60
        val milli = ms % 1000
        return String.format(Locale.US, "%02d:%02d:%02d%s%03d", h, m, sec, if (vtt) "." else ",", milli)
    }

    private fun showQualityReport() {
        if (cues.isEmpty()) { toast("لا توجد مقاطع لفحصها"); return }
        val sorted = cues.sortedBy { it.startMs }
        val issues = mutableListOf<String>()
        sorted.forEachIndexed { index, cue ->
            if (cue.source.isBlank()) issues.add("المقطع ${index + 1}: النص الأصلي فارغ")
            if (cue.translated.isBlank()) issues.add("المقطع ${index + 1}: الترجمة غير موجودة")
            val foreignLetters = cue.translated.count { it.isLetter() && it.code in 65..122 }
            if (foreignLetters >= 4) issues.add("المقطع ${index + 1}: توجد أحرف لاتينية متبقية؛ قد تكون اسماً أو نصاً يحتاج مراجعة")
            if (cue.endMs <= cue.startMs) issues.add("المقطع ${index + 1}: توقيت غير صالح")
            if (index > 0 && cue.startMs < sorted[index - 1].endMs) issues.add("تداخل زمني بين المقطعين $index و${index + 1}")
            if (index > 0 && cue.source.equals(sorted[index - 1].source, true)) issues.add("تكرار محتمل للنص في المقطعين $index و${index + 1}")
        }
        val report = if (issues.isEmpty()) "اجتاز الخط الزمني الفحص البنيوي.\nعدد المقاطع: ${cues.size}\n\nهذا الفحص لا يثبت دقة الترجمة اللغوية أو OCR." else
            "المقاطع: ${cues.size}\nالملاحظات: ${issues.size}\n\n" + issues.take(80).joinToString("\n") + "\n\nالفحص بنيوي؛ راجع النصوص بصرياً."
        AlertDialog.Builder(this).setTitle("VIDEO FOX · Quality Guard").setMessage(report)
            .setPositiveButton("حسناً", null).show()
    }

    private fun showKnowledge() {
        val options = arrayOf("عرض القاموس وإضافة مصطلح", "بطاقات المتحدثين", "معلومات النسخ الاحتياطي")
        AlertDialog.Builder(this).setTitle("مركز المعرفة المحلي")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> manageGlossary()
                    1 -> manageCharacters()
                    2 -> toast("تُضمّن القواميس وبطاقات المتحدثين تلقائياً داخل نسخة ZIP")
                }
            }.setNegativeButton("إغلاق", null).show()
    }

    private fun manageGlossary() {
        val array = JSONArray(knowledgePrefs.getString("glossary", "[]"))
        val labels = (0 until array.length()).map { i ->
            val o = array.optJSONObject(i) ?: JSONObject()
            "${o.optString("source")}  →  ${o.optString("arabic")}"
        }.toMutableList()
        labels.add("＋ إضافة مصطلح جديد")
        AlertDialog.Builder(this).setTitle("قاموس المصطلحات · ${array.length()}")
            .setItems(labels.toTypedArray()) { _, which ->
                if (which == labels.lastIndex) {
                    val source = EditText(this).apply { hint = "المصطلح الأصلي" }
                    val target = EditText(this).apply { hint = "المقابل العربي" }
                    val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16)); addView(source); addView(target) }
                    AlertDialog.Builder(this).setTitle("إضافة مصطلح").setView(box)
                        .setPositiveButton("حفظ") { _, _ ->
                            if (source.text.isNotBlank() && target.text.isNotBlank()) {
                                saveGlossary(source.text.toString(), target.text.toString(), "")
                                toast("تم حفظ المصطلح")
                            }
                        }.setNegativeButton("إلغاء", null).show()
                } else {
                    val o = array.optJSONObject(which) ?: return@setItems
                    AlertDialog.Builder(this).setTitle(o.optString("source"))
                        .setMessage(o.optString("arabic") + "\n\n" + o.optString("note"))
                        .setPositiveButton("حذف") { _, _ -> deleteGlossary(o.optString("source")); toast("تم حذف المصطلح") }
                        .setNegativeButton("إغلاق", null).show()
                }
            }.setNegativeButton("إغلاق", null).show()
    }

    private fun manageCharacters() {
        val array = JSONArray(knowledgePrefs.getString("characters", "[]"))
        val input = EditText(this).apply { hint = "اسم المتحدث | أسلوبه | ملاحظات (افصل بينها بعلامة |)" }
        AlertDialog.Builder(this).setTitle("بطاقات المتحدثين")
            .setMessage((0 until array.length()).joinToString("\n") { i ->
                val o = array.optJSONObject(i) ?: JSONObject()
                "• ${o.optString("name")} — ${o.optString("voice")}"
            }.ifBlank { "لا توجد بطاقات محفوظة." })
            .setView(input)
            .setPositiveButton("إضافة") { _, _ ->
                val parts = input.text.toString().split("|", limit = 3).map { it.trim() }
                if (parts.isNotEmpty() && parts[0].isNotBlank()) {
                    val updated = JSONArray()
                    for (i in 0 until array.length()) updated.put(array.getJSONObject(i))
                    updated.put(JSONObject().put("name", parts[0]).put("voice", parts.getOrElse(1) { "" }).put("notes", parts.getOrElse(2) { "" }))
                    knowledgePrefs.edit().putString("characters", updated.toString()).apply()
                    toast("تم حفظ بطاقة المتحدث")
                }
            }.setNegativeButton("إغلاق", null).show()
    }

    private fun saveGlossary(source: String, arabic: String, note: String) {
        val old = JSONArray(knowledgePrefs.getString("glossary", "[]"))
        val updated = JSONArray()
        for (i in 0 until old.length()) {
            val o = old.optJSONObject(i) ?: continue
            if (!o.optString("source").equals(source, true)) updated.put(o)
        }
        updated.put(JSONObject().put("source", source.trim()).put("arabic", arabic.trim()).put("note", note))
        knowledgePrefs.edit().putString("glossary", updated.toString()).apply()
    }

    private fun deleteGlossary(source: String) {
        val old = JSONArray(knowledgePrefs.getString("glossary", "[]"))
        val updated = JSONArray()
        for (i in 0 until old.length()) {
            val o = old.optJSONObject(i) ?: continue
            if (!o.optString("source").equals(source, true)) updated.put(o)
        }
        knowledgePrefs.edit().putString("glossary", updated.toString()).apply()
    }

    private fun exactGlossary(source: String): String? {
        val array = JSONArray(knowledgePrefs.getString("glossary", "[]"))
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            if (o.optString("source").trim().equals(source.trim(), true)) return o.optString("arabic").takeIf { it.isNotBlank() }
        }
        return null
    }

    private fun showSubtitleAppearance() {
        val options = arrayOf("استيراد خط TTF / OTF", "إظهار / إخفاء النص الأصلي", "تفعيل / إيقاف الملاءمة التلقائية")
        AlertDialog.Builder(this).setTitle("مظهر الترجمة")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> importFont.launch(arrayOf("font/ttf", "font/otf", "application/octet-stream"))
                    1 -> {
                        hideOriginalSubtitle = !hideOriginalSubtitle
                        originalSubtitle.visibility = if (hideOriginalSubtitle) View.GONE else View.VISIBLE
                        toast(if (hideOriginalSubtitle) "تم إخفاء النص الأصلي" else "تم إظهار النص الأصلي")
                    }
                    2 -> {
                        autoFitSubtitle = !autoFitSubtitle
                        getSharedPreferences("video_fox", MODE_PRIVATE).edit().putBoolean("auto_fit", autoFitSubtitle).apply()
                        applySubtitleStyle()
                        toast(if (autoFitSubtitle) "الملاءمة التلقائية مفعلة" else "الملاءمة التلقائية متوقفة")
                    }
                }
            }.setNegativeButton("إغلاق", null).show()
    }

    private fun applySubtitleStyle() {
        customTypeface?.let {
            originalSubtitle.typeface = it
            arabicSubtitle.typeface = it
        }
        if (autoFitSubtitle) {
            originalSubtitle.maxLines = 2
            arabicSubtitle.maxLines = 3
            originalSubtitle.setAutoSizeTextTypeUniformWithConfiguration(11, 16, 1, TypedValue.COMPLEX_UNIT_SP)
            arabicSubtitle.setAutoSizeTextTypeUniformWithConfiguration(12, 21, 1, TypedValue.COMPLEX_UNIT_SP)
        } else {
            originalSubtitle.setAutoSizeTextTypeWithDefaults(TextView.AUTO_SIZE_TEXT_TYPE_NONE)
            arabicSubtitle.setAutoSizeTextTypeWithDefaults(TextView.AUTO_SIZE_TEXT_TYPE_NONE)
            originalSubtitle.textSize = 15f
            arabicSubtitle.textSize = 18f
        }
    }

    private fun backupProject() {
        runCatching {
            val bytes = ByteArrayOutputStream()
            ZipOutputStream(bytes).use { zip ->
                fun put(name: String, value: ByteArray) {
                    zip.putNextEntry(ZipEntry(name)); zip.write(value); zip.closeEntry()
                }
                val manifest = JSONObject().put("format", "VIDEO_FOX_PROJECT").put("version", 2)
                    .put("videoUri", selectedUri?.toString().orEmpty())
                    .put("positionMs", if (::player.isInitialized) player.currentPosition else 0L)
                put("project.json", manifest.toString().toByteArray(Charsets.UTF_8))
                put("timeline.json", cuesJson().toString().toByteArray(Charsets.UTF_8))
                put("knowledge.json", JSONObject()
                    .put("glossary", JSONArray(knowledgePrefs.getString("glossary", "[]")))
                    .put("characters", JSONArray(knowledgePrefs.getString("characters", "[]"))).toString().toByteArray(Charsets.UTF_8))
            }
            pendingArchiveBytes = bytes.toByteArray()
            exportProject.launch("VIDEO_FOX_Project.zip")
        }.onFailure { toast("فشل تجهيز المشروع: ${it.localizedMessage}") }
    }

    private fun restoreProject(input: java.io.InputStream) {
        val temp = File(cacheDir, "videofox_import_${System.currentTimeMillis()}").apply { mkdirs() }
        var total = 0L
        val allowed = setOf("project.json", "timeline.json", "knowledge.json")
        try {
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name.replace('\\', '/')
                    require(!entry.isDirectory && name in allowed) { "عنصر ZIP غير مسموح: $name" }
                    val target = File(temp, name)
                    var count = 0L
                    target.outputStream().use { out ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val n = zip.read(buffer)
                            if (n < 0) break
                            count += n; total += n
                            require(count <= 5L * 1024 * 1024 && total <= 12L * 1024 * 1024) { "حجم النسخة أكبر من الحد" }
                            out.write(buffer, 0, n)
                        }
                    }
                }
            }
            val project = JSONObject(File(temp, "project.json").readText(Charsets.UTF_8))
            require(project.optString("format") == "VIDEO_FOX_PROJECT") { "صيغة المشروع غير معروفة" }
            val timeline = File(temp, "timeline.json")
            if (timeline.isFile) restoreCueSnapshot(timeline.readText(Charsets.UTF_8))
            val knowledge = File(temp, "knowledge.json")
            if (knowledge.isFile) {
                val obj = JSONObject(knowledge.readText(Charsets.UTF_8))
                knowledgePrefs.edit().putString("glossary", obj.optJSONArray("glossary")?.toString() ?: "[]")
                    .putString("characters", obj.optJSONArray("characters")?.toString() ?: "[]").apply()
            }
            val video = project.optString("videoUri")
            if (video.isNotBlank()) toast("تم استيراد بيانات المشروع؛ اختر الفيديو الأصلي لإعادة ربطه")
        } finally { temp.deleteRecursively() }
    }

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
        val languageButton = Button(this).apply {
            text = "لغة OCR: " + when (sourceOcrLanguage) { "ja" -> "اليابانية"; "ko" -> "الكورية"; "zh" -> "الصينية"; else -> "تلقائي / لاتينية" }
            isAllCaps = false
            setOnClickListener {
                val codes = arrayOf("auto", "ja", "ko", "zh")
                val names = arrayOf("تلقائي / لاتينية", "اليابانية", "الكورية", "الصينية")
                AlertDialog.Builder(this@MainActivity).setTitle("لغة النص داخل الفيديو")
                    .setItems(names) { _, index ->
                        sourceOcrLanguage = codes[index]
                        getSharedPreferences("video_fox", MODE_PRIVATE).edit().putString("ocr_language", sourceOcrLanguage).apply()
                        text = "لغة OCR: " + names[index]
                    }.show()
            }
        }
        box.addView(languageButton)
        AlertDialog.Builder(this)
            .setTitle("إعداد محرك الترجمة")
            .setView(box)
            .setPositiveButton("حفظ") { _, _ ->
                remoteEndpoint = endpointInput.text.toString().trim()
                remoteApiKey = keyInput.text.toString().trim()
                preferRemote = remoteCheck.isChecked
                getSharedPreferences("video_fox", MODE_PRIVATE).edit()
                    .putString("endpoint", remoteEndpoint)
                    .putString("api_key", remoteApiKey)
                    .putBoolean("prefer_remote", preferRemote).apply()
                status("تم حفظ إعدادات الترجمة")
            }
            .setNegativeButton("إلغاء", null).show()
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences("video_fox", MODE_PRIVATE)
        remoteEndpoint = prefs.getString("endpoint", "") ?: ""
        remoteApiKey = prefs.getString("api_key", "") ?: ""
        preferRemote = prefs.getBoolean("prefer_remote", false)
        sourceOcrLanguage = prefs.getString("ocr_language", "auto") ?: "auto"
        loadRegions()
        autoFitSubtitle = prefs.getBoolean("auto_fit", true)
        if (prefs.getBoolean("custom_font", false)) {
            val fontFile = File(filesDir, "video_fox_custom_font.ttf")
            if (fontFile.isFile) runCatching { customTypeface = Typeface.createFromFile(fontFile) }
        }
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
        if (::player.isInitialized) {
            player.pause()
            getSharedPreferences("video_fox", MODE_PRIVATE).edit()
                .putLong("last_position_ms", player.currentPosition)
                .putString("last_video_uri", selectedUri?.toString().orEmpty()).apply()
        }
        persistCues()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(scanRunnable)
        scanEnabled = false
        try { retriever?.release() } catch (_: Exception) { }
        try { latinRecognizer.close() } catch (_: Exception) { }
        try { japaneseRecognizer.close() } catch (_: Exception) { }
        try { koreanRecognizer.close() } catch (_: Exception) { }
        try { chineseRecognizer.close() } catch (_: Exception) { }
        try { languageIdentifier.close() } catch (_: Exception) { }
        try { translator?.close() } catch (_: Exception) { }
        if (::player.isInitialized) player.release()
        frameExecutor.shutdownNow()
        translationExecutor.shutdownNow()
        super.onDestroy()
    }
}