package com.jarvis.tablet.data.vision

import com.jarvis.tablet.domain.vision.VisionLanguageModel
import com.jarvis.tablet.domain.vision.VlmPrompt
import com.jarvis.tablet.domain.vision.VlmRequest
import com.jarvis.tablet.domain.vision.VlmResult
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Adapter for open-source vision-language models served behind an OpenAI-compatible API
 * (vLLM, Ollama, llama.cpp server: e.g. SmolVLM2 or Qwen2.5-VL). Sends every keyframe as its own image.
 */
class OpenAiCompatibleVlm(
    private val baseUrl: String,
    private val model: String,
    private val apiKey: String = "",
    private val timeoutMs: Int = 90_000,
    private val post: (url: String, headers: Map<String, String>, body: String, timeoutMs: Int) -> String = ::httpPost
) : VisionLanguageModel {

    override suspend fun describe(request: VlmRequest): VlmResult = withContext(Dispatchers.IO) {
        val headers = HashMap<String, String>()
        headers["Content-Type"] = "application/json"
        if (apiKey.isNotBlank()) headers["Authorization"] = "Bearer $apiKey"
        val reply = post(baseUrl.trimEnd('/') + "/chat/completions", headers, buildPayload(model, request), timeoutMs)
        VlmResult(parseReply(reply), model)
    }

    companion object {
        fun buildPayload(model: String, request: VlmRequest): String {
            val content = JSONArray().put(JSONObject().put("type", "text").put("text", VlmPrompt.build(request)))
            for (f in request.frames) {
                val uri = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(f.jpeg)
                content.put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", uri)))
            }
            return JSONObject().put("model", model).put("temperature", 0.2).put("max_tokens", 300)
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content))).toString()
        }

        fun parseReply(json: String): String {
            val choices = JSONObject(json).optJSONArray("choices")
            val text = choices?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty().trim()
            if (text.isEmpty()) throw IllegalStateException("empty reply from the vision server")
            return text
        }

        private fun httpPost(url: String, headers: Map<String, String>, body: String, timeoutMs: Int): String {
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.requestMethod = "POST"
                c.connectTimeout = 10_000
                c.readTimeout = timeoutMs
                c.doOutput = true
                headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
                c.outputStream.use { it.write(body.toByteArray()) }
                if (c.responseCode !in 200..299) throw IllegalStateException("vision server answered HTTP ${c.responseCode}")
                return c.inputStream.bufferedReader().readText()
            } finally {
                c.disconnect()
            }
        }
    }
}
