package com.video.fox

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

data class RawCue(
    val startMs: Long,
    var endMs: Long,
    var source: String,
    var translated: String = "",
    var speaker: String = "",
    var emotion: String = ""
) {
    fun toJson(): JSONObject = JSONObject()
        .put("start", startMs).put("end", endMs).put("source", source)
        .put("translated", translated).put("speaker", speaker).put("emotion", emotion)

    companion object {
        fun fromJson(o: JSONObject) = RawCue(
            startMs = o.optLong("start"), endMs = o.optLong("end"),
            source = o.optString("source"), translated = o.optString("translated"),
            speaker = o.optString("speaker"), emotion = o.optString("emotion")
        )
    }
}

data class TranslationConfig(
    val endpoint: String = "",
    val model: String = "",
    val apiKey: String = "",
    val style: String = "طبيعية وسياقية",
    val glossary: Map<String, String> = emptyMap(),
    val characters: List<String> = emptyList(),
    val mode: String = "auto"
)

class TranslationEngine(@Suppress("UNUSED_PARAMETER") context: Context) {
    class TranslationException(message: String) : Exception(message)

    private val translatorCache = ConcurrentHashMap<String, Translator>()
    private val languageIdentifierDelegate = lazy { LanguageIdentification.getClient() }
    private val languageIdentifier by languageIdentifierDelegate

    fun close() {
        runCatching { if (languageIdentifierDelegate.isInitialized()) languageIdentifier.close() }
        translatorCache.values.forEach { runCatching { it.close() } }
        translatorCache.clear()
    }

    fun translateBatch(
        cues: MutableList<RawCue>,
        config: TranslationConfig,
        onProgress: (done: Int, total: Int) -> Unit
    ) {
        if (cues.isEmpty()) return
        val effectiveMode = when (config.mode.lowercase(Locale.ROOT)) {
            "offline" -> "offline"
            "openai" -> "openai"
            else -> if (config.endpoint.isNotBlank() && config.model.isNotBlank()) "openai" else "offline"
        }
        when (effectiveMode) {
            "openai" -> translateViaOpenAI(cues, config, onProgress)
            else -> translateViaMLKit(cues, config, onProgress)
        }
    }

    private fun translateViaMLKit(
        cues: MutableList<RawCue>,
        config: TranslationConfig,
        onProgress: (Int, Int) -> Unit
    ) {
        val total = cues.size
        var done = 0
        cues.forEach { cue ->
            if (cue.translated.isBlank()) {
                try {
                    val translated = translateSingleWithMlKit(cue.source)
                    cue.translated = applyGlossary(translated, config.glossary)
                    cue.emotion = inferEmotion(cue.source)
                } catch (e: Exception) {
                    cue.translated = ""
                    cue.emotion = ""
                }
            }
            done++
            onProgress(done, total)
        }
    }

    private fun translateSingleWithMlKit(sourceText: String): String {
        val clean = sourceText.trim()
        if (clean.isBlank()) return ""
        if (clean.any { it in '\u0600'..'\u06FF' } && clean.none { it in 'A'..'Z' || it in 'a'..'z' }) {
            return clean
        }

        val detected = runCatching {
            Tasks.await(languageIdentifier.identifyLanguage(clean.take(400)))
        }.getOrDefault("und").lowercase(Locale.ROOT).substringBefore('-')

        val sourceLang = when (detected) {
            "ja" -> TranslateLanguage.JAPANESE
            "ko" -> TranslateLanguage.KOREAN
            "zh" -> TranslateLanguage.CHINESE
            "ar" -> return clean
            "en" -> TranslateLanguage.ENGLISH
            else -> TranslateLanguage.ENGLISH
        }

        val translator = translatorCache.getOrPut(sourceLang) {
            Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(sourceLang)
                    .setTargetLanguage(TranslateLanguage.ARABIC)
                    .build()
            )
        }

        val condition = DownloadConditions.Builder().build()
        Tasks.await(translator.downloadModelIfNeeded(condition))

