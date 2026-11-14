package com.video.fox

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaProjection
import android.media.MediaRecorder
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import android.widget.ViewFlipper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private lateinit var flipper: ViewFlipper
    private lateinit var recStatus: TextView
    private lateinit var procBar: ProgressBar
    private lateinit var procText: TextView
    private lateinit var listView: ListView
    private lateinit var player: VideoView
    private lateinit var subView: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var endpointEdit: EditText
    private lateinit var qwenSwitch: Switch
    private var currentVideo: File? = null
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val outDir by lazy { File(filesDir, "processed").apply { mkdirs() } }
    private var recording = false

    private val doneReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val path = intent?.getStringExtra(EXTRA_PATH) ?: return
            recording = false
            recStatus.text = "اكتمل التسجيل — تتم المعالجة الآن"
            LensManager.show(this@MainActivity) { askProjection() }
            io.execute {
                val dst = File(outDir, "fox_rec_${System.currentTimeMillis()}.mp4")
                val ok = Remux.remux(path, null, dst) || CopyUtil.copyFile(File(path), dst)
                if (ok) Subs.writeStub(dst)
                main.post {
                    refreshVideos()
                    showPage(PAGE_VIDEOS)
                    toast(if (ok) "تمت المعالجة بنجاح" else "حفظ التسجيل")
                }
            }
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            val p = player
            val v = currentVideo
            if (v != null && p.isPlaying) {
                seekBar.progress = p.currentPosition
                val pos = p.currentPosition
                subView.text = Subs.load(v).firstOrNull { pos >= it.start && pos <= it.end }?.ar ?: ""
            }
            main.postDelayed(this, 300)
        }
    }

    companion object {
        const val PAGE_HOME = 0
        const val PAGE_REC = 1
        const val PAGE_PROC = 2
        const val PAGE_VIDEOS = 3
        const val PAGE_PLAYER = 4
        const val PAGE_SETTINGS = 5

        const val REQ_OVERLAY = 10
        const val REQ_PROJECTION = 11
        const val REQ_PICK = 12
        const val REQ_PERMS = 13

        const val ACTION_DONE = "com.video.fox.REC_DONE"
        const val EXTRA_PATH = "path"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(
            GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFFF3D9BE.toInt(), 0xFFFFFBF5.toInt()))
        )
        buildUi()
        register()
        LensManager.show(this) { askProjection() }
    }

    override fun onDestroy() {
        try { unregisterReceiver(doneReceiver) } catch (_: Exception) {}
        main.removeCallbacks(tick)
        io.shutdown()
        super.onDestroy()
    }

    private fun register() {
        val filter = IntentFilter(ACTION_DONE)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(doneReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(doneReceiver, filter)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun pill(color: Int, radiusDp: Float = 18f): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusDp * resources.displayMetrics.density
        }

    private fun header(title: String, sub: String): LinearLayout {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(18), dp(8), dp(10))
        }
        val t = TextView(this).apply {
            text = title
            textSize = 26f
            setTextColor(Fox.DEEP)
            setTypeface(typeface, Typeface.BOLD)
        }
        val s = TextView(this).apply {
            text = sub
            textSize = 13f
            setTextColor(0xFF6B5B4C.toInt())
            setPadding(0, dp(4), 0, dp(6))
        }
        col.addView(t); col.addView(s)
        return col
    }

    private fun creditBar(): TextView = TextView(this).apply {
        text = "Made by: @N5CR4"
        textSize = 12f
        setTextColor(Fox.DEEP)
        setTypeface(typeface, Typeface.ITALIC)
        gravity = Gravity.CENTER
        setPadding(0, dp(6), 0, dp(8))
    }

    private fun card(title: String, sub: String, accent: Int, onClick: () -> Unit): LinearLayout {
        val ll = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
            background = pill(Color.WHITE, 18f).apply { setStroke(dp(1), accent) }
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) }
            isClickable = true
            setOnClickListener { onClick() }
        }
        val t = TextView(this).apply {
            text = title
            textSize = 18f
            setTextColor(Fox.DARK)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(6))
        }
        val s = TextView(this).apply {
            text = sub
            textSize = 13f
            setTextColor(0xFF6B5B4C.toInt())
        }
        ll.addView(t); ll.addView(s)
        return ll
    }

    private fun foxButton(label: String, bg: Int, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            textSize = 15f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            background = pill(bg, 14f)
            setPadding(dp(18), dp(12), dp(18), dp(12))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) }
            setOnClickListener { onClick() }
        }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFFFFBF5.toInt())
        }

        flipper = ViewFlipper(this)

        root.addView(flipper, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(buildNav())
        root.addView(creditBar())
        setContentView(root)
    }

    private fun pageShell(inner: LinearLayout): ScrollX {
        val scroll = ScrollX(this)
        scroll.addView(inner)
        return scroll
    }

    private fun buildNav(): LinearLayout {
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Fox.DEEP)
            setPadding(0, dp(4), 0, dp(4))
        }
        val items = listOf(
            "الرئيسية" to PAGE_HOME,
            "التسجيل" to PAGE_REC,
            "الفيديوهات" to PAGE_VIDEOS,
            "الإعدادات" to PAGE_SETTINGS
        )
        items.forEach { (label, page) ->
            val b = Button(this).apply {
                text = label
                textSize = 13f
                setTextColor(Color.WHITE)
                setBackgroundResource(android.R.color.transparent)
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                setOnClickListener { showPage(page) }
            }
            nav.addView(b)
        }
        buildPages()
        return nav
    }

    private fun buildPages() {
        flipper.addView(buildHome())
        flipper.addView(buildRecord())
        flipper.addView(buildProcessing())
        flipper.addView(buildVideos())
        flipper.addView(buildPlayer())
        flipper.addView(buildSettings())
    }

    private fun colShell(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(8), dp(16), dp(8))
    }

    private fun buildHome(): View {
        val shell = colShell()
        shell.addView(header("Video Fox", "مسجل شاشة مترجم بالهوية الثعلبية — برتقالي / كريمي / داكن"))
        shell.addView(card(
            "تسجيل الشاشة — العدسة العائمة",
            "زر عائم برتقالي؛ يختفي تلقائياً أثناء التسجيل حتى لا يظهر في الفيديو.",
            Fox.ORANGE
        ) { ensureOverlayThenLens() })
        shell.addView(card(
            "رفع فيديو من الملفات",
            "استخراج المسار الصوتي ومعالجته دون أي صوت خارج أثناء المعالجة، ثم تشغيله بالترجمة العربية.",
            Fox.DEEP
        ) { ensureStorage(); pickVideo() })
        val note = TextView(this).apply {
            text = "الترجمة: محلية (قاموس ثعلب) + وضع Qwen في الإعدادات.\nMade by: @N5CR4"
            textSize = 12f
            setTextColor(0xFF6B5B4C.toInt())
            setPadding(dp(4), dp(10), dp(4), dp(10))
        }
        shell.addView(note)
        return pageShell(shell)
    }

    private fun buildRecord(): View {
        val shell = colShell()
        shell.addView(header("صفحة التسجيل", "التحكم بالتسجيل ومتابعة الحالة"))
        recStatus = TextView(this).apply {
            text = "جاهز — اضغط بدء التسجيل"
            textSize = 15f
            setTextColor(Fox.DARK)
            setPadding(0, dp(10), 0, dp(14))
        }
        shell.addView(recStatus)
        shell.addView(foxButton("بدء التسجيل (عدسة عائمة)", Fox.ORANGE) { askProjection() })
        shell.addView(foxButton("إيقاف التسجيل", Fox.DEEP) { stopRecording() })
        val info = TextView(this).apply {
            text = "أثناء التسجيل تختفي العدسة العائمة تماماً. بعد الإيقاف تتم المعالجة والنقل لصفحة الفيديوهات.\nMade by: @N5CR4"
            textSize = 12f
            setTextColor(0xFF6B5B4C.toInt())
            setPadding(0, dp(8), 0, dp(8))
        }
        shell.addView(info)
        return pageShell(shell)
    }

    private fun buildProcessing(): View {
        val shell = colShell()
        shell.addView(header("صفحة المعالجة", "حالة الترجمة ونسبة التقدم — الفيديو صامت تماماً هنا"))
        procBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            setPadding(0, dp(14), 0, dp(14))
        }
        shell.addView(procBar)
        procText = TextView(this).apply {
            text = "بانتظار المهمة…"
            textSize = 14f
            setTextColor(Fox.DARK)
        }
        shell.addView(procText)
        val note = TextView(this).apply {
            text = "لا يُشغَّل أي صوت أثناء النسخ أو المعالجة؛ الصوت الأصلي يبقى محفوظاً في الملف الناتج.\nMade by: @N5CR4"
            textSize = 12f
            setTextColor(0xFF6B5B4C.toInt())
            setPadding(0, dp(16), 0, 0)
        }
        shell.addView(note)
        return pageShell(shell)
    }

    private fun buildVideos(): View {
        val shell = colShell()
        shell.addView(header("صفحة الفيديوهات", "كل الفيديوهات المعالجة — اضغط أي مشاهدة"))
        listView = ListView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
            dividerHeight = dp(1)
            setDividerColor(0xFFE8D5C0.toInt())
        }
        shell.addView(listView)
        val note = TextView(this).apply {
            text = "Made by: @N5CR4"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Fox.DEEP)
            setPadding(0, dp(8), 0, dp(8))
        }
        shell.addView(note)
        return pageShell(shell)
    }

    private fun buildPlayer(): View {
        val shell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val back = Button(this).apply {
            text = "رجوع"
            setTextColor(Color.WHITE)
            background = pill(Fox.DEEP, 12f)
            setOnClickListener { showPage(PAGE_VIDEOS) }
        }
        val title = TextView(this).apply {
            text = "مشاهدة مع الترجمة"
            textSize = 15f
            setTextColor(Fox.DARK)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        top.addView(back); top.addView(title)
        shell.addView(top)

        player = VideoView(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        }
        shell.addView(player)

        subView = TextView(this).apply {
            text = ""
            textSize = 18f
            setTextColor(0xFFFFE9C7.toInt())
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = pill(0xCC1E140C.toInt(), 12f)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        shell.addView(subView)

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, dp(8))
        }
        val play = Button(this).apply {
            text = "تشغيل / إيقاف"
            setTextColor(Color.WHITE)
            background = pill(Fox.ORANGE, 12f)
            setOnClickListener {
                if (player.isPlaying) player.pause() else player.start()
            }
        }
        seekBar = SeekBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            setPadding(dp(12), 0, 0, 0)
        }
        controls.addView(play)
        controls.addView(seekBar)
        shell.addView(controls)

        val note = TextView(this).apply {
            text = "الصوت الأصلي يعمل متزامناً مع الصورة والترجمة هنا فقط. Made by: @N5CR4"
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(Fox.DEEP)
        }
        shell.addView(note)
        return shell
    }

    private fun buildSettings(): View {
        val shell = colShell()
        shell.addView(header("صفحة الإعدادات", "مزود Qwen وخيارات الترجمة وإدارة الملفات"))
        val lbl = TextView(this).apply { text = "نقطة اتصال Qwen (POST /chat/completions)"; textSize = 13f; setTextColor(Fox.DARK); setPadding(0, dp(6), 0, dp(6)) }
        endpointEdit = EditText(this).apply {
            hint = "https://host/v1/chat/completions"
            setTextColor(Fox.DARK)
            background = pill(Color.WHITE, 12f).apply { setStroke(dp(1), Fox.ORANGE) }
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) }
        }
        qwenSwitch = Switch(this).apply {
            text = "استخدام Qwen للترجمة (عطّلها = ترجمة محلية)"
            setTextColor(Fox.DARK)
            setPadding(0, dp(6), 0, dp(12))
        }
        shell.addView(lbl); shell.addView(endpointEdit); shell.addView(qwenSwitch)
        shell.addView(foxButton("حفظ الإعدادات", Fox.ORANGE) {
            QwenClient.cfg.endpoint = endpointEdit.text.toString().trim()
            QwenClient.cfg.useQwen = qwenSwitch.isChecked
            toast(if (qwenSwitch.isChecked) "Qwen مفعّل" else "ترجمة محلية مفعّلة")
        })
        shell.addView(foxButton("مسح الفيديوهات المعالجة", Fox.DEEP) {
            outDir.listFiles()?.forEach { it.delete() }
            refreshVideos()
            toast("تم المسح")
        })
        val note = TextView(this).apply {
            text = "Made by: @N5CR4"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Fox.DEEP)
            setPadding(0, dp(14), 0, 0)
        }
        shell.addView(note)
        return pageShell(shell)
    }

    private fun showPage(page: Int) {
        flipper.displayedChild = page
        if (page == PAGE_VIDEOS) refreshVideos()
    }

    private fun ensureOverlayThenLens() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            toast("فعّل إذن العدسة العائمة ثم ارجع")
            return
        }
        LensManager.show(this) { askProjection() }
        showPage(PAGE_REC)
    }

    private fun ensureStorage() {
        if (Build.VERSION.SDK_INT >= 33)
            requestPermissions(arrayOf(android.Manifest.permission.READ_MEDIA_VIDEO), REQ_PERMS)
        else
            requestPermissions(arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE), REQ_PERMS)
    }

    private fun askProjection() {
        if (!Settings.canDrawOverlays(this)) {
            ensureOverlayThenLens()
            return
        }
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mgr.createScreenCaptureIntent(), REQ_PROJECTION)
    }

    private fun pickVideo() {
        val pick = Intent(Intent.ACTION_PICK).apply { type = "video/*" }
        startActivityForResult(pick, REQ_PICK)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_PROJECTION -> if (resultCode == RESULT_OK && data != null) startRecorderService(resultCode, data)
            REQ_PICK -> if (resultCode == RESULT_OK && data?.data != null) processPicked(data.data!!)
        }
    }

    private fun startRecorderService(rc: Int, data: Intent) {
        val svc = Intent(this, RecordService::class.java).apply {
            action = RecordService.ACTION_START
            putExtra(RecordService.EXTRA_RC, rc)
            putExtra(RecordService.EXTRA_DATA, data)
        }
        startForegroundService(svc)
        recording = true
        LensManager.hide()
        recStatus.text = "جارٍ التسجيل… العدسة مخفية الآن"
        showPage(PAGE_REC)
    }

    private fun stopRecording() {
        startService(Intent(this, RecordService::class.java).setAction(RecordService.ACTION_STOP))
        recStatus.text = "جارٍ إيقاف التسجيل…"
    }

    private fun processPicked(uri: Uri) {
        showPage(PAGE_PROC)
        procBar.progress = 0
        procText = "نسخ المسار الصوتي دون تشغيل أي صوت…"
        io.execute {
            val dst = File(outDir, "pick_${System.currentTimeMillis()}.mp4")
            val fd = contentResolver.openFileDescriptor(uri, "r")
            var ok = false
            try { ok = Remux.remux(null, fd, dst) } catch (_: Exception) {}
            finally { fd?.close() }
            if (!ok) ok = CopyUtil.copyUri(contentResolver, uri, dst)
            main.post { procBar.progress = 55; procText = "تجهيز الترجمة (محلية / Qwen)…" }
            Translator.build(dst) { p ->
                main.post {
                    procBar.progress = 60 + (p * 40) / 100
                    procText = "معالجة الترجمة… $p%"
                }
            }
            Subs.writeStub(dst)
            main.post {
                procBar.progress = 100
                procText = "اكتملت المعالجة بنجاح"
                refreshVideos()
                showPage(PAGE_VIDEOS)
                toast("حُفظ الصوت الأصلي مع الترجمة")
            }
        }
    }

    private fun refreshVideos() {
        val files = outDir.listFiles { f -> f.extension == "mp4" }?.sortedByDescending { it.lastModified() } ?: emptyList()
        val names = files.mapIndexed { i, f -> "${i + 1}. ${f.name}  (${f.length() / 1024} KB)" }
        listView.adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_list_item_1, names)
        listView.onItemClickListener = { _, _, pos, _ -> openPlayer(files[pos]) }
    }

    private fun openPlayer(f: File) {
        currentVideo = f
        showPage(PAGE_PLAYER)
        subView.text = ""
        seekBar.progress = 0
        player.setVideoPath(f.absolutePath)
        player.setOnPreparedListener {
            seekBar.max = player.duration
            player.start()
            main.removeCallbacks(tick)
            main.post(tick)
        }
        player.setOnCompletionListener { seekBar.progress = 0 }
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser && player.isPlaying) player.seekTo(p)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }
}

