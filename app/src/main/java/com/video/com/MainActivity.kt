package com.video.com

import android.app.AlertDialog
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.annotation.RequiresApi
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

// --- Main Activity Class ---
class MainActivity : ComponentActivity() {

    companion object {
        const val TAG = "VideoFox"
        const val ACTION_TRANSLATE = "com.video.com.ACTION_TRANSLATE"
        const val EXTRA_TEXT = "extra_text"
    }

    // Inner Activity for playing videos locally within the app
    class MainFoxActivity : ComponentActivity() {
        private var exoPlayer: ExoPlayer? = null
        private lateinit var playerView: androidx.media3.ui.PlayerView
        private lateinit var floatingBtn: View
        private lateinit var windowManager: WindowManager
        private var overlayView: View? = null
        private var overlayParams: WindowManager.LayoutParams? = null
        private var isOverlayShowing = false

        override fun onCreate(savedInstanceState: android.os.Bundle?) {
            super.onCreate(savedInstanceState)
            setupUI()
            checkPermissions()
        }

        private fun setupUI() {
            // Programmatic UI Layout
            val rootLayout = FrameLayout(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                setBackgroundColor(android.R.color.black)
            }

            playerView = androidx.media3.ui.PlayerView(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                useController = true
            }
            rootLayout.addView(playerView)

            // Floating Button to trigger translation logic simulation
            floatingBtn = TextView(this).apply {
                text = "🦊 Translate"
                textSize = 18f
                setTextColor(android.R.color.white)
                background = android.graphics.drawable.ColorDrawable(android.graphics.Color.parseColor("#E91E63"))
                gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(300, 60).apply {
                    gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    bottomMargin = 100
                }
                setOnClickListener {
                    startTranslationSimulation()
                }
            }
            rootLayout.addView(floatingBtn)

            setContentView(rootLayout)

            // Load a sample video (Big Buck Bunny)
            try {
                val mediaItem = MediaItem.fromUri("https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4")
                exoPlayer = ExoPlayer.Builder(this).build().apply {
                    setMediaItem(mediaItem)
                    prepare()
                    playWhenReady = true
                }
                playerView.player = exoPlayer
            } catch (e: Exception) {
                Log.e(TAG, "Error loading video", e)
            }
        }

        private fun checkPermissions() {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Permission required for Floating Lens", Toast.LENGTH_LONG).show()
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
        }

        private fun startTranslationSimulation() {
            // Simulate capturing text from video subtitle area
            val simulatedText = "This is a demo of Video Fox translation engine."
            
            // Show overlay lens
            showFloatingLens(simulatedText)
        }

        private fun showFloatingLens(text: String) {
            if (overlayView != null) return

            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            
            overlayView = LayoutInflater.from(this).inflate(R.layout.temp_layout_placeholder, null) 
            // Since we can't use XML layouts strictly, we build it programmatically here for the overlay too
            val lensContainer = FrameLayout(this).apply {
                layoutParams = FrameLayout.LayoutParams(300, 150)
                setBackgroundColor(Color.parseColor("#CC000000"))
                elevation = 10f
            }

            val tvCaption = TextView(this).apply {
                text = text
                textColor = Color.WHITE
                textSize = 14f
                gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }
            lensContainer.addView(tvCaption)

            overlayParams = WindowManager.LayoutParams().apply {
                type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) 
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY 
                else 
                    WindowManager.LayoutParams.TYPE_PHONE
                
                format = PixelFormat.TRANSLUCENT
                flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or 
                         WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                         WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                
                gravity = Gravity.CENTER
                width = 300
                height = 150
                x = 0
                y = 0
            }

            windowManager.addView(lensContainer, overlayParams)
            this.overlayView = lensContainer
            this.isOverlayShowing = true

            // Start Service to process translation
            val intent = Intent(this, TranslationService::class.java).apply {
                putExtra(EXTRA_TEXT, text)
                action = ACTION_TRANSLATE
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            
            // Dismiss after 3 seconds for demo
            CoroutineScope(Dispatchers.Main).launch {
                delay(3000)
                dismissFloatingLens()
            }
        }

        private fun dismissFloatingLens() {
            if (overlayView != null && ::windowManager.isInitialized) {
                windowManager.removeView(overlayView)
                overlayView = null
                isOverlayShowing = false
            }
        }
        
        override fun onDestroy() {
            super.onDestroy()
            exoPlayer?.release()
            dismissFloatingLens()
        }
    }

    // --- Service for Translation Logic ---
    class TranslationService : Service() {
        private var job: Job? = null

        override fun onBind(intent: Intent?): IBinder? = null

        override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
            val text = intent?.getStringExtra(MainActivity.EXTRA_TEXT) ?: ""
            
            val notification = android.app.Notification.Builder(this, "fox_channel")
                .setContentTitle("Video Fox Translating...")
                .setContentText("Processing audio/subtitles...")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .build()
            
            startForeground(101, notification)

            job = CoroutineScope(Dispatchers.IO).launch {
                // Simulate processing time
                delay(2000)
                
                // Simulate translation result
                val translated = translateText(text, Locale.US, Locale.FRANCE) // Demo: EN -> FR
                
                // Save to internal storage (simulated)
                saveTranslationToFile(translated)
                
                stopSelf()
            }

            return START_NOT_STICKY
        }

        private fun translateText(text: String, source: Locale, target: Locale): String {
            // In a real app, call Google Translate API or ML Kit here
            return "[Translated] $text"
        }

        private fun saveTranslationToFile(content: String) {
            try {
                val fileName = "translation_${System.currentTimeMillis()}.txt"
                openFileOutput(fileName, Context.MODE_PRIVATE).use {
                    it.write(content.toByteArray())
                }
                Log.d(TAG, "Saved translation: $fileName")
            } catch (e: Exception) {
                Log.e(TAG, "Error saving file", e)
            }
        }

        override fun onDestroy() {
            job?.cancel()
            super.onDestroy()
        }
    }

    // --- Receiver for Broadcasts ---
    class OverlayReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_TRANSLATE) {
                val text = intent.getStringExtra(EXTRA_TEXT)
                Toast.makeText(context, "Lens Activated: $text", Toast.LENGTH_SHORT).show()
            }
        }
    }
}