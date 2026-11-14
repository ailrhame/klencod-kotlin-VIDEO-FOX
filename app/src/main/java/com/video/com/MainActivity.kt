package com.video.fox

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.setPadding
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : ComponentActivity() {
    companion object { const val EXTRA_OPEN_STUDIO = "video_fox_open_translation_studio" }

    private val gold = Color.rgb(255, 184, 0)
    private val goldSoft = Color.rgb(255, 205, 90)
    private val bg = Color.rgb(10, 9, 17)
    private val panel = Color.rgb(22, 19, 33)
    private val panel2 = Color.rgb(30, 26, 44)
    private val muted = Color.rgb(155, 152, 170)

    private lateinit var root: LinearLayout
    private lateinit var playerContainer: FrameLayout
    private lateinit var playerView: PlayerView
    private lateinit var regionView: RegionSelectionView
    private lateinit var statusText: TextView
    private lateinit var progressBar: android.widget.ProgressBar
    private lateinit var progressLabel: TextView
    private lateinit var translateButton: Button
    private var studioVisible = false
    private lateinit var player: ExoPlayer

    private val mainHandler = Handler(Looper.getMainLooper())
    private val frameExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val translateExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val frameBusy = AtomicBoolean(false)
    private val translationEngine = TranslationEngine(this)
    private val recognizers: List<TextRecognizer> by lazy { listOf(
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS),
        TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build()),
        TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build()),
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    ) }
    private var retriever: android.media.MediaMetadataRetriever? = null
    private var selectedUri: Uri? = null
    private var region = RectF(0.08f, 0.72f, 0.92f, 0.96f)
    private var regionChosen = false
    private var selectingRegion = false
    private var videoAspect = 16f / 9f
    private var candidateText = ""
    private var candidateHits = 0
    private var activeText = ""
    private var rawCues: MutableList<RawCue> = mutableListOf()
    private var scanEnabled = false
    private var isTranslating = false
    private var autoTranslateOnEnd = true
    private var aiEndpoint = ""
    private var aiApiKey = ""
    private var aiModel = ""
    private var currentVideoName = "فيديو"
    private var currentVideoKey = ""
    private var lastSavedVideoUri = ""
    private var glossary = linkedMapOf<String, String>()
    private var characters = mutableListOf<String>()
    private var scanIntervalMs = 900L
    private var translationStyle = "طبيعية وسياقية"
    private var translationMode = "auto"

    private val createSrt = registerForActivityResult(ActivityResultContracts.CreateDocument("application/x-subrip")) { uri ->
        if (uri != null) exportSrt(uri)
    }
    private val createJson = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) exportJson(uri)
    }
    private val pickVideo = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) openVideo(uri)
    }
    private val screenCaptureRequest = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val svc = Intent(this, VideoFoxLensService::class.java).apply {
                action = VideoFoxLensService.ACTION_START
                putExtra(VideoFoxLensService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(VideoFoxLensService.EXTRA_RESULT_DATA, result.data)
            }
            ContextCompat.startForegroundService(this, svc)
            toast("VIDEO FOX lens started")
        } else toast("لم يُمنح إذن التقاط الشاشة")
    }

    private val scanRunnable = object : Runnable {
        override fun run() {
            if (scanEnabled && ::player.isInitialized && player.isPlaying && selectedUri != null) {
                captureAndRecognize(player.currentPosition)
            }
            mainHandler.postDelayed(this, scanIntervalMs)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg; window.navigationBarColor = bg
        buildHomeUi()
        player = ExoPlayer.Builder(this).build()
        playerView.player = player
        playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    scanEnabled = true
                    status("جارٍ التقاط النص… (الترجمة بعد انتهاء الفيديو)")
                } else {
                    scanEnabled = false
                    status("متوقف")
                }
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) onVideoEnded()
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
        if (intent?.getBooleanExtra(EXTRA_OPEN_STUDIO, false) == true) showStudio()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_STUDIO, false)) showStudio()
    }

    private fun buildHomeUi() {
        studioVisible = false
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg); setPadding(dp(14))
        }
        root.addView(buildHeader())
        root.addView(text("VIDEO FOX — التقط الحوار، ترجمه سياقياً، في مساحة مستقلة", 12f, muted))
        root.addView(button("اختيار فيديو", gold, Color.BLACK) { pickVideo.launch(arrayOf("video/*")) }, matchWrap().apply { topMargin = dp(8) })

        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row1.addView(button("🦊 العدسة العائمة", panel2, goldSoft) { startFloatingLens() }, weightParams(1f).apply { marginEnd = dp(6) })
        row1.addView(button("مركز الترجمة", panel2, goldSoft) { showStudio() }, weightParams(1f).apply { marginStart = dp(6) })
        root.addView(row1, matchWrap().apply { topMargin = dp(6) })

        playerContainer = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        playerView = PlayerView(this).apply {
            useController = true
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
        }
        playerContainer.addView(playerView, FrameLayout.LayoutParams(-1, -1))
        regionView = RegionSelectionView(this) { normalized ->
            region = normalized; regionChosen = true; selectingRegion = false
            regionView.setSelectionMode(false)
            status("تم حفظ منطقة النص")
        }
        playerContainer.addView(regionView, FrameLayout.LayoutParams(-1, -1))
        root.addView(playerContainer, LinearLayout.LayoutParams(-1, 0, 1f).apply {
            topMargin = dp(12); bottomMargin = dp(8)
        })

        progressBar = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progress = 0; visibility = View.GONE
            progressTintList = android.content.res.ColorStateList.valueOf(gold)
        }
        progressLabel = text("", 11f, muted).apply { visibility = View.GONE }
        root.addView(progressBar, matchWrap())
        root.addView(progressLabel)

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(button("تحديد منطقة النص", panel2, Color.WHITE) { beginRegionSelection() }, weightParams(1f).apply { marginEnd = dp(4) })
        actions.addView(button("بدء / إيقاف الالتقاط", panel2, Color.WHITE) { toggleCapture() }, weightParams(1f).apply { marginStart = dp(4) })
        root.addView(actions, matchWrap().apply { topMargin = dp(6) })

        translateButton = button("توليد الترجمة السياقية ▸", gold, Color.BLACK) { triggerTranslation() }
        root.addView(translateButton, matchWrap().apply { topMargin = dp(8) })

        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row2.addView(button("تصدير SRT", panel2, goldSoft) {
            if (rawCues.none { it.translated.isNotBlank() }) toast("لا توجد ترجمة") else createSrt.launch("${safeFileName(currentVideoName)}.srt")
        }, weightParams(1f).apply { marginEnd = dp(4) })
        row2.addView(button("تصدير JSON", panel2, goldSoft) {
            if (rawCues.isEmpty()) toast("لا توجد بيانات") else createJson.launch("${safeFileName(currentVideoName)}.json")
        }, weightParams(1f).apply { marginStart = dp(4) })
        root.addView(row2, matchWrap().apply { topMargin = dp(6) })

        statusText = text("جاهز — اختر فيديو للبدء", 12f, muted).apply { setPadding(2, dp(8), 2, dp(8)) }
        root.addView(statusText)

        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        nav.addView(button("⚙ الإعدادات", panel2, goldSoft) { showFoxDashboard() }, weightParams(1f))
        root.addView(nav, matchWrap().apply { topMargin = dp(4) })

        setContentView(root)
    }

    private fun buildHeader(): LinearLayout {
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this).apply {
            text = "VIDEO FOX"
            textSize = 22f; setTextColor(gold); typeface = android.graphics.Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        header.addView(TextView(this).apply {
            text = "V3"
            textSize = 11f; setTextColor(goldSoft); gravity = Gravity.CENTER
            background = GradientDrawable().apply { setColor(panel2); cornerRadius = dp(8).toFloat(); setStroke(dp(1), gold) }
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
        header.addView(iconButton("▤") { showLibrary() })
        return header
    }

    private fun iconButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 14f
        setTextColor(goldSoft); setBackgroundColor(Color.TRANSPARENT)
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
    }

    private fun startFloatingLens() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            toast("اسمح بالظهور فوق التطبيقات ثم أعد المحاولة")
            return
        }
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
        screenCaptureRequest.launch(mgr.createScreenCaptureIntent())
    }

    private fun openVideo(uri: Uri) {
        selectedUri = uri
        currentVideoName = queryDisplayName(uri) ?: "فيديو ${System.currentTimeMillis()}"
        lastSavedVideoUri = uri.toString()
        currentVideoKey = uri.toString().hashCode().toUInt().toString(16)
        saveVideoToLibrary()
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        player.setMediaItem(MediaItem.fromUri(uri)); player.prepare(); player.playWhenReady = true
        regionChosen = false; region = RectF(0.08f, 0.72f, 0.92f, 0.96f)
        resetState(); rawCues.clear(); loadCues()
        status("فُتح الفيديو — سيُلتقط النص ثم تترجم بعد الانتهاء")
        frameExecutor.execute {
            try {
                retriever?.release()
                retriever = android.media.MediaMetadataRetriever().apply { setDataSource(this@MainActivity, uri) }
            } catch (e: Exception) { mainHandler.post { status("تعذر تجهيز الفيديو: ${e.localizedMessage}") } }
        }
    }

    private fun beginRegionSelection() {
        if (selectedUri == null) { toast("اختر فيديو أولاً"); return }
        selectingRegion = true; regionView.setSelectionMode(true)
        status("ارسم مستطيلاً حول الترجمة")
    }

    private fun toggleCapture() {
        if (selectedUri == null) { toast("اختر فيديو أولاً"); return }
        if (player.isPlaying) player.pause() else player.play()
    }

    private fun onVideoEnded() {
        scanEnabled = false
        mainHandler.post { status("انتهى الفيديو — ${rawCues.size} مقطع جاهز") }
        if (rawCues.isEmpty()) return
        if (rawCues.all { it.translated.isNotBlank() }) { status("الترجمة مكتملة — افتح المركز"); return }
        if (autoTranslateOnEnd) triggerTranslation() else mainHandler.post { showTranslatePrompt() }
    }

    private fun showTranslatePrompt() {
        AlertDialog.Builder(this)
            .setTitle("توليد الترجمة السياقية")
            .setMessage("تم التقاط ${rawCues.size} مقطع. هل تريد ترجمتها الآن دفعة واحدة؟")
            .setPositiveButton("نعم، ترجم") { _, _ -> triggerTranslation() }
            .setNegativeButton("لاحقاً", null)
            .show()
    }

    private fun triggerTranslation() {
        if (isTranslating) { toast("الترجمة قيد التنفيذ"); return }
        val toTranslate = rawCues.filter { it.translated.isBlank() }
        if (toTranslate.isEmpty()) { toast("لا توجد مقاطع بانتظار الترجمة"); return }

        isTranslating = true
        translateButton.isEnabled = false
        progressBar.visibility = View.VISIBLE; progressLabel.visibility = View.VISIBLE
        progressBar.progress = 0; progressLabel.text = "0 / ${toTranslate.size}"

        val config = TranslationConfig(
            endpoint = aiEndpoint,
            model = aiModel,
            apiKey = aiApiKey,
            style = translationStyle,
            glossary = glossary,
            characters = characters,
            mode = translationMode
        )
        val workingList = toTranslate.toMutableList()
        translateExecutor.execute {
            try {
                translationEngine.translateBatch(workingList, config) { done, total ->
                    mainHandler.post {
                        progressBar.progress = (done * 100) / total.coerceAtLeast(1)
                        progressLabel.text = "$done / $total مقطع"
                    }
                }
                workingList.forEach { src ->
                    rawCues.firstOrNull { it.startMs == src.startMs && it.source == src.source }?.let { target ->
                        target.translated = src.translated
                        target.speaker = src.speaker
                        target.emotion = src.emotion
                    }
                }
                persistCues()
                mainHandler.post {
                    status("اكتملت الترجمة ✓")
                    progressBar.visibility = View.GONE; progressLabel.visibility = View.GONE
                    translateButton.isEnabled = true; isTranslating = false
                    showStudio()
                }
            } catch (e: TranslationEngine.TranslationException) {
                mainHandler.post {
                    status("فشل: ${e.message}")
                    progressBar.visibility = View.GONE; progressLabel.visibility = View.GONE
                    translateButton.isEnabled = true; isTranslating = false
                }
            } catch (e: Exception) {
                mainHandler.post {
                    status("خطأ غير متوقع: ${e.localizedMessage ?: "?"}")
                    progressBar.visibility = View.GONE; progressLabel.visibility = View.GONE
                    translateButton.isEnabled = true; isTranslating = false
                }
            }
        }
    }

    private fun captureAndRecognize(positionMs: Long) {
        if (!frameBusy.compareAndSet(false, true)) return
        frameExecutor.execute {
            var handedOff = false; var frameBitmap: Bitmap? = null; var cropBitmap: Bitmap? = null
            try {
                val sourceRetriever = retriever ?: run { frameBusy.set(false); return@execute }
                frameBitmap = sourceRetriever.getFrameAtTime(positionMs * 1000L, android.media.MediaMetadataRetriever.OPTION_CLOSEST) ?: run { frameBusy.set(false); return@execute }
                val source = frameBitmap!!
                val rect = RectF(region)
                val left = (rect.left * source.width).toInt().coerceIn(0, source.width - 1)
                val top = (rect.top * source.height).toInt().coerceIn(0, source.height - 1)
                val right = (rect.right * source.width).toInt().coerceIn(left + 1, source.width)
                val bottom = (rect.bottom * source.height).toInt().coerceIn(top + 1, source.height)
                cropBitmap = Bitmap.createBitmap(source, left, top, right - left, bottom - top)
                val fC = frameBitmap; val cC = cropBitmap!!
                val input = InputImage.fromBitmap(cC, 0)
                val tasks = recognizers.map { e -> e.process(input).continueWith { t -> if (t.isSuccessful) t.result?.text.orEmpty() else "" } }
                Tasks.whenAllSuccess<String>(tasks).addOnSuccessListener { results ->
                    val raw = results.filter { it.isNotBlank() }.distinct().joinToString(" ")
                    processRecognized(raw, positionMs)
                }.addOnCompleteListener {
                    runCatching { cC.recycle() }; runCatching { fC?.recycle() }
                    frameBusy.set(false)
                }
                handedOff = true
            } catch (e: Exception) {
                mainHandler.post { status("خطأ: ${e.localizedMessage}") }
            } finally {
                if (!handedOff) {
                    runCatching { cropBitmap?.recycle() }; runCatching { frameBitmap?.recycle() }
                    frameBusy.set(false)
                }
            }
        }
    }

    private fun processRecognized(raw: String, positionMs: Long) {
        val clean = raw.replace('\n', ' ').replace(Regex("\\s+"), " ").trim()
        if (clean.length < 2) { activeText = ""; return }
        if (clean.equals(candidateText, true)) candidateHits++ else { candidateText = clean; candidateHits = 1 }
        if (candidateHits < 2 || clean.equals(activeText, true)) return
        activeText = clean

        val prev = rawCues.lastOrNull()
        if (prev != null && positionMs - prev.endMs < 4000L) prev.endMs = positionMs.coerceAtLeast(prev.startMs + 500)
        rawCues.add(RawCue(positionMs, positionMs + 2500, clean))
        persistCues()
        mainHandler.post { status("التقطت ${rawCues.size} مقطعاً — الترجمة لاحقاً") }
    }

    private fun showStudio() {
        studioVisible = true
        if (::player.isInitialized) player.pause()
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(bg); setPadding(dp(14))
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(iconButton("‹") { returnToHome() })
        header.addView(text("مركز الترجمة", 21f, gold).apply { typeface = android.graphics.Typeface.DEFAULT_BOLD }, LinearLayout.LayoutParams(0, dp(48), 1f))
        header.addView(iconButton("⚙") { showRemoteSettings() })
        page.addView(header)

        page.addView(button("توليد الترجمة السياقية للكل", gold, Color.BLACK) { triggerTranslation() }, matchWrap().apply { topMargin = dp(6) })

        val search = EditText(this).apply {
            hint = "بحث في النص…"; setTextColor(Color.WHITE); setHintTextColor(muted)
            textSize = 13f; setSingleLine()
            background = GradientDrawable().apply { setColor(panel); cornerRadius = dp(12).toFloat(); setStroke(dp(1), 0x33443558) }
            setPadding(dp(12))
        }
        page.addView(search, matchWrap().apply { topMargin = dp(8) })

        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(10), 0, dp(20)) }
        scroll.addView(list)
        page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val lensEntries = loadLensEntries()

        fun rebuild() {
            list.removeAllViews()
            val query = search.text.toString().trim().lowercase(Locale.getDefault())

            list.addView(sectionHeader("مقاطع فيديو الهاتف · ${rawCues.size}"))
            val videoFiltered = rawCues.filter {
                query.isBlank() || it.source.lowercase(Locale.getDefault()).contains(query) || it.translated.lowercase(Locale.getDefault()).contains(query)
            }
            if (videoFiltered.isEmpty()) list.addView(text(if (rawCues.isEmpty()) "لم تُلتقط مقاطع بعد. شغّل الفيديو." else "لا نتائج", 12f, muted))
            else videoFiltered.forEach { cue ->
                val idx = rawCues.indexOf(cue)
                list.addView(buildCueCard(cue, idx, ::rebuild, jumpTo = { player.seekTo(cue.startMs); player.play() }))
            }

            list.addView(sectionHeader("عدسة عائمة · ${lensEntries.length()}"))
            if (lensEntries.length() == 0) list.addView(text("لم تُلتقط مقاطع من العدسة العائمة.", 12f, muted))
            else {
                var shownSession = ""
                for (i in 0 until lensEntries.length()) {
                    val obj = lensEntries.optJSONObject(i) ?: continue
                    val sid = obj.optString("sessionId")
                    if (sid != shownSession) {
                        shownSession = sid
                        val ts = obj.optLong("capturedAt")
                        val label = java.text.SimpleDateFormat("yyyy/MM/dd · HH:mm", Locale.getDefault()).format(java.util.Date(ts))
                        list.addView(text("جلسة · $label", 13f, goldSoft).apply { setPadding(0, dp(10), 0, dp(4)) })
                    }
                    val src = obj.optString("source")
                    val tr = obj.optString("translated")
                    if (query.isNotBlank() && !src.lowercase(Locale.getDefault()).contains(query) && !tr.lowercase(Locale.getDefault()).contains(query)) continue
                    val card = card()
                    card.addView(text("${i + 1} · ${obj.optString("status", "")}", 11f, muted))
                    card.addView(text(src, 13f, Color.WHITE))
                    card.addView(text(tr.ifBlank { "بانتظار الترجمة" }, 15f, if (tr.isBlank()) muted else gold))
                    list.addView(card, cardParams())
                }
            }
        }

        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { rebuild() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        rebuild()

        if (translationMode == "openai" && (aiEndpoint.isBlank() || aiModel.isBlank())) {
            val warn = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(12))
                background = GradientDrawable().apply { setColor(0xFF221B29.toInt()); cornerRadius = dp(14).toFloat(); setStroke(dp(1), gold) }
            }
            warn.addView(text("وضع API مفعّل لكن الإعدادات فارغة", 13f, gold))
            warn.addView(button("ضبط الآن", gold, Color.BLACK) { showRemoteSettings() }, matchWrap().apply { topMargin = dp(6) })
            page.addView(warn, matchWrap().apply { topMargin = dp(8) })
        } else if (translationMode == "auto" && aiEndpoint.isBlank()) {
            val info = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(12))
                background = GradientDrawable().apply { setColor(0xFF1A2A1A.toInt()); cornerRadius = dp(14).toFloat(); setStroke(dp(1), Color.rgb(83, 214, 138)) }
            }
            info.addView(text("وضع تلقائي: الترجمة المحلية ML Kit جاهزة (بدون إنترنت بعد تنزيل النموذج)", 12f, Color.rgb(83, 214, 138)))
            page.addView(info, matchWrap().apply { topMargin = dp(8) })
        }

        setContentView(page)
    }

    private fun buildCueCard(cue: RawCue, index: Int, onRefresh: () -> Unit, jumpTo: () -> Unit): LinearLayout {
        val c = card()
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(text("${index + 1} · ${formatTime(cue.startMs)}", 11f, gold), LinearLayout.LayoutParams(0, -2, 1f))
        if (cue.emotion.isNotBlank()) head.addView(tag(cue.emotion, goldSoft), LinearLayout.LayoutParams(-2, -2))
        c.addView(head)
        if (cue.speaker.isNotBlank()) c.addView(text("🎭 ${cue.speaker}", 11f, goldSoft))
        c.addView(text(cue.source, 13f, Color.WHITE))
        c.addView(text(cue.translated.ifBlank { "بانتظار الترجمة" }, 15f, if (cue.translated.isBlank()) muted else gold))
        val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btns.addView(button("انتقال", panel2, goldSoft) { jumpTo() }, weightParams(1f).apply { marginEnd = dp(4) })
        btns.addView(button("تحرير", panel2, goldSoft) { editCue(cue, onRefresh) }, weightParams(1f).apply { marginStart = dp(4) })
        c.addView(btns, matchWrap().apply { topMargin = dp(6) })
        return c.apply { layoutParams = cardParams() }
    }

    private fun editCue(cue: RawCue, onSaved: () -> Unit) {
        val input = EditText(this).apply { setText(cue.translated); minLines = 2; gravity = Gravity.TOP }
        AlertDialog.Builder(this).setTitle("تحرير الترجمة")
            .setView(input)
            .setPositiveButton("حفظ") { _, _ -> cue.translated = input.text.toString().trim(); persistCues(); onSaved() }
            .setNegativeButton("إلغاء", null).show()
    }

    private fun sectionHeader(label: String) = text(label, 15f, gold).apply { setPadding(0, dp(14), 0, dp(6)) }

    private fun tag(label: String, color: Int) = TextView(this).apply {
        text = " $label "
        textSize = 10f; setTextColor(color)
        background = GradientDrawable().apply { setColor(0x22332200); cornerRadius = dp(8).toFloat(); setStroke(dp(1), color) }
        setPadding(dp(6), dp(2), dp(6), dp(2))
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(12))
        background = GradientDrawable().apply { setColor(panel); cornerRadius = dp(14).toFloat(); setStroke(dp(1), 0x553F354F) }
    }
    private fun cardParams() = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }

    private fun returnToHome() { studioVisible = false; buildHomeUi(); playerView.player = player }

    private fun showFoxDashboard() {
        val items = arrayOf("إعدادات محرك الترجمة", "الجودة وسرعة الالتقاط", "المعجم الشخصي", "الشخصيات", "مكتبة الفيديو")
        AlertDialog.Builder(this).setTitle("مركز التحكم").setItems(items) { _, w -> when (w) {
            0 -> showRemoteSettings(); 1 -> showQualitySettings(); 2 -> showGlossaryEditor(); 3 -> showCharactersEditor(); 4 -> showLibrary()
        } }.show()
    }

    private fun showQualitySettings() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16)) }
        val interval = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("دقيق · 600ms", "متوازن · 900ms", "موفر · 1400ms"))
            setSelection(when (scanIntervalMs) { in 0..700 -> 0; in 701..1100 -> 1; else -> 2 })
        }
        val style = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("طبيعية وسياقية", "حوار أنمي", "رسمية", "مختصرة"))
            setSelection(listOf("طبيعية وسياقية", "حوار أنمي", "رسمية", "مختصرة").indexOf(translationStyle).coerceAtLeast(0))
        }
        val modeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("تلقائي · ML Kit أو API", "محلي فقط · ML Kit", "API فقط · OpenAI"))
            setSelection(when (translationMode) { "offline" -> 1; "openai" -> 2; else -> 0 })
        }
        val auto = android.widget.CheckBox(this).apply { text = "توليد الترجمة تلقائياً عند انتهاء الفيديو"; isChecked = autoTranslateOnEnd; setTextColor(Color.WHITE) }
        box.addView(text("فاصل الالتقاط", 13f, gold)); box.addView(interval)
        box.addView(text("أسلوب الصياغة", 13f, gold)); box.addView(style)
        box.addView(text("محرك الترجمة", 13f, gold)); box.addView(modeSpinner)
        box.addView(auto)
        AlertDialog.Builder(this).setTitle("الجودة").setView(box)
            .setPositiveButton("حفظ") { _, _ ->
                scanIntervalMs = when (interval.selectedItemPosition) { 0 -> 600L; 2 -> 1400L; else -> 900L }
                translationStyle = style.selectedItem.toString()
                translationMode = when (modeSpinner.selectedItemPosition) { 1 -> "offline"; 2 -> "openai"; else -> "auto" }
                autoTranslateOnEnd = auto.isChecked
                getSharedPreferences("video_fox", MODE_PRIVATE).edit()
                    .putLong("scan_interval", scanIntervalMs)
                    .putString("translation_style", translationStyle)
                    .putString("translation_mode", translationMode)
                    .putBoolean("auto_translate", autoTranslateOnEnd).apply()
                mainHandler.removeCallbacks(scanRunnable); mainHandler.post(scanRunnable)
                status("حُفظت الإعدادات · وضع الترجمة: $translationMode")
            }.setNegativeButton("إلغاء", null).show()
    }

    private fun showGlossaryEditor() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16)) }
        val src = EditText(this).apply { hint = "الأصل" }
        val tgt = EditText(this).apply { hint = "العربية" }
        box.addView(src); box.addView(tgt)
        val entries = glossary.entries.joinToString("\n") { "${it.key} → ${it.value}" }
        box.addView(text(if (entries.isBlank()) "لا مصطلحات" else entries, 12f, muted))
        AlertDialog.Builder(this).setTitle("المعجم").setView(box)
            .setPositiveButton("إضافة") { _, _ ->
                val a = src.text.toString().trim(); val b = tgt.text.toString().trim()
                if (a.isNotBlank() && b.isNotBlank()) { glossary[a] = b; saveGlossary(); toast("أُضيف") }
            }.setNegativeButton("إغلاق", null).show()
    }

    private fun showCharactersEditor() {
        val input = EditText(this).apply { setText(characters.joinToString(", ")); hint = "أسماء الشخصيات مفصولة بفواصل" }
        AlertDialog.Builder(this).setTitle("الشخصيات المعروفة").setView(input)
            .setPositiveButton("حفظ") { _, _ ->
                characters = input.text.toString().split(",").map { it.trim() }.filter { it.isNotBlank() }.toMutableList()
                getSharedPreferences("video_fox", MODE_PRIVATE).edit().putString("characters", characters.joinToString(",")).apply()
            }.setNegativeButton("إلغاء", null).show()
    }

    private fun saveGlossary() {
        getSharedPreferences("video_fox", MODE_PRIVATE).edit().putString("glossary", JSONObject(glossary as Map<*, *>).toString()).apply()
    }

    private fun showRemoteSettings() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18)) }
        val ep = EditText(this).apply {
            hint = "https://.../v1/chat/completions"; setText(aiEndpoint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val mdl = EditText(this).apply { hint = "model id"; setText(aiModel) }
        val key = EditText(this).apply {
            hint = "API key"; setText(aiApiKey)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        box.addView(text("Chat Completions endpoint", 12f, gold)); box.addView(ep)
        box.addView(text("model", 12f, gold)); box.addView(mdl)
        box.addView(text("API key", 12f, gold)); box.addView(key)
        AlertDialog.Builder(this).setTitle("محرك الترجمة").setView(box)
            .setPositiveButton("حفظ") { _, _ ->
                aiEndpoint = ep.text.toString().trim()
                aiModel = mdl.text.toString().trim()
                aiApiKey = key.text.toString().trim()
                getSharedPreferences("video_fox", MODE_PRIVATE).edit()
                    .putString("ai_endpoint", aiEndpoint).putString("ai_model", aiModel).putString("ai_key", aiApiKey).apply()
                status("حُفظ إعداد API")
            }.setNegativeButton("إلغاء", null).show()
    }

    private fun showLibrary() {
        val arr = runCatching { JSONArray(getSharedPreferences("video_fox", MODE_PRIVATE).getString("video_library", "[]")) }.getOrDefault(JSONArray())
        if (arr.length() == 0) { AlertDialog.Builder(this).setTitle("المكتبة").setMessage("فارغة").setPositiveButton("حسناً", null).show(); return }
        val labels = (0 until arr.length()).map { arr.optJSONObject(it)?.optString("name", "فيديو") ?: "فيديو" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("المكتبة").setItems(labels) { _, w ->
            val u = arr.optJSONObject(w)?.optString("uri").orEmpty()
            if (u.isNotBlank()) openVideo(Uri.parse(u))
        }.setNegativeButton("إغلاق", null).show()
    }

    private fun saveVideoToLibrary() {
        val prefs = getSharedPreferences("video_fox", MODE_PRIVATE)
        val old = runCatching { JSONArray(prefs.getString("video_library", "[]")) }.getOrDefault(JSONArray())
        val upd = JSONArray().put(JSONObject().put("name", currentVideoName).put("uri", lastSavedVideoUri))
        for (i in 0 until old.length()) {
            val item = old.optJSONObject(i) ?: continue
            if (item.optString("uri") != lastSavedVideoUri && upd.length() < 30) upd.put(item)
        }
        prefs.edit().putString("video_library", upd.toString()).apply()
    }

    private fun persistCues() {
        if (currentVideoKey.isBlank()) return
        val arr = JSONArray(); rawCues.forEach { arr.put(it.toJson()) }
        getSharedPreferences("video_fox", MODE_PRIVATE).edit().putString("cues_$currentVideoKey", arr.toString()).apply()
    }

    private fun loadCues() {
        if (currentVideoKey.isBlank()) return
        val raw = getSharedPreferences("video_fox", MODE_PRIVATE).getString("cues_$currentVideoKey", "[]") ?: "[]"
        val arr = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { rawCues.add(RawCue.fromJson(it)) }
    }

    private fun loadLensEntries(): JSONArray {
        val raw = getSharedPreferences("video_fox_lens", MODE_PRIVATE).getString("entries", "[]") ?: "[]"
        return runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
    }

    private fun exportSrt(uri: Uri) {
        try {
            contentResolver.openOutputStream(uri)?.use { out ->
                OutputStreamWriter(out, Charsets.UTF_8).use { w ->
                    var n = 1
                    rawCues.filter { it.translated.isNotBlank() }.forEach { cue ->
                        val end = cue.endMs.coerceAtLeast(cue.startMs + 500)
                        w.write("${n++}\n${srtTime(cue.startMs)} --> ${srtTime(end)}\n${cue.translated}\n\n")
                    }
                }
            }
            toast("تم التصدير")
        } catch (e: Exception) { toast("فشل: ${e.localizedMessage}") }
    }

    private fun exportJson(uri: Uri) {
        try {
            contentResolver.openOutputStream(uri)?.use { out ->
                val rootObj = JSONObject().put("video", currentVideoName)
                    .put("cues", JSONArray().also { a -> rawCues.forEach { a.put(it.toJson()) } })
                OutputStreamWriter(out, Charsets.UTF_8).use { it.write(rootObj.toString(2)) }
            }
            toast("تم التصدير")
        } catch (e: Exception) { toast("فشل: ${e.localizedMessage}") }
    }

    private fun srtTime(ms: Long): String {
        val h = ms / 3600000; val m = (ms / 60000) % 60; val s = (ms / 1000) % 60; val mi = ms % 1000
        return String.format(Locale.US, "%02d:%02d:%02d,%03d", h, m, s, mi)
    }

    private fun safeFileName(v: String) = v.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9_-]"), "_").take(48).ifBlank { "video_fox" }

    private fun loadSettings() {
        val p = getSharedPreferences("video_fox", MODE_PRIVATE)
        aiEndpoint = p.getString("ai_endpoint", "").orEmpty()
        aiModel = p.getString("ai_model", "").orEmpty()
        aiApiKey = p.getString("ai_key", "").orEmpty()
        scanIntervalMs = p.getLong("scan_interval", 900L)
        translationStyle = p.getString("translation_style", "طبيعية وسياقية") ?: "طبيعية وسياقية"
        translationMode = p.getString("translation_mode", "auto") ?: "auto"
        autoTranslateOnEnd = p.getBoolean("auto_translate", true)
        characters = p.getString("characters", "").orEmpty().split(",").map { it.trim() }.filter { it.isNotBlank() }.toMutableList()
        val g = runCatching { JSONObject(p.getString("glossary", "{}")) }.getOrNull()
        g?.keys()?.forEach { glossary[it] = g.optString(it) }
    }

    private fun resetState() { candidateText = ""; candidateHits = 0; activeText = "" }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull()

    private fun contentRectFor(view: View): RectF {
        val vw = view.width.toFloat().coerceAtLeast(1f); val vh = view.height.toFloat().coerceAtLeast(1f)
        val va = vw / vh
        return if (va > videoAspect) {
            val w = vh * videoAspect; val x = (vw - w) / 2f; RectF(x, 0f, x + w, vh)
        } else {
            val h = vw / videoAspect; val y = (vh - h) / 2f; RectF(0f, y, vw, y + h)
        }
    }

    private inner class RegionSelectionView(context: Context, val onDone: (RectF) -> Unit) : View(context) {
        private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = gold; style = Paint.Style.STROKE; strokeWidth = dp(2).toFloat() }
        private val shade = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55000000 }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x22FFB800 }
        private var sx = 0f; private var sy = 0f
        private var cur = RectF(); private var drawing = false; private var mode = false
        fun setSelectionMode(enabled: Boolean) { mode = enabled; visibility = if (enabled) VISIBLE else GONE; invalidate() }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas); if (!mode) return
            val c = contentRectFor(this); canvas.drawRect(c, shade)
            if (drawing || !cur.isEmpty) { canvas.drawRect(cur, fill); canvas.drawRect(cur, border) }
            val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = dp(13).toFloat(); textAlign = Paint.Align.CENTER }
            canvas.drawText("ارسم مستطيلاً حول الترجمة", width / 2f, dp(28).toFloat(), t)
        }
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (!mode) return false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> { sx = event.x; sy = event.y; cur = RectF(sx, sy, sx, sy); drawing = true; invalidate(); return true }
                MotionEvent.ACTION_MOVE -> { cur.set(sx, sy, event.x, event.y); cur.sort(); invalidate(); return true }
                MotionEvent.ACTION_UP -> {
                    cur.set(sx, sy, event.x, event.y); cur.sort(); drawing = false
                    val c = contentRectFor(this)
                    val clip = RectF(cur).apply {
                        left = left.coerceIn(c.left, c.right); right = right.coerceIn(c.left, c.right)
                        top = top.coerceIn(c.top, c.bottom); bottom = bottom.coerceIn(c.top, c.bottom)
                    }
                    if (clip.width() > dp(20) && clip.height() > dp(8)) {
                        onDone(RectF(
                            ((clip.left - c.left) / c.width()).coerceIn(0f, 1f),
                            ((clip.top - c.top) / c.height()).coerceIn(0f, 1f),
                            ((clip.right - c.left) / c.width()).coerceIn(0f, 1f),
                            ((clip.bottom - c.top) / c.height()).coerceIn(0f, 1f)
                        ))
                    } else Toast.makeText(this@MainActivity, "صغيرة جداً", Toast.LENGTH_SHORT).show()
                    invalidate(); return true
                }
            }
            return true
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() { if (studioVisible) returnToHome() else super.onBackPressed() }

    private fun button(label: String, bgColor: Int, fgColor: Int, action: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 13f; setTextColor(fgColor)
        backgroundTintList = android.content.res.ColorStateList.valueOf(bgColor)
        setOnClickListener { action() }
    }
    private fun text(v: String, s: Float, c: Int) = TextView(this).apply { text = v; textSize = s; setTextColor(c) }
    private fun matchWrap() = LinearLayout.LayoutParams(-1, -2)
    private fun weightParams(w: Float) = LinearLayout.LayoutParams(0, dp(46), w)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun status(v: String) { if (::statusText.isInitialized) statusText.text = v }
    private fun toast(v: String) = Toast.makeText(this, v, Toast.LENGTH_SHORT).show()
    private fun formatTime(ms: Long) = String.format(Locale.US, "%02d:%02d", ms / 60000, (ms / 1000) % 60)

    override fun onPause() { super.onPause(); if (::player.isInitialized) player.pause() }

    override fun onDestroy() {
        mainHandler.removeCallbacks(scanRunnable); scanEnabled = false
        runCatching { retriever?.release() }
        runCatching { recognizers.forEach { it.close() } }
        runCatching { translationEngine.close() }
        if (::player.isInitialized) player.release()
        frameExecutor.shutdownNow(); translateExecutor.shutdownNow()
        super.onDestroy()
    }
}