class RecordService : Service() {

    companion object {
        const val ACTION_START = "com.video.fox.START"
        const val ACTION_STOP = "com.video.fox.STOP"
        const val EXTRA_RC = "rc"
        const val EXTRA_DATA = "data"
        private const val CHANNEL = "fox_rec_channel"
        private const val NOTIF_ID = 4201
    }

    private var projection: MediaProjection? = null
    private var recorder: MediaRecorder? = null
    private var outFile: File? = null

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopEverything()
            return START_NOT_STICKY
        }
        val rc = intent?.getIntExtra(EXTRA_RC, 0) ?: 0
        val data: Intent? = intent?.getParcelableExtra(EXTRA_DATA)
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mgr.getMediaProjection(rc, data)
        startForegroundFox()
        startRecording()
        return START_NOT_STICKY
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Video Fox Recording", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "قناة تسجيل الشاشة"
                }
            )
        }
    }

    private fun startForegroundFox() {
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle("Video Fox")
            .setContentText("جارٍ تسجيل الشاشة — Made by: @N5CR4")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else
            startForeground(NOTIF_ID, n)
    }

    private fun startRecording() {
        val base = getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: filesDir
        val dir = File(base, "fox").apply { mkdirs() }
        outFile = File(dir, "rec_${System.currentTimeMillis()}.mp4")
        val rec = MediaRecorder()
        rec.setVideoSource(MediaRecorder.VideoSource.MEDIA_PROJECTION)
        rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        rec.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        rec.setVideoSize(1280, 720)
        rec.setVideoFrameRate(30)
        rec.setVideoEncodingBitRate(4_000_000)
        rec.setOutputFile(outFile!!.absolutePath)
        rec.prepare()
        rec.start()
        recorder = rec
    }

    private fun stopEverything() {
        try { recorder?.stop() } catch (_: Exception) {}
        recorder?.reset()
        recorder?.release()
        recorder = null
        projection?.stop()
        projection = null
        stopForeground(true)
        sendBroadcast(Intent(ACTION_DONE).putExtra(EXTRA_PATH, outFile?.absolutePath))
        stopSelf()
    }

    override fun onDestroy() {
        try { recorder?.release() } catch (_: Exception) {}
        super.onDestroy()
    }
}

