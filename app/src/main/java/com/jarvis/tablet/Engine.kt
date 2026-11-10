package com.jarvis.tablet

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.net.Uri
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.UUID
import kotlin.math.sqrt

class HttpException(val code: Int, val body: String = "") : Exception("HTTP $code")
class NoKeyException : Exception("no key")

private const val IDLE = "TAP JARVIS TO SPEAK"

class Engine(private val ctx: Context, private val scope: CoroutineScope) {

    private val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var callActive = false
    private var lastInteraction = 0L
    private val history = ArrayDeque<Pair<String, String>>()
    @Volatile private var busy = false
    @Volatile private var listeningNow = false
    private lateinit var stt: Stt

    fun start() {
        Keys.init(ctx)
        tts = TextToSpeech(ctx) { status ->
            if (status == TextToSpeech.SUCCESS) ttsReady = true
        }
        stt = Stt(
            ctx,
            { text -> listeningNow = false; scope.launch(Dispatchers.Default) { handle(text) } },
            { msg -> listeningNow = false; Bus.say("err", msg); Bus.state("", IDLE) }
        )
        Bus.onText = { t -> scope.launch(Dispatchers.Default) { handle(t) } }
        Bus.onToggle = { lang -> toggleListen(lang) }
        pushStatus()
        Bus.log("Jarvis engine online")
        if (!Keys.hasLlm()) Bus.say("err", "حط مفتاح Groq أو OpenAI في الخانة اللي فوق")
        scope.launch(Dispatchers.Default) { callLoop() }
    }

    private fun pushStatus() {
        Bus.emit(
            JSONObject().put("type", "status")
                .put("whatsapp", "ONLINE 🟢")
                .put("call", if (callActive) "LIVE CALL 🟢" else "")
                .put("live", callActive)
        )
    }

    // ------------------------------------------------------------ tap to talk
    private fun toggleListen(lang: String) {
        if (busy) { Bus.say("err", "استنى لحظة"); return }
        if (listeningNow) {
            stt.cancel()
            listeningNow = false
            Bus.state("", IDLE)
            return
        }
        if (!Keys.hasLlm()) { Bus.say("err", "حط مفتاح Groq أو OpenAI الأول"); return }
        tts?.stop()
        listeningNow = true
        Bus.state("listening", "LISTENING")
        stt.start(lang)
    }

