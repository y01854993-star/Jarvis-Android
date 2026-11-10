package com.jarvis.tablet

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.net.Uri
import android.provider.AlarmClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import java.text.SimpleDateFormat
import java.util.Date
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
    private var listenJob: Job? = null

    fun start() {
        Keys.init(ctx)
        tts = TextToSpeech(ctx) { status ->
            if (status == TextToSpeech.SUCCESS) ttsReady = true
        }
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
            listenJob?.cancel()
            listeningNow = false
            Bus.state("", IDLE)
            return
        }
        if (!Keys.hasLlm()) { Bus.say("err", "حط مفتاح Groq أو OpenAI الأول"); return }
        tts?.stop()
        listeningNow = true
        Bus.state("listening", "LISTENING")
        listenJob = scope.launch(Dispatchers.Default) {
            try {
                val pcm = recordUtterance()
                if (pcm == null) { Bus.say("err", "ما سمعتش حاجة، جرّب تاني"); return@launch }
                Bus.state("thinking", "THINKING")
                val text = try {
                    withContext(Dispatchers.IO) { transcribe(toWav(pcm), lang) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: NoKeyException) {
                    Bus.say("err", "حط مفتاح Groq أو OpenAI الأول"); return@launch
                } catch (e: HttpException) {
                    Bus.say("err", httpMessage(e)); return@launch
                } catch (e: Exception) {
                    Bus.say("err", "مشكلة في الاتصال بالنت (${e.javaClass.simpleName})"); return@launch
                }
                if (text.isBlank()) { Bus.say("err", "ما فهمتش الكلام، جرّب تاني"); return@launch }
                listeningNow = false
                handle(text)
            } finally {
                listeningNow = false
                if (!busy) Bus.state("", IDLE)
            }
        }
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
        val acts = ArrayList<JSONObject>()
        res.optJSONArray("actions")?.let { arr ->
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { acts.add(it) }
        }
        for ((i, a) in acts.withIndex()) {
            val err = runAction(a)
            if (err != null) { reply = err; break }
            if (i < acts.size - 1) delay(1500)
        }
        if (reply.isNotEmpty()) {
            history.addLast(userText to reply)
            while (history.size > 6) history.removeFirst()
            speak(reply)
        }
        lastInteraction = System.currentTimeMillis()
    }

    private fun view(url: String, fail: String): String? =
        if (launch(Intent(Intent.ACTION_VIEW, Uri.parse(url)))) null else fail

    private suspend fun runAction(a: JSONObject): String? = when (a.optString("type")) {
        "whatsapp" -> if (doWhatsapp(a.optString("contact"), a.optString("message"))) null
            else "مش قادر ابعت الرسالة، اتأكد من الاسم"
        "wake_pc" -> if (doWakePc()) null else "عنوان الجهاز مش متظبط"
        "open_app" -> if (doOpenApp(a.optString("app"))) null else "مش لاقي التطبيق ده"
        "web_search" -> a.optString("query").takeIf { it.isNotBlank() }
            ?.let { view("https://www.google.com/search?q=" + enc(it), "معرفتش افتح البحث") }
            ?: "مفيش حاجة أبحث عنها"
        "youtube_search" -> a.optString("query").takeIf { it.isNotBlank() }
            ?.let { view("https://www.youtube.com/results?search_query=" + enc(it), "معرفتش افتح يوتيوب") }
            ?: "مفيش حاجة أبحث عنها"
        "navigate" -> a.optString("place").takeIf { it.isNotBlank() }
            ?.let { view("geo:0,0?q=" + enc(it), "معرفتش افتح الخريطة") }
            ?: "مفيش مكان أروحله"
        "call" -> {
            val n = Config.contacts().optString(a.optString("contact"), "")
            if (n.isEmpty()) "مش لاقي الاسم ده في جهات الاتصال"
            else view("tel:" + normalizeNumber(n).let { "+$it" }, "معرفتش افتح الاتصال")
        }
        "alarm" -> {
            val i = Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, a.optInt("hour"))
                .putExtra(AlarmClock.EXTRA_MINUTES, a.optInt("minute"))
                .putExtra(AlarmClock.EXTRA_MESSAGE, a.optString("label", "Jarvis"))
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            if (launch(i)) null else "معرفتش اظبط المنبه"
        }
        "timer" -> {
            val i = Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, a.optInt("seconds"))
                .putExtra(AlarmClock.EXTRA_MESSAGE, a.optString("label", "Jarvis"))
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            if (launch(i)) null else "معرفتش اظبط المؤقت"
        }
        "create_project" -> a.optString("project_prompt").takeIf { it.isNotBlank() }
            ?.let { view("https://lovable.dev/?autosubmit=true#prompt=" + enc(it), "معرفتش افتح صفحة المشروع") }
            ?: "مفيش وصف للمشروع"
        "remember" -> { a.optString("fact").takeIf { it.isNotBlank() }?.let { Keys.addMemory(it) }; null }
        "forget_memory" -> { Keys.clearMemory(); null }
        else -> null
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
        val now = SimpleDateFormat("EEEE d MMMM yyyy, HH:mm", Locale.ENGLISH).format(Date())
        val mem = Keys.memory()
        val memBlock = if (mem.isEmpty()) "" else "حاجات تعرفها عن المستخدم:\n" + mem.joinToString("\n") { "- $it" }
        val extraKeys = if (audioMode) "\"transcript\": \"...\", \"addressed\": true/false, " else ""
        val extraRules = if (audioMode) """
- transcript: اللي اتقال حرفيا. لو صمت أو ضجيج خليه "".
- addressed: true لو المتحدث نادى "جارفس" أو "جارفيس" أو Jarvis، أو لو in_conversation = true.
- لو addressed=false: actions فاضية و reply="".""" else ""
        return """أنت J.A.R.V.I.S مساعد شخصي ذكي جدا على موبايل المستخدم. اسمه "${Keys.name}" وبتناديه بيه.
الوقت والتاريخ دلوقتي: $now.
$memBlock
شخصيتك: رسمي، خفيف الدم، واثق. رد بنفس لغة المستخدم (غالبا عامية مصرية). افهم الحروف الغلط في الكلام المسموع.
ارجع JSON فقط بالشكل ده:
{$extraKeys"reply": "...", "actions": []}
actions قائمة (ممكن تبقى فاضية أو فيها أكتر من خطوة بالترتيب). كل عنصر فيه "type" وحقوله:
- {"type":"open_app","app":"..."}: app اسم واحد بالظبط من التطبيقات المثبتة دي: $apps
- {"type":"web_search","query":"..."}
- {"type":"youtube_search","query":"..."}
- {"type":"navigate","place":"..."}
- {"type":"whatsapp","contact":"...","message":"..."}: contact واحد بالظبط من: $names
- {"type":"call","contact":"..."}: نفس الأسماء
- {"type":"alarm","hour":0-23,"minute":0-59,"label":"..."}
- {"type":"timer","seconds":رقم,"label":"..."}
- {"type":"create_project","project_prompt":"وصف مفصل بالإنجليزي للي هيتبني"}
- {"type":"wake_pc"}
- {"type":"remember","fact":"..."}: لو المستخدم قالك تفتكر حاجة عنه.
- {"type":"forget_memory"}

القواعد:$extraRules
- reply: جملة أو اتنين أو تلاتة كحد أقصى، من غير رموز أو تنسيق لأنها هتتقال بصوت. لو في actions خلي reply تأكيد قصير زي "تمام، فتحت يوتيوب".
- لو سؤال معرفة: جاوب بدقة ومباشرة. لو مش متأكد قول إنك مش متأكد وماتألفش. لو محتاج معلومة حديثة (أخبار، أسعار، طقس، نتايج) اعمل web_search وقول إنك فتحت البحث.
- لو الطلب مش واضح اسأل سؤال واحد قصير في reply وخلي actions فاضية.
- ماتبعتش رسالة ولا تتصل إلا لو المستخدم طلب ده صراحة."""
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

    // ------------------------------------------------------------ speech to text (Whisper)
    @SuppressLint("MissingPermission")
    private suspend fun recordUtterance(): ShortArray? = withContext(Dispatchers.IO) {
        val rate = 16000
        val frame = 1600   // 100ms
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return@withContext null
        val rec = try {
            AudioRecord(Config.audioSource(), rate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, frame * 2) * 2)
        } catch (e: Exception) {
            return@withContext null
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return@withContext null }

        val out = ArrayList<ShortArray>()
        var started = false
        var silent = 0
        var frames = 0
        var noise = 0.0
        var threshold = Config.silenceRms()
        try {
            rec.startRecording()
            val buf = ShortArray(frame)
            while (frames < 150 && isActive) {          // max 15s
                var got = 0
                while (got < frame) {
                    val n = rec.read(buf, got, frame - got)
                    if (n <= 0) break
                    got += n
                }
                if (got < frame) break
                frames++
                out.add(buf.copyOf())
                val level = rms(buf)
                if (frames <= 3) {                       // measure room noise
                    noise = maxOf(noise, level)
                    if (frames == 3) threshold = maxOf(Config.silenceRms(), noise * 2.5)
                    continue
                }
                if (level > threshold) { started = true; silent = 0 }
                else if (started) { silent++; if (silent >= 13) break }   // 1.3s of silence = done
                else if (frames > 70) break                                // nobody spoke for 7s
            }
        } finally {
            try { rec.stop() } catch (_: Exception) {}
            rec.release()
        }
        if (!started) return@withContext null
        val total = out.sumOf { it.size }
        val res = ShortArray(total)
        var o = 0
        for (f in out) { System.arraycopy(f, 0, res, o, f.size); o += f.size }
        res
    }

    private fun transcribe(wav: ByteArray, lang: String): String {
        val code = if (lang.startsWith("en")) "en" else "ar"
        return when {
            Keys.groq.isNotBlank() -> whisper(
                "https://api.groq.com/openai/v1/audio/transcriptions", Keys.groq,
                listOf("whisper-large-v3-turbo", "whisper-large-v3"), wav, code
            )
            Keys.openai.isNotBlank() -> whisper(
                "https://api.openai.com/v1/audio/transcriptions", Keys.openai,
                listOf("gpt-4o-mini-transcribe", "whisper-1"), wav, code
            )
            Keys.gemini.isNotBlank() -> geminiTranscribe(wav)
            else -> throw NoKeyException()
        }
    }

    private fun whisper(url: String, key: String, models: List<String>, wav: ByteArray, lang: String): String {
        var last: HttpException? = null
        for (m in models) {
            try {
                val boundary = "----jarvis" + System.currentTimeMillis()
                val conn = URL(url).openConnection() as HttpURLConnection
                try {
                    conn.requestMethod = "POST"
                    conn.doOutput = true
                    conn.connectTimeout = 15000
                    conn.readTimeout = 40000
                    conn.setRequestProperty("Authorization", "Bearer $key")
                    conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                    conn.outputStream.use { os ->
                        fun field(name: String, value: String) {
                            os.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
                        }
                        field("model", m)
                        field("language", lang)
                        field("response_format", "json")
                        field("temperature", "0")
                        os.write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"a.wav\"\r\nContent-Type: audio/wav\r\n\r\n".toByteArray())
                        os.write(wav)
                        os.write("\r\n--$boundary--\r\n".toByteArray())
                    }
                    val code = conn.responseCode
                    if (code != 200) {
                        val err = try { conn.errorStream?.bufferedReader()?.readText() ?: "" } catch (_: Exception) { "" }
                        throw HttpException(code, err)
                    }
                    return JSONObject(conn.inputStream.bufferedReader().readText()).optString("text").trim()
                } finally {
                    conn.disconnect()
                }
            } catch (e: HttpException) {
                last = e
                if (e.code != 400 && e.code != 404) throw e
            }
        }
        throw last ?: Exception("no model")
    }

    private fun geminiTranscribe(wav: ByteArray): String {
        val parts = JSONArray()
            .put(JSONObject().put("text", "اكتب الكلام المسموع في المقطع حرفيا فقط، من غير أي إضافة. لو مفيش كلام اكتب فاضي."))
            .put(JSONObject().put("inlineData", JSONObject().put("mimeType", "audio/wav")
                .put("data", Base64.encodeToString(wav, Base64.NO_WRAP))))
        val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
        val txt = postJson(
            "https://generativelanguage.googleapis.com/v1beta/models/${Config.model()}:generateContent",
            mapOf("x-goog-api-key" to Keys.gemini), body
        )
        return JSONObject(txt).getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text").trim()
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