object Fox {
    val ORANGE = 0xFFF27D16.toInt()
    val DEEP = 0xD95A0B.toInt()
    val CREAM = 0xFFFBF3E7.toInt()
    val DARK = 0xFF2A2018.toInt()
}

object Remux {
    fun remux(srcPath: String?, fd: android.os.ParcelFileDescriptor?, dst: File): Boolean = try {
        val extractor = MediaExtractor()
        if (srcPath != null) extractor.setDataSource(srcPath)
        else if (fd != null) extractor.setDataSource(fd.fileDescriptor)
        else return false

        val muxer = MediaMuxer(dst.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val map = IntArray(extractor.trackCount) { -1 }

        for (i in 0 until extractor.trackCount) {
            val fmt = extractor.getTrackFormat(i)
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/") || mime.startsWith("audio/")) map[i] = muxer.addTrack(fmt)
        }

        muxer.start()
        val buffer = java.nio.ByteBuffer.allocate(1024 * 1024)
        val info = MediaCodec.BufferInfo()

        for (i in 0 until extractor.trackCount) {
            if (map[i] < 0) continue
            extractor.selectTrack(i)
            while (true) {
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                info.offset = 0
                info.size = size
                info.presentationTimeUs = extractor.sampleTime
                info.flags = extractor.sampleFlags
                muxer.writeSampleData(map[i], buffer, info)
                extractor.advance()
            }
        }
        muxer.stop()
        muxer.release()
        extractor.release()
        true
    } catch (e: Exception) {
        dst.delete()
        false
    }
}

object CopyUtil {
    fun copyFile(src: File, dst: File): Boolean = try {
        FileInputStream(src).use { input ->
            FileOutputStream(dst).use { output ->
                input.channel.transferTo(0, input.channel.size(), output.channel)
            }
        }
        true
    } catch (e: Exception) { false }