    // ------------------------------------------------------------ text brain
    private suspend fun handle(text: String) {
        if (text.isBlank()) return
        if (busy) { Bus.say("err", "استنى لحظة"); return }
        busy = true
        try {
            Bus.state("thinking", "THINKING")
            Bus.say("u", text)
            val res = try {
                withContext(Dispatchers.IO) { llmCall(text) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: NoKeyException) {
                Bus.say("err", "حط مفتاح Groq أو OpenAI الأول"); return
            } catch (e: HttpException) {
                Bus.say("err", httpMessage(e)); return
            } catch (e: Exception) {
                Bus.say("err", "مشكلة في الاتصال بالنت (${e.javaClass.simpleName})"); return
            }
            act(res, text)
        } finally {
            busy = false
            Bus.state("", IDLE)
        }
    }

    private fun httpMessage(e: HttpException): String = when (e.code) {
        401, 403 -> "المفتاح غلط أو مش شغال (${e.code})"
        429 -> "وصلت للحد المجاني، جرّب بعد شوية"
        else -> "الـ API رجّع خطأ ${e.code}: " + e.body.take(140)
    }

    private suspend fun act(res: JSONObject, userText: String) {
        var reply = res.optString("reply").trim()
        when (res.optString("action", "none")) {
            "whatsapp" -> if (!doWhatsapp(res.optString("contact"), res.optString("message")))
                reply = "مش قادر ابعت الرسالة، اتأكد من الاسم"
            "wake_pc" -> if (!doWakePc()) reply = "عنوان الجهاز مش متظبط"
            "open_app" -> if (!doOpenApp(res.optString("app"))) reply = "مش لاقي التطبيق ده"
            "web_search" -> {
                val q = res.optString("query")
                val ok = q.isNotBlank() &&
                    launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + enc(q))))
                if (!ok) reply = "معرفتش افتح البحث"
            }
            "create_project" -> {
                val pr = res.optString("project_prompt")
                val ok = pr.isNotBlank() &&
                    launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://lovable.dev/?autosubmit=true#prompt=" + enc(pr))))
                if (!ok) reply = "معرفتش افتح صفحة المشروع"
            }
        }
        if (reply.isNotEmpty()) {
            history.addLast(userText to reply)
            while (history.size > 6) history.removeFirst()
            speak(reply)
        }
        lastInteraction = System.currentTimeMillis()
    }

    // ------------------------------------------------------------ prompts
    private fun installedApps(): List<Pair<String, String>> {
        val pm = ctx.packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(i, 0)
            .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
            .distinctBy { it.second }
    }

    private fun systemPrompt(audioMode: Boolean): String {
        val names = Config.contacts().keys().asSequence().joinToString("، ").ifEmpty { "(لا يوجد)" }
        val apps = installedApps().map { it.first }.take(200).joinToString("، ")
        val extraKeys = if (audioMode) "\"transcript\": \"...\", \"addressed\": true/false, " else ""
        val extraRules = if (audioMode) """
- transcript: اللي اتقال حرفيا. لو صمت أو ضجيج خليه "".
- addressed: true لو المتحدث نادى "جارفس" أو "جارفيس" أو Jarvis، أو لو in_conversation = true.
- لو addressed=false: action="none" و reply="".""" else ""
        return """أنت J.A.R.V.I.S مساعد شخصي ذكي على موبايل المستخدم. اسمه "${Keys.name}" وبتناديه بيه.
شخصيتك: رسمي، خفيف الدم، واثق. رد بنفس لغة المستخدم، وغالبا بالعامية المصرية.
افهم الحروف الغلط في الكلام المسموع.
ارجع JSON فقط بالشكل ده:
{$extraKeys"action": "none" | "whatsapp" | "wake_pc" | "open_app" | "web_search" | "create_project", "contact": "...", "message": "...", "app": "...", "query": "...", "project_prompt": "...", "reply": "..."}

القواعد:$extraRules
- action="whatsapp": رسالة واتساب. contact لازم يكون واحد من الأسماء دي بالظبط: $names. message نص الرسالة فقط.
- action="wake_pc": تشغيل اللابتوب أو الكمبيوتر.
- action="open_app": فتح تطبيق. app لازم يكون اسم واحد بالظبط من التطبيقات المثبتة دي: $apps
- action="web_search": بحث على الإنترنت. query نص البحث.
- action="create_project": المستخدم عايز يعمل مشروع أو موقع أو تطبيق. project_prompt وصف واضح ومفصل بالإنجليزي للي هيتبني.
- غير كده action="none" و reply إجابة قصيرة جدا (جملة أو اتنين) من غير رموز أو تنسيق، لأنها هتتقال بصوت.
- لو action مش none، reply تأكيد قصير زي "تمام، فتحت يوتيوب"."""
    }

    // ------------------------------------------------------------ LLM calls
    private fun postJson(url: String, headers: Map<String, String>, body: JSONObject): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 15000
            conn.readTimeout = 40000
            conn.setRequestProperty("Content-Type", "application/json")
            for ((k, v) in headers) conn.setRequestProperty(k, v)
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            if (code != 200) {
                val err = try { conn.errorStream?.bufferedReader()?.readText() ?: "" } catch (_: Exception) { "" }
                throw HttpException(code, err)
            }
            return conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    }

    private fun parseLoose(t: String): JSONObject {
        val s = t.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        return try { JSONObject(s) } catch (_: Exception) { JSONObject().put("reply", s).put("action", "none") }
    }

    private fun llmCall(text: String): JSONObject {
        val sys = systemPrompt(false)
        return when {
            Keys.groq.isNotBlank() -> openAiChat(
                "https://api.groq.com/openai/v1/chat/completions", Keys.groq,
                Config.list("groq_models").ifEmpty { listOf("llama-3.3-70b-versatile") }, sys, text
            )
            Keys.openai.isNotBlank() -> openAiChat(
                "https://api.openai.com/v1/chat/completions", Keys.openai,
                Config.list("openai_models").ifEmpty { listOf("gpt-4o") }, sys, text
            )
            Keys.gemini.isNotBlank() -> geminiGenerate(null, text, true)
            else -> throw NoKeyException()
        }
    }

    private fun openAiChat(url: String, key: String, models: List<String>, sys: String, user: String): JSONObject {
        var last: HttpException? = null
        for (m in models) {
            try {
                val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", sys))
                for ((u, r) in history.toList().takeLast(4)) {
                    msgs.put(JSONObject().put("role", "user").put("content", u))
                    msgs.put(JSONObject().put("role", "assistant").put("content", r))
                }
                msgs.put(JSONObject().put("role", "user").put("content", user))
                val body = JSONObject().put("model", m)
                    .put("response_format", JSONObject().put("type", "json_object"))
                    .put("messages", msgs)
                    .put("temperature", 0.4)
                val txt = postJson(url, mapOf("Authorization" to "Bearer $key"), body)
                val content = JSONObject(txt).getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").getString("content")
                return parseLoose(content)
            } catch (e: HttpException) {
                last = e
                if (e.code != 400 && e.code != 404) throw e   // try next model only on model errors
            }
        }
        throw last ?: Exception("no model")
    }

    private fun geminiGenerate(wav: ByteArray?, typed: String?, inConv: Boolean): JSONObject {
        val hist = history.toList().takeLast(4).joinToString("\n") { "المستخدم: ${it.first}\nجارفس: ${it.second}" }
        val prompt = "in_conversation = $inConv\nآخر الحوار:\n${hist.ifEmpty { "(لا يوجد)" }}"
        val parts = JSONArray().put(JSONObject().put("text", prompt))
        if (wav != null) {
            parts.put(
                JSONObject().put(
                    "inlineData",
                    JSONObject().put("mimeType", "audio/wav")
                        .put("data", Base64.encodeToString(wav, Base64.NO_WRAP))
                )
            )
        }
        if (typed != null) parts.put(JSONObject().put("text", "المستخدم: $typed"))
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt(wav != null)))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
            .put("generationConfig", JSONObject().put("responseMimeType", "application/json").put("temperature", 0.4))
        val txt = postJson(
            "https://generativelanguage.googleapis.com/v1beta/models/${Config.model()}:generateContent",
            mapOf("x-goog-api-key" to Keys.gemini), body
        )
        val out = JSONObject(txt).getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
        return parseLoose(out)
    }

    // ------------------------------------------------------------ call mode (optional, needs Gemini key)
    @Suppress("DEPRECATION")
    private suspend fun callLoop() {
        while (scope.isActive) {
            val inCall = audio.mode == AudioManager.MODE_IN_COMMUNICATION
            if (inCall != callActive) {
                callActive = inCall
                history.clear()
                lastInteraction = 0
                Bus.log(if (inCall) "CALL CONNECTED" else "CALL ENDED")
                pushStatus()
                if (inCall) {
                    try { audio.isSpeakerphoneOn = true } catch (_: Exception) {}
                    speak("أهلا، جارفس معاك")
                }
            }
            val wanted = callActive || Config.alwaysListen()
            if (!wanted || Keys.gemini.isBlank() || busy || listeningNow) { delay(700); continue }

            val pcm = recordChunk()
            if (pcm == null) { delay(1000); continue }
            if (rms(pcm) < Config.silenceRms()) continue
            handleAudio(toWav(pcm))
        }
    }

    private suspend fun handleAudio(wav: ByteArray) {
        if (busy) return
        val inConv = System.currentTimeMillis() - lastInteraction < 20_000
        val res = try {
            withContext(Dispatchers.IO) { geminiGenerate(wav, null, inConv) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpException) {
            Bus.say("err", httpMessage(e))
            if (e.code == 429) delay(30_000)
            return
        } catch (e: Exception) {
            return
        }
        val transcript = res.optString("transcript").trim()
        if (transcript.isEmpty() || !res.optBoolean("addressed")) return
        busy = true
        try {
            Bus.state("thinking", "THINKING")
            Bus.say("u", transcript)
            act(res, transcript)
        } finally {
            busy = false
            Bus.state("", IDLE)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun recordChunk(): ShortArray? = withContext(Dispatchers.IO) {
        val rate = 16000
        val total = rate * Config.chunkSeconds()
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return@withContext null
        val rec = try {
            AudioRecord(Config.audioSource(), rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 4)
        } catch (e: Exception) {
            return@withContext null
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return@withContext null }
        val buf = ShortArray(total)
        var off = 0
        try {
            rec.startRecording()
            while (off < total) {
                val n = rec.read(buf, off, total - off)
                if (n <= 0) break
                off += n
            }
        } finally {
            try { rec.stop() } catch (_: Exception) {}
            rec.release()
        }
        if (off < total / 2) null else buf.copyOf(off)
    }

    private fun rms(pcm: ShortArray): Double {
        if (pcm.isEmpty()) return 0.0
        var sum = 0.0
        for (s in pcm) sum += s.toDouble() * s.toDouble()
        return sqrt(sum / pcm.size)
    }

    private fun toWav(pcm: ShortArray): ByteArray {
        val dataLen = pcm.size * 2
        val bb = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray()).putInt(36 + dataLen).put("WAVE".toByteArray())
        bb.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(16000).putInt(16000 * 2).putShort(2).putShort(16)
        bb.put("data".toByteArray()).putInt(dataLen)
        for (s in pcm) bb.putShort(s)
        return bb.array()
    }

    // ------------------------------------------------------------ actions
    private fun enc(t: String) = URLEncoder.encode(t, "UTF-8").replace("+", "%20")

    private fun launch(intent: Intent): Boolean {
        val a = JarvisAccessibility.instance
        if (a != null) return a.launch(intent)
        return try {
            ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: Exception) {
            Bus.say("err", "فعّل خدمة Jarvis من إمكانية الوصول")
            false
        }
    }

    private fun doOpenApp(name: String): Boolean {
        if (name.isBlank()) return false
        val apps = installedApps()
        val hit = apps.firstOrNull { it.first.equals(name, true) }
            ?: apps.firstOrNull { it.first.contains(name, true) || name.contains(it.first, true) }
            ?: return false
        val i = ctx.packageManager.getLaunchIntentForPackage(hit.second) ?: return false
        Bus.log("OPEN APP -> ${hit.first}")
        return launch(i)
    }

    private fun normalizeNumber(raw: String): String {
        var d = raw.filter { it.isDigit() }
        if (d.startsWith("00")) d = d.substring(2)
        if (d.startsWith("0")) d = "20" + d.substring(1)
        return d
    }

    private fun doWhatsapp(contact: String, message: String): Boolean {
        val number = Config.contacts().optString(contact, "")
        if (number.isEmpty() || message.isBlank()) return false
        val url = "https://wa.me/${normalizeNumber(number)}?text=${enc(message)}"
        Bus.log("WHATSAPP -> $contact: $message")
        val a = JarvisAccessibility.instance
        if (a != null) return a.openWhatsApp(url)
        // بدون خدمة إمكانية الوصول: بيفتح المحادثة والرسالة جاهزة وانت تضغط إرسال
        return launch(Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage("com.whatsapp"))
    }

    private suspend fun doWakePc(): Boolean = withContext(Dispatchers.IO) {
        try {
            val mac = Config.pcMac().replace(":", "").replace("-", "")
            val macBytes = ByteArray(6) { mac.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
            val packet = ByteArray(6 + 16 * 6)
            for (i in 0 until 6) packet[i] = 0xFF.toByte()
            for (i in 0 until 16) System.arraycopy(macBytes, 0, packet, 6 + i * 6, 6)
            DatagramSocket().use { s ->
                s.broadcast = true
                s.send(DatagramPacket(packet, packet.size, InetAddress.getByName(Config.broadcastIp()), 9))
            }
            Bus.log("WOL magic packet sent")
            true
        } catch (e: Exception) {
            false
        }
    }

    // ------------------------------------------------------------ tts
    private suspend fun speak(text: String) {
        Bus.say("j", text)
        Bus.state("speaking", "SPEAKING")
        val engine = tts
        if (!ttsReady || engine == null) return

        val arabic = text.any { it in '\u0600'..'\u06FF' }
        engine.language = if (arabic) Locale("ar") else Locale.US

        val done = CompletableDeferred<Unit>()
        val id = UUID.randomUUID().toString()
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { done.complete(Unit) }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { done.complete(Unit) }
        })
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        withTimeoutOrNull(30_000) { done.await() }
        delay(400)
    }
}
