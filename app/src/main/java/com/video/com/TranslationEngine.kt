package com.video.com

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

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
    val endpoint: String,
    val model: String,
    val apiKey: String,
    val style: String = "طبيعية وسياقية",
    val glossary: Map<String, String> = emptyMap(),
    val characters: List<String> = emptyList()
)

class TranslationEngine {

    class TranslationException(message: String) : Exception(message)

    fun translateBatch(
        cues: MutableList<RawCue>,
        config: TranslationConfig,
        onProgress: (done: Int, total: Int) -> Unit
    ) {
        if (cues.isEmpty()) return
        if (config.endpoint.isBlank() || config.model.isBlank())
            throw TranslationException("محرك الترجمة السياقية غير مضبوط")

        val chunks = cues.chunked(20)
        var done = 0
        val total = cues.size

        chunks.forEachIndexed { index, chunk ->
            val before = if (index > 0) chunks[index - 1].takeLast(8).map { it.source } else emptyList()
            val after = if (index < chunks.size - 1) chunks[index + 1].take(4).map { it.source } else emptyList()
            val results = translateChunk(chunk, before, after, config)
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

    private fun translateChunk(
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