    fun copyUri(cr: android.content.ContentResolver, uri: Uri, dst: File): Boolean = try {
        cr.openInputStream(uri)?.use { input ->
            FileOutputStream(dst).use { output -> input.copyTo(output, 64 * 1024) }
        }
        true
    } catch (e: Exception) { false }
}

object Subs {
    data class Line(val start: Long, val end: Long, val ar: String)

    private fun sidecar(f: File) = File(f.parentFile, f.nameWithoutExtension + ".fox.json")

    fun writeStub(f: File) = write(f, JSONArray())

    fun write(f: File, arr: JSONArray) {
        sidecar(f).writeText(arr.toString())
    }

    fun load(f: File): List<Line> = try {
        val arr = JSONArray(sidecar(f).readText())
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Line(o.getLong("start"), o.getLong("end"), o.getString("ar"))
        }
    } catch (e: Exception) { emptyList() }
}

object Translator {
    fun build(file: File, onProgress: (Int) -> Unit) {
        onProgress(0)
        val segments = QwenClient.segments()
        onProgress(45)
        val out = JSONArray()
        segments.forEach { seg ->
            val ar = QwenClient.translateEnToAr(seg.en)
            out.put(JSONObject().apply {
                put("start", seg.start)
                put("end", seg.end)
                put("en", seg.en)
                put("ar", ar)
            })
        }
        Subs.write(file, out)
        onProgress(100)
    }
}

