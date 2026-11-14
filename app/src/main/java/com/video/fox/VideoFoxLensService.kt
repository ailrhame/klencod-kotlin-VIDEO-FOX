package com.video.fox

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import java.io.File

object FoxNotifications {
    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(
                "video_fox_capture", "Video Fox Capture", NotificationManager.IMPORTANCE_LOW
            ))
            manager.createNotificationChannel(NotificationChannel(
                "fox_processing", "Video Fox Processing", NotificationManager.IMPORTANCE_LOW
            ))
        }
    }
}

class ScreenCaptureService : Service() {
    private var projection: MediaProjection? = null
    private var recorder: MediaRecorder? = null
    private var display: VirtualDisplay? = null
    private var output: File? = null
    private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CAPTURE_START -> begin(intent)
            ACTION_CAPTURE_STOP -> stopCapture()
        }
        return START_NOT_STICKY
    }

    private fun makeNotification(): Notification {
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, ScreenCaptureService::class.java).setAction(ACTION_CAPTURE_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, "video_fox_capture")
            .setContentTitle("Video Fox")
            .setContentText("تسجيل الشاشة جارٍ")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "إيقاف", stopIntent)
            .build()
    }

    private fun begin(intent: Intent) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(22, makeNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(22, makeNotification())
        }
        try {
            val resultCode = intent.getIntExtra("resultCode", Activity.RESULT_CANCELED)
            val data = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra("projectionData", Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<Intent>("projectionData")
            } ?: error("بيانات إذن تسجيل الشاشة غير موجودة")

            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(resultCode, data)
                ?: error("تعذر بدء MediaProjection")

            val metrics = resources.displayMetrics
            val width = metrics.widthPixels.coerceAtLeast(2)
            val height = metrics.heightPixels.coerceAtLeast(2)
            val density = metrics.densityDpi
            val folder = getExternalFilesDir("recordings") ?: filesDir
            output = File(folder, "video_fox_${System.currentTimeMillis()}.mp4")

            recorder = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            recorder?.apply {
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setVideoSize(width - width % 2, height - height % 2)
                setVideoFrameRate(30)
                setVideoEncodingBitRate(6_000_000)
                setOutputFile(output!!.absolutePath)
                prepare()
                start()
            }
            display = projection?.createVirtualDisplay(
                "VideoFoxScreen", width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                recorder?.surface, null, null
            )
            projection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopCapture() }
            }, null)
        } catch (e: Exception) {
            stopCapture()
            FoxNotifications.createChannels(this)
            getSystemService(NotificationManager::class.java).notify(
                23, Notification.Builder(this, "video_fox_capture")
                    .setContentTitle("Video Fox")
                    .setContentText("فشل التسجيل: ${e.message}")
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .build()
            )
        }
    }

    private fun stopCapture() {
        if (stopping) return
        stopping = true
        try { display?.release() } catch (_: Exception) {}
        display = null
        try { recorder?.stop() } catch (_: Exception) {}
        try { recorder?.reset() } catch (_: Exception) {}
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }
}

class FoxOverlayService : Service() {
    private var manager: WindowManager? = null
    private var bubble: TextView? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        manager = getSystemService(WINDOW_SERVICE) as WindowManager
        bubble = TextView(this).apply {
            text = "🦊"
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(255, 132, 36))
            setPadding(16, 12, 16, 12)
            setOnClickListener {
                val stop = Intent(this@FoxOverlayService, ScreenCaptureService::class.java)
                    .setAction(ACTION_CAPTURE_STOP)
                startService(stop)
                stopSelf()
            }
        }
        val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 18
            y = 220
        }
        try { manager?.addView(bubble, params) } catch (_: Exception) { stopSelf() }
    }

    override fun onDestroy() {
        try { bubble?.let { manager?.removeView(it) } } catch (_: Exception) {}
        bubble = null
        super.onDestroy()
    }
}
