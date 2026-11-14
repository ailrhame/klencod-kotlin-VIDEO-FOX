package com.video.fox

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject

private data class SubtitleCue(val startMs: Long, val endMs: Long, val text: String)

object TranslationPipeline {
    private val worker = Executors.newSingleThreadExecutor()

    fun start(context: Context, source: File) {
        AppContextHolder.context = context.applicationContext
        worker.execute {
            try {
                update(context, "تحليل الفيديو واستخراج النصوص…")
                val cues = ArrayList<SubtitleCue>()
                cues += VideoOcr.readCues(source) { message -> update(context, message) }

                update(context, "استخراج المسار الصوتي…")
                val audio = File(context.cacheDir, "fox_audio_${System.currentTimeMillis()}.m4a")
                val hasAudio = runCatching { AudioTrackExtractor.extract(source, audio) }.getOrDefault(false)

                if (hasAudio) {
                    val prefs = context.getSharedPreferences("video_fox_settings", Context.MODE_PRIVATE)
                    val asrUrl = prefs.getString("asrUrl", "").orEmpty()
                    val asrKey = prefs.getString("asrKey", prefs.getString("apiKey", "")).orEmpty()
                    if (asrUrl.isNotBlank()) {
                        update(context, "تحويل الكلام المنطوق إلى نص…")
                        val spokenCues = AsrClient.transcribe(asrUrl, asrKey, audio)
                        if (spokenCues.isNotEmpty()) {
                            update(context, "ترجمة الحوار إلى العربية…")
                            spokenCues.forEach { cue ->
                                val translated = QwenClient.translate(context, cue.text)
                                if (translated.isNotBlank()) cues += cue.copy(text = translated)
                            }
                        }
                    } else {
                        update(context, "لم يُضبط رابط ASR؛ تمت معالجة النصوص الظاهرة فقط.")
                    }
                } else {
                    update(context, "لم يُعثر على مسار صوتي داخل الفيديو.")
                }

                update(context, "كتابة ملف الترجمة…")
                val output = File(source.parentFile, source.nameWithoutExtension + ".ar.srt")
                SrtWriter.write(output, cues.sortedBy { it.startMs })
                audio.delete()
                update(context, "اكتملت المعالجة: ${output.name}")
            } catch (e: Exception) {
                update(context, "تعذرت المعالجة: ${e.message ?: "خطأ غير معروف"}")
            }
        }
    }

    private fun update(context: Context, message: String) {
        Handler(Looper.getMainLooper()).post {
            val manager = context.getSystemService(NotificationManager::class.java)
            val builder = Notification.Builder(context, "fox_processing")
            manager.notify(44, builder.setContentTitle("Video Fox")
                .setContentText(message)
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setOngoing(false).build())
        }
    }
}

private object VideoOcr {
    fun readCues(video: File, progress: (String) -> Unit): List<SubtitleCue> {
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(video.absolutePath)
        val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull() ?: 0L
        if (duration <= 0L) {
            retriever.release()
            return emptyList()
        }

        val recognizers = listOf(
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS),
            TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()),
            TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build()),
            TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        )
        val results = ArrayList<SubtitleCue>()
        val step = 2500L
        try {
            var time = 0L
            while (time < duration) {
                progress("فحص الإطار عند ${time / 1000} ثانية…")
                val bitmap = retriever.getFrameAtTime(
                    time * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                )
                if (bitmap != null) {
                    val found = recognize(bitmap, recognizers)
                    if (found.isNotBlank() && !isMostlyArabic(found)) {
                        results += SubtitleCue(time, (time + step).coerceAtMost(duration), found)
                    }
                    bitmap.recycle()
                }
                time += step
            }
        } finally {
            retriever.release()
            recognizers.forEach { runCatching { it.close() } }
        }

        val prefsContext = AppContextHolder.context
        return results.map { cue ->
            val translated = if (prefsContext != null)
                runCatching { QwenClient.translate(prefsContext, cue.text) }.getOrDefault(cue.text)
            else cue.text
            cue.copy(text = translated)
        }.distinctBy { it.startMs to it.text }
    }

    private fun recognize(bitmap: Bitmap, recognizers: List<com.google.mlkit.vision.text.TextRecognizer>): String {
        for (recognizer in recognizers) {
            val latch = CountDownLatch(1)
            var result = ""
            runCatching {
                recognizer.process(InputImage.fromBitmap(bitmap, 0))
                    .addOnSuccessListener { text: Text -> result = text.text.trim(); latch.countDown() }
                    .addOnFailureListener { latch.countDown() }
            }.onFailure { latch.countDown() }
            latch.await(12, TimeUnit.SECONDS)
            if (result.isNotBlank()) return result
        }
        return ""
    }

    private fun isMostlyArabic(text: String): Boolean {
        val letters = text.count { it.isLetter() }.coerceAtLeast(1)
        val arabic = text.count { it in '\u0600'..'\u06FF' }
        return arabic.toFloat() / letters > 0.55f
    }
}

private object AppContextHolder {
    // Context is assigned by the app process through the first pipeline request.
    var context: Context? = null
}