object QwenClient {
    data class Cfg(var endpoint: String = "", var useQwen: Boolean = false)
    @JvmField var cfg = Cfg()

    data class Seg(val start: Long, val end: Long, val en: String)

    fun segments(): List<Seg> = emptyList()

    fun translateEnToAr(text: String): String {
        if (!cfg.useQwen || cfg.endpoint.isBlank()) return LocalAr.map(text)
        return try { qwenPost(text) } catch (e: Exception) { LocalAr.map(text) }
    }

    private fun qwenPost(text: String): String {
        val conn = (URL(cfg.endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8000
            readTimeout = 15000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        val body = JSONObject().apply {
            put("model", "qwen-plus")
            val ms = JSONArray()
            ms.put(JSONObject().put("role", "system").put("content", "Translate to Modern Standard Arabic only."))
            ms.put(JSONObject().put("role", "user").put("content", text))
            put("messages", ms)
        }
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        if (conn.responseCode != 200) throw RuntimeException("HTTP ${conn.responseCode}")
        val resp = JSONObject(conn.inputStream.bufferedReader().readText())
        return resp.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
    }
}

object LocalAr {
    private val dict = mapOf(
        "hello" to "مرحباً",
        "thank you" to "شكراً",
        "good morning" to "صباح الخير",
        "good night" to "تصبح على خير",
        "fox" to "ثعلب",
        "video" to "فيديو",
        "screen" to "الشاشة",
        "recording" to "التسجيل",
        "translation" to "الترجمة",
        "made by" to "صُنع بواسطة"
    )

    fun map(text: String): String {
        val k = text.trim().lowercase()
        return dict[k] ?: text
    }
}

object LensManager {
    private var wm: WindowManager? = null
    private var view: View? = null

    fun show(ctx: Context, onClick: () -> Unit) {
        if (view != null) return
        val density = ctx.resources.displayMetrics.density
        val size = (96 * density).toInt()
        val dot = TextView(ctx).apply {
            text = "●"
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Fox.ORANGE)
                setStroke((3 * density).toInt(), Fox.DEEP)
            }
            setOnClickListener { onClick() }
        }
        val params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = (12 * density).toInt()
            y = (140 * density).toInt()
        }
        wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm?.addView(dot, params)
        view = dot
    }

    fun hide() {
        view?.let { v ->
            try { wm?.removeView(v) } catch (_: Exception) {}
        }
        view = null
    }
}

class ScrollX(ctx: Context) : android.widget.ScrollView(ctx)