        val chunks = chunkText(clean, 240)
        val translated = chunks.mapNotNull { chunk ->
            runCatching { Tasks.await(translator.translate(chunk)) }.getOrNull()
        }.filter { it.isNotBlank() }.joinToString(" ")

        return cleanArabic(translated.ifBlank { clean })
    }

    private fun chunkText(text: String, maxChars: Int): List<String> {
        if (text.length <= maxChars) return listOf(text)
        val sentences = text.split(Regex("(?<=[.!?؟…。！？])\\s*")).filter { it.isNotBlank() }
        if (sentences.size <= 1) {
            val words = text.split(Regex("\\s+"))
            val chunks = mutableListOf<String>()
            val current = StringBuilder()
            words.forEach { word ->
                if (current.length + word.length + 1 > maxChars && current.isNotEmpty()) {
                    chunks.add(current.toString().trim()); current.clear()
                }
                current.append(word).append(' ')
            }
            if (current.isNotEmpty()) chunks.add(current.toString().trim())
            return chunks.ifEmpty { listOf(text) }
        }
        val chunks = mutableListOf<String>()
        val current = StringBuilder()
        sentences.forEach { sentence ->
            if (current.length + sentence.length + 1 > maxChars && current.isNotEmpty()) {
                chunks.add(current.toString().trim()); current.clear()
            }
            current.append(sentence).append(' ')
        }
        if (current.isNotEmpty()) chunks.add(current.toString().trim())
        return chunks
    }

    private fun cleanArabic(value: String): String = value
        .replace('\u0640', ' ')
        .replace(Regex("\\s+"), " ")
        .replace('?', '؟')
        .trim()

    private fun applyGlossary(text: String, glossary: Map<String, String>): String {
        if (glossary.isEmpty()) return text
        var result = text
        glossary.entries.sortedByDescending { it.key.length }.forEach { (s, t) ->
            result = result.replace(s, t, ignoreCase = true)
        }
        return result
    }

    private fun inferEmotion(source: String): String {
        val lower = source.lowercase(Locale.ROOT)
        return when {
            lower.count { it == '!' } >= 2 -> "انفعال"
            lower.contains("...") || lower.contains("…") -> "تردد"
            lower.contains('?') && lower.contains('!') -> "صدمة"
            lower.contains('?') -> "تساؤل"
            lower.contains('!') -> "تأكيد"
            else -> ""
        }
    }

    private fun translateViaOpenAI(
        cues: MutableList<RawCue>,
        config: TranslationConfig,
        onProgress: (Int, Int) -> Unit
    ) {
        if (config.endpoint.isBlank() || config.model.isBlank())
            throw TranslationException("محرك الترجمة السياقية غير مضبوط")

        val chunks = cues.chunked(20)
        var done = 0
        val total = cues.size

        chunks.forEachIndexed { index, chunk ->
            val before = if (index > 0) chunks[index - 1].takeLast(8).map { it.source } else emptyList()
            val after = if (index < chunks.size - 1) chunks[index + 1].take(4).map { it.source } else emptyList()
            val results = translateChunkOpenAI(chunk, before, after, config)
            results.forEachIndexed { i, r ->
                if (i < chunk.size) {
                    chunk[i].translated = r.translated
                    chunk[i].speaker = r.speaker
                    chunk[i].emotion = r.emotion
                }
            }
            done += chunk.size
            onProgress(done, total)
        }
    }

    private data class ChunkResult(val translated: String, val speaker: String, val emotion: String)

    private fun translateChunkOpenAI(
        chunk: List<RawCue>,
        before: List<String>,
        after: List<String>,
        config: TranslationConfig
    ): List<ChunkResult> {
        val timeFmt = { ms: Long -> String.format(Locale.US, "%02d:%02d", ms / 60000, (ms / 1000) % 60) }
        val lines = chunk.mapIndexed { i, cue -> "${i + 1}|${timeFmt(cue.startMs)}|${cue.source}" }.joinToString("\n")

        val ctxBefore = if (before.isNotEmpty()) "السياق السابق (للفهم فقط، لا تترجمه):\n" + before.joinToString("\n") else ""
        val ctxAfter = if (after.isNotEmpty()) "السياق التالي (للفهم فقط، لا تترجمه):\n" + after.joinToString("\n") else ""
        val glossaryText = if (config.glossary.isNotEmpty())
            "المعجم:\n" + config.glossary.entries.take(150).joinToString("\n") { "${it.key} = ${it.value}" } else ""
        val charsText = if (config.characters.isNotEmpty()) "الشخصيات المعروفة:\n" + config.characters.joinToString("، ") else ""

        val systemPrompt = """
أنت مترجم حوارات سينمائية إلى العربية بمستوى مخرج أفلام. مهمتك ليست الترجمة الحرفية، بل نقل المعنى والنبرة والنية إلى عربية حية طبيعية.
قواعدك الصارمة:
1. اقرأ السياق الكامل قبل الترجمة. السياق أهم من الكلمة المفردة.
2. لا تترجم كلمة بكلمة. ترجم ما يعنيه المتحدث فعلاً.
3. التعابير الاصطلاحية والأمثال والسخرية والتهديدات تتحول إلى مكافئاتها العربية الطبيعية.
4. شخصية المتحدث تحدد أسلوبها. الطفل لا يتكلم كالجنرال.
5. حافظ على اتساق الأسماء والمصطلحات كما في المعجم.
6. لا تخترع أحداثاً أو معاني غير موجودة في النص الأصلي.
7. إذا كان نص OCR ناقصاً، استنتج فقط ما يسمح به السياق.
8. الأسلوب المطلوب: ${config.style}.
9. حدد المتحدث إن أمكن من السياق وإلا اتركه فارغاً.
10. حدد نبرة المقطع: هدوء، غضب، حزن، فرح، خوف، سخرية، رومانسي، توتر.
""".trimIndent()

        val userPrompt = """
$ctxBefore
$ctxAfter
$glossaryText
$charsText

المقاطع المطلوب ترجمتها (التنسيق: رقم|الوقت|النص الأصلي):
$lines

أعد ردك بصيغة JSON فقط دون أي شرح:
{"items":[{"id":1,"ar":"الترجمة","speaker":"اسم المتحدث أو فراغ","emotion":"النبرة"},{"id":2,"ar":"...","speaker":"...","emotion":"..."}]}
""".trimIndent()

        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", systemPrompt))
            .put(JSONObject().put("role", "user").put("content", userPrompt))
        val body = JSONObject()
            .put("model", config.model)
            .put("messages", messages)
            .put("temperature", 0.35)
            .put("response_format", JSONObject().put("type", "json_object"))

        var connection: HttpURLConnection? = null
        try {
            connection = (URL(config.endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; connectTimeout = 20000; readTimeout = 90000; doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                if (config.apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer ${config.apiKey}")
            }
            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { it.write(body.toString()) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
            if (code !in 200..299) throw TranslationException("HTTP $code")

            val content = JSONObject(response).optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("message")?.optString("content").orEmpty()
            val cleaned = content.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()

            val rootObj = runCatching { JSONObject(cleaned) }.getOrNull()
            val itemsArr = rootObj?.optJSONArray("items")
                ?: runCatching {
                    val s = cleaned.indexOf('['); val e = cleaned.lastIndexOf(']')
                    if (s >= 0 && e > s) JSONArray(cleaned.substring(s, e + 1)) else JSONArray()
                }.getOrDefault(JSONArray())

            val results = MutableList(chunk.size) { ChunkResult("", "", "") }
            for (i in 0 until itemsArr.length()) {
                val obj = itemsArr.optJSONObject(i) ?: continue
                val id = obj.optInt("id", i + 1) - 1
                if (id in results.indices) {
                    results[id] = ChunkResult(
                        translated = obj.optString("ar").trim(),
                        speaker = obj.optString("speaker").trim(),
                        emotion = obj.optString("emotion").trim()
                    )
                }
            }
            return results
        } catch (e: TranslationException) {
            throw e
        } catch (e: Exception) {
            throw TranslationException("فشل الاتصال بمحرك الترجمة: ${e.localizedMessage ?: "غير معروف"}")
        } finally {
            connection?.disconnect()
        }
    }
}