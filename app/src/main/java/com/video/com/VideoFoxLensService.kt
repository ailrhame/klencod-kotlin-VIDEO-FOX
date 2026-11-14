package com.video.fox

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
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
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class VideoFoxLensService : Service() {
    companion object {
        const val ACTION_START = "com.video.fox.action.START_LENS"
        const val EXTRA_RESULT_CODE = "video_fox_result_code"
        const val EXTRA_RESULT_DATA = "video_fox_result_data"
        private const val CHANNEL_ID = "video_fox_capture"
        private const val NOTIF_ID = 5105
        private const val STORE = "video_fox_lens"
        private const val KEY = "entries"
        private const val MAX = 2000
    }

    private lateinit var wm: WindowManager
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var lens: FoxLensView? = null
    private var panel: LinearLayout? = null
    private var panelStatus: TextView? = null
    private var scanBtn: TextView? = null
    private var panelVisible = false
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val translateWorker = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    private var scanning = false
    private var translating = false
    private var lastFrame = 0L
    private var lastText = ""
    private var stableHits = 0
    private var sessionId = ""
    private val engine = TranslationEngine()
    private val recognizers by lazy { listOf(
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS),
        TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build()),
        TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build()),
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    ) }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL_ID, "VIDEO FOX capture", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_START) { stopSelf(); return START_NOT_STICKY }
        val notif = buildNotif()
        if (Build.VERSION.SDK_INT >= 34)
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(NOTIF_ID, notif)

        val rc = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        else { @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_RESULT_DATA) }
        if (data == null || rc != android.app.Activity.RESULT_OK) { stopSelf(); return START_NOT_STICKY }
        startCapture(rc, data)
        return START_NOT_STICKY
    }

    private fun buildNotif(): Notification {
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        return b.setContentTitle("VIDEO FOX · lens")
            .setContentText("جمع النص فقط — الترجمة تُطلب يدوياً")
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setOngoing(true).build()
    }

    private fun startCapture(rc: Int, data: Intent) {
        try {
            val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = mgr.getMediaProjection(rc, data)
            val m = resources.displayMetrics
            reader = ImageReader.newInstance(m.widthPixels, m.heightPixels, PixelFormat.RGBA_8888, 3)
            projection?.registerCallback(object : MediaProjection.Callback() { override fun onStop() { stopSelf() } }, main)
            display = projection?.createVirtualDisplay("FOX_CAP", m.widthPixels, m.heightPixels, m.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader?.surface, null, main)
            reader?.setOnImageAvailableListener({ r ->
                if (!scanning || System.currentTimeMillis() - lastFrame < 1100L) r.acquireLatestImage()?.close()
                else { lastFrame = System.currentTimeMillis(); processFrame(r) }
            }, main)
            showLens()
        } catch (_: Exception) { stopSelf() }
    }

    private fun showLens() {
        val d = resources.displayMetrics.density
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else { @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE }

        val v = FoxLensView(this).apply { setOnTouchListener(DragListener()) }
        lens = v
        val lp = WindowManager.LayoutParams(
            (64 * d).toInt(), (64 * d).toInt(), type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.RIGHT; x = (12 * d).toInt(); y = (190 * d).toInt() }
        try { wm.addView(v, lp) } catch (_: Exception) { stopSelf(); return }
        createPanel(type, d)
    }

    private fun createPanel(type: Int, d: Float) {
        val p = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((14 * d).toInt(), (12 * d).toInt(), (14 * d).toInt(), (12 * d).toInt())
            background = GradientDrawable().apply { setColor(0xF214111D.toInt()); cornerRadius = 22 * d; setStroke((1.4f * d).toInt(), 0xFFFFC43D.toInt()) }
            elevation = 18 * d; visibility = View.GONE
        }
        p.addView(TextView(this).apply { text = "VIDEO FOX  ·  lens"; textSize = 14f; setTextColor(0xFFFFC43D.toInt()); typeface = android.graphics.Typeface.DEFAULT_BOLD })
        panelStatus = TextView(this).apply { text = "جاهز — جمع النص فقط"; textSize = 12f; setTextColor(0xFFD5D0DF.toInt()); setPadding(0, (7*d).toInt(), 0, (10*d).toInt()) }
        p.addView(panelStatus)
        scanBtn = action("بدء الجمع", d) { toggleScan() }
        p.addView(scanBtn)
        p.addView(action("ترجمة دفعة الآن", d) { translateNow() })
        p.addView(action("فتح المركز ↗", d) { openStudio() })
        p.addView(action("إغلاق", d) { togglePanel(false) })
        panel = p
        val lp = WindowManager.LayoutParams((240 * d).toInt(), -2, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.RIGHT; x = (12*d).toInt(); y = (260*d).toInt() }
        try { wm.addView(p, lp) } catch (_: Exception) { panel = null }
    }

    private fun action(label: String, d: Float, click: () -> Unit) = TextView(this).apply {
        text = label; textSize = 13f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
        setPadding((10*d).toInt(), (11*d).toInt(), (10*d).toInt(), (11*d).toInt())
        background = GradientDrawable().apply { setColor(0xFF25202F.toInt()); cornerRadius = 13*d; setStroke((1f * d).toInt(), 0x664A405A) }
        setOnClickListener { click() }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = (5*d).toInt() }
    }

    private fun togglePanel(show: Boolean) { panelVisible = show; panel?.visibility = if (show) View.VISIBLE else View.GONE }

    private fun toggleScan() {
        if (translating) { panelStatus?.text = "الترجمة قيد التنفيذ…"; return }
        scanning = !scanning
        if (scanning) {
            sessionId = System.currentTimeMillis().toString()
            lastText = ""; stableHits = 0
            togglePanel(false)
        }
        lens?.invalidate()
        scanBtn?.text = if (scanning) "إيقاف الجمع" else "بدء الجمع"
        panelStatus?.text = if (scanning) "جمع النص فقط — بدون ترجمة الآن" else "متوقف — اضغط ترجمة دفعة"
    }

    private fun openStudio() {
        scanning = false; lens?.invalidate()
        scanBtn?.text = "بدء الجمع"
        val i = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(MainActivity.EXTRA_OPEN_STUDIO, true)
        }
        runCatching { startActivity(i); togglePanel(false) }
    }

    private fun translateNow() {
        if (translating) { panelStatus?.text = "قيد التنفيذ"; return }
        val entries = runCatching { JSONArray(getSharedPreferences(STORE, MODE_PRIVATE).getString(KEY, "[]")) }.getOrDefault(JSONArray())
        val pending = mutableListOf<Pair<Int, RawCue>>()
        for (i in 0 until entries.length()) {
            val o = entries.optJSONObject(i) ?: continue
            if (o.optString("translated").isBlank()) pending.add(i to RawCue(o.optLong("start", 0), o.optLong("end", 0), o.optString("source")))
        }
        if (pending.isEmpty()) { panelStatus?.text = "لا مقاطع بانتظار الترجمة"; return }

        val p = getSharedPreferences("video_fox", MODE_PRIVATE)
        val ep = p.getString("ai_endpoint", "").orEmpty(); val mdl = p.getString("ai_model", "").orEmpty()
        if (ep.isBlank() || mdl.isBlank()) { panelStatus?.text = "محرك الترجمة غير مضبوط — افتح المركز"; return }

        val style = p.getString("translation_style", "طبيعية وسياقية") ?: "طبيعية وسياقية"
        val key = p.getString("ai_key", "").orEmpty()
        val gl = runCatching { JSONObject(p.getString("glossary", "{}")) }.getOrDefault(JSONObject())
        val glMap = mutableMapOf<String, String>(); gl.keys().forEach { glMap[it] = gl.optString(it) }
        val chars = p.getString("characters", "").orEmpty().split(",").map { it.trim() }.filter { it.isNotBlank() }

        translating = true
        panelStatus?.text = "ترجمة ${pending.size} مقطع…"

        translateWorker.execute {
            try {
                val working = pending.map { it.second }.toMutableList()
                engine.translateBatch(working, TranslationConfig(ep, mdl, key, style, glMap, chars)) { done, total ->
                    main.post { panelStatus?.text = "ترجمة $done / $total" }
                }
                for (k in pending.indices) {
                    val (origIdx, _) = pending[k]
                    val obj = entries.optJSONObject(origIdx) ?: continue
                    obj.put("translated", working[k].translated)
                    obj.put("speaker", working[k].speaker)
                    obj.put("emotion", working[k].emotion)
                    obj.put("status", "done")
                }
                trimSave(entries)
                main.post { panelStatus?.text = "✓ اكتملت الترجمة" }
            } catch (e: Exception) {
                main.post { panelStatus?.text = "فشل: ${e.message}" }
            } finally { translating = false }
        }
    }

    private fun processFrame(r: ImageReader) {
        if (!busy.compareAndSet(false, true)) { r.acquireLatestImage()?.close(); return }
        val img = r.acquireLatestImage() ?: run { busy.set(false); return }
        worker.execute {
            var full: Bitmap? = null; var crop: Bitmap? = null; var scaled: Bitmap? = null; var mlOwns = false
            try {
                val plane = img.planes[0]; val buf = plane.buffer
                val ps = plane.pixelStride; val rs = plane.rowStride
                val pad = rs - ps * img.width
                val pw = img.width + pad / ps
                val fb = Bitmap.createBitmap(pw, img.height, Bitmap.Config.ARGB_8888); full = fb
                fb.copyPixelsFromBuffer(buf)
                val cb = Bitmap.createBitmap(fb, 0, 0, img.width, img.height); crop = cb
                val s = if (cb.width > 1200) Bitmap.createScaledBitmap(cb, 1200, (cb.height * 1200f / cb.width).toInt().coerceAtLeast(1), true) else cb
                scaled = s
                val input = InputImage.fromBitmap(s, 0)
                val tasks = recognizers.map { e -> e.process(input).continueWith { t -> if (t.isSuccessful) t.result?.text.orEmpty() else "" } }
                mlOwns = true
                Tasks.whenAllSuccess<String>(tasks).addOnSuccessListener { res ->
                    val cands = res.filter { it.isNotBlank() }.distinct()
                    val best = cands.maxByOrNull { score(it) }.orEmpty()
                    accept(best)
                }.addOnCompleteListener {
                    runCatching { if (s !== cb) s.recycle() }; runCatching { cb.recycle() }; runCatching { fb.recycle() }
                    busy.set(false)
                }
            } catch (_: Exception) { busy.set(false) }
            finally {
                img.close()
                if (!mlOwns) { runCatching { if (scaled !== crop) scaled?.recycle() }; runCatching { crop?.recycle() }; runCatching { full?.recycle() } }
            }
        }
    }

    private fun score(v: String): Int {
        val c = v.replace(Regex("\\s+"), " ").trim()
        return c.count { it.isLetter() } * 3 + c.length - c.count { it.isISOControl() || it in "|_~`" } * 5
    }

    private fun accept(raw: String) {
        val clean = raw.replace(Regex("\\s+"), " ").trim()
        if (clean.length < 3) return
        if (clean.equals(lastText, true)) stableHits++ else { lastText = clean; stableHits = 1 }
        if (stableHits < 2) return
        val now = System.currentTimeMillis()
        val prefs = getSharedPreferences(STORE, MODE_PRIVATE)
        val entries = runCatching { JSONArray(prefs.getString(KEY, "[]")) }.getOrDefault(JSONArray())
        if (entries.length() > 0) {
            val prior = entries.optJSONObject(entries.length() - 1)
            if (prior?.optString("source")?.equals(clean, true) == true && now - prior.optLong("capturedAt") < 12000L) return
        }
        val entry = JSONObject().put("id", "${sessionId}_$now").put("sessionId", sessionId).put("capturedAt", now)
            .put("start", now).put("end", now + 2500).put("source", clean)
            .put("translated", "").put("speaker", "").put("emotion", "").put("status", "pending")
        entries.put(entry)
        trimSave(entries)
        main.post { panelStatus?.text = "التقطت ${entries.length()} مقطعاً" }
    }

    private fun trimSave(e: JSONArray) {
        val start = (e.length() - MAX).coerceAtLeast(0)
        val c = JSONArray(); for (i in start until e.length()) e.optJSONObject(i)?.let { c.put(it) }
        getSharedPreferences(STORE, MODE_PRIVATE).edit().putString(KEY, c.toString()).apply()
    }

    private inner class DragListener : View.OnTouchListener {
        private var dx0 = 0f; private var dy0 = 0f; private var sx0 = 0; private var sy0 = 0; private var dragged = false
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            val p = v.layoutParams as WindowManager.LayoutParams
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { dx0 = e.rawX; dy0 = e.rawY; sx0 = p.x; sy0 = p.y; dragged = false; return true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - dx0).toInt(); val dy = (e.rawY - dy0).toInt()
                    if (kotlin.math.abs(dx) + kotlin.math.abs(dy) > 9) dragged = true
                    if (dragged) {
                        val hg = p.gravity and Gravity.HORIZONTAL_GRAVITY_MASK
                        p.x = (if (hg == Gravity.RIGHT) sx0 - dx else sx0 + dx).coerceAtLeast(0)
                        p.y = (sy0 + dy).coerceAtLeast(8)
                        runCatching { wm.updateViewLayout(v, p) }
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> { if (dragged) snap(v) else togglePanel(!panelVisible); return true }
            }
            return true
        }
    }

    private fun snap(v: View) {
        val p = v.layoutParams as WindowManager.LayoutParams
        val w = resources.displayMetrics.widthPixels
        val hg = p.gravity and Gravity.HORIZONTAL_GRAVITY_MASK
        val cx = if (hg == Gravity.RIGHT) w - p.x - v.width / 2 else p.x + v.width / 2
        p.gravity = Gravity.TOP or (if (cx < w / 2) Gravity.LEFT else Gravity.RIGHT)
        p.x = (10 * resources.displayMetrics.density).toInt()
        runCatching { wm.updateViewLayout(v, p) }
    }

    override fun onDestroy() {
        scanning = false
        runCatching { reader?.setOnImageAvailableListener(null, null) }
        runCatching { display?.release() }; runCatching { reader?.close() }; runCatching { projection?.stop() }
        runCatching { recognizers.forEach { it.close() } }
        runCatching { lens?.let { wm.removeView(it) } }
        runCatching { panel?.let { wm.removeView(it) } }
        worker.shutdownNow(); translateWorker.shutdownNow()
        super.onDestroy()
    }

    private inner class FoxLensView(ctx: Context) : View(ctx) {
        private val d = resources.displayMetrics.density
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG)
        private var pulse = 0f
        private val pulseRunnable = object : Runnable {
            override fun run() { pulse = (pulse + 0.05f) % 1f; invalidate(); if (scanning || translating) postDelayed(this, 40) }
        }
        init { post(pulseRunnable) }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat(); val h = height.toFloat()
            val radius = minOf(w, h) * 0.28f
            val rect = RectF(1.5f*d, 1.5f*d, w - 1.5f*d, h - 1.5f*d)

            fill.style = Paint.Style.FILL
            fill.color = 0xF0100D18.toInt()
            canvas.drawRoundRect(rect, radius, radius, fill)

            val borderColor = when {
                translating -> 0xFFFFB800.toInt()
                scanning -> 0xFF53D68A.toInt()
                else -> 0xFF4A4058.toInt()
            }
            stroke.style = Paint.Style.STROKE
            stroke.strokeWidth = 1.6f * d
            stroke.color = borderColor
            canvas.drawRoundRect(rect, radius, radius, stroke)

            val cx = w / 2f; val cy = h / 2f
            val fs = minOf(w, h) * 0.32f
            fill.style = Paint.Style.FILL
            fill.color = if (scanning) 0xFFFFD05A.toInt() else 0xFFFFB800.toInt()
            val path = Path().apply {
                moveTo(cx - fs*0.9f, cy + fs*0.1f)
                lineTo(cx - fs*0.7f, cy - fs*1.1f)
                lineTo(cx - fs*0.15f, cy - fs*0.55f)
                lineTo(cx + fs*0.15f, cy - fs*0.55f)
                lineTo(cx + fs*0.7f, cy - fs*1.1f)
                lineTo(cx + fs*0.9f, cy + fs*0.1f)
                quadTo(cx, cy + fs*1.15f, cx - fs*0.9f, cy + fs*0.1f)
                close()
            }
            canvas.drawPath(path, fill)

            fill.color = 0xFF17121E.toInt()
            canvas.drawOval(cx - fs*0.55f, cy - fs*0.15f, cx - fs*0.1f, cy + fs*0.25f, fill)
            canvas.drawOval(cx + fs*0.1f, cy - fs*0.15f, cx + fs*0.55f, cy + fs*0.25f, fill)
            fill.color = 0xFFFFF3CF.toInt()
            canvas.drawCircle(cx - fs*0.32f, cy + fs*0.05f, 1.6f*d, fill)
            canvas.drawCircle(cx + fs*0.32f, cy + fs*0.05f, 1.6f*d, fill)

            if (scanning || translating) {
                stroke.color = if (translating) 0x88FFB800.toInt() else 0x8853D68A.toInt()
                stroke.strokeWidth = 2.5f * d
                val pr = radius + (rect.width() / 2f - radius) * pulse
                canvas.drawRoundRect(RectF(rect.left - pr*0.15f, rect.top - pr*0.15f, rect.right + pr*0.15f, rect.bottom + pr*0.15f), radius + pr*0.15f, radius + pr*0.15f, stroke)
            }

            fill.color = when {
                translating -> 0xFFFFB800.toInt()
                scanning -> 0xFF53D68A.toInt()
                else -> 0xFF8B8297.toInt()
            }
            canvas.drawCircle(w - 9*d, h - 9*d, 3.5f*d, fill)
        }
    }
}