private object QwenClient {
    fun translate(context: Context, text: String): String {
        val prefs = context.getSharedPreferences("video_fox_settings", Context.MODE_PRIVATE)
        val endpoint = prefs.getString("apiUrl", "").orEmpty()
        val model = prefs.getString("model", "qwen-plus").orEmpty()
        val key = prefs.getString("apiKey", "").orEmpty()
        if (endpoint.isBlank() || key.isBlank()) return text

        val payload = JSONObject().apply {
            put("model", model)
            put("temperature", 0.2)
            put("messages", org.json.JSONArray().put(JSONObject().put("role", "system")
                .put("content", "ترجم النص إلى العربية الفصحى ترجمة دقيقة وطبيعية. حافظ على الأسماء والمعنى والنبرة. أعد الترجمة فقط دون شرح."))
                .put(JSONObject().put("role", "user").put("content", text)))
        }
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 20000
            connection.readTimeout = 60000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Authorization", "Bearer $key")
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream.bufferedReader().use { it.readText() }
            if (code !in 200..299) error("Qwen HTTP $code: ${body.take(250)}")
            return JSONObject(body).getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content").trim()
        } finally {
            connection.disconnect()
        }
    }
}

private object AsrClient {
    fun transcribe(endpoint: String, key: String, audio: File): List<SubtitleCue> {
        val boundary = "----VideoFox${System.currentTimeMillis()}"
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 30000
            connection.readTimeout = 120000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            if (key.isNotBlank()) connection.setRequestProperty("Authorization", "Bearer $key")
            connection.outputStream.use { out ->
                fun line(value: String) = out.write((value + "\r\n").toByteArray())
                line("--$boundary")
                line("Content-Disposition: form-data; name=\"model\"")
                line("")
                line("whisper-1")
                line("--$boundary")
                line("Content-Disposition: form-data; name=\"response_format\"")
                line("")
                line("verbose_json")
                line("--$boundary")
                line("Content-Disposition: form-data; name=\"file\"; filename=\"audio.m4a\"")
                line("Content-Type: audio/mp4")
                line("")
                audio.inputStream().use { it.copyTo(out) }
                line("")
                line("--$boundary--")
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream.bufferedReader().use { it.readText() }
            if (code !in 200..299) error("ASR HTTP $code: ${body.take(250)}")
            val json = JSONObject(body)
            val segments = json.optJSONArray("segments")
            if (segments != null && segments.length() > 0) {
                val cues = ArrayList<SubtitleCue>()
                for (i in 0 until segments.length()) {
                    val item = segments.optJSONObject(i) ?: continue
                    val text = item.optString("text", "").trim()
                    if (text.isBlank()) continue
                    val start = (item.optDouble("start", 0.0) * 1000.0).toLong().coerceAtLeast(0)
                    val end = (item.optDouble("end", item.optDouble("start", 0.0) + 3.0) * 1000.0).toLong()
                    cues += SubtitleCue(start, end.coerceAtLeast(start + 500), text)
                }
                return cues
            }
            val plain = json.optString("text", json.optString("transcript", "")).trim()
            return if (plain.isBlank()) emptyList() else listOf(SubtitleCue(0L, 5000L, plain))
        } finally {
            connection.disconnect()
        }
    }
}

private object AudioTrackExtractor {
    fun extract(input: File, output: File): Boolean {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            extractor.setDataSource(input.absolutePath)
            var audioTrack = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val candidate = extractor.getTrackFormat(i)
                if (candidate.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    audioTrack = i
                    format = candidate
                    break
                }
            }
            if (audioTrack < 0 || format == null) return false
            extractor.selectTrack(audioTrack)
            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val outputTrack = muxer.addTrack(format)
            muxer.start()
            val buffer = java.nio.ByteBuffer.allocate(1024 * 1024)
            val info = android.media.MediaCodec.BufferInfo()
            while (true) {
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                info.offset = 0
                info.size = size
                info.presentationTimeUs = extractor.sampleTime
                info.flags = extractor.sampleFlags
                muxer.writeSampleData(outputTrack, buffer, info)
                extractor.advance()
            }
            muxer.stop()
            return output.length() > 0
        } finally {
            runCatching { muxer?.release() }
            extractor.release()
        }
    }
}

private object SrtWriter {
    fun write(file: File, cues: List<SubtitleCue>) {
        FileOutputStream(file).bufferedWriter(Charsets.UTF_8).use { writer ->
            cues.forEachIndexed { index, cue ->
                writer.append((index + 1).toString()).append('\n')
                writer.append(timestamp(cue.startMs)).append(" --> ")
                    .append(timestamp(cue.endMs)).append('\n')
                writer.append(cue.text.replace("-->", "→")).append("\n\n")
            }
        }
    }

    private fun timestamp(ms: Long): String {
        val safe = ms.coerceAtLeast(0)
        val hours = safe / 3_600_000
        val minutes = (safe % 3_600_000) / 60_000
        val seconds = (safe % 60_000) / 1000
        val millis = safe % 1000
        return String.format(Locale.US, "%02d:%02d:%02d,%03d", hours, minutes, seconds, millis)
    }
}
