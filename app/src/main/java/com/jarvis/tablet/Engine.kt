package com.jarvis.tablet

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.net.Uri
import android.os.BatteryManager
import android.provider.AlarmClock
import android.provider.ContactsContract
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
import java.io.DataOutputStream
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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
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
    @Volatile private var track: AudioTrack? = null
    private val deadTts = HashSet<String>()
    private class Contact(val name: String, val clean: String, val number: String)
    private var contactsCache: List<Contact> = emptyList()
    private var contactsAt = 0L

    fun start() {
        Keys.init(ctx)
        Memory.init(ctx)
        tts = TextToSpeech(ctx) { status -> if (status == TextToSpeech.SUCCESS) ttsReady = true }
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

    // ------------------------------------------------------------ busy guard
    private suspend fun withBusy(block: suspend () -> Unit) {
        if (busy) { Bus.say("err", "استنى لحظة"); return }
        busy = true
        try {
            block()
        } finally {
            busy = false
            Bus.state("", IDLE)
        }
    }

    // ------------------------------------------------------------ tap to talk
    private fun toggleListen(lang: String) {
        if (busy) { Bus.say("err", "استنى لحظة"); return }
        if (listeningNow) { listeningNow = false; return }     // second tap cancels
        if (!Keys.hasLlm()) { Bus.say("err", "حط مفتاح Groq أو OpenAI الأول"); return }
        tts?.stop()
        try { track?.stop() } catch (_: Exception) {}
        listeningNow = true
        Bus.state("listening", "LISTENING")
        scope.launch(Dispatchers.Default) { listenOnce(lang) }
    }

    private suspend fun listenOnce(lang: String) {
        try {
            val pcm = recordUtterance()
            if (!listeningNow) return                               // cancelled
            if (pcm == null) { Bus.say("err", "ما سمعتش حاجة، جرّب تاني"); return }
            listeningNow = false
            Bus.state("thinking", "THINKING")
            val wav = toWav(pcm)

            if (Keys.groq.isNotBlank() || Keys.openai.isNotBlank()) {
                withBusy {
                    val text = try {
                        withContext(Dispatchers.IO) { transcribe(wav, lang) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: HttpException) {
                        Bus.say("err", httpMessage(e)); return@withBusy
                    } catch (e: Exception) {
                        Bus.say("err", "مشكلة في الاتصال بالنت (${e.javaClass.simpleName})"); return@withBusy
                    }
                    if (text.isBlank()) { Bus.say("err", "مفهمتش الكلام، جرّب تاني"); return@withBusy }
                    process(text)
                }
            } else {
                handleAudio(wav, true)
            }
        } finally {
            listeningNow = false
            if (!busy) Bus.state("", IDLE)
        }
    }

    // ------------------------------------------------------------ brain
    private suspend fun handle(text: String) {
        if (text.isBlank()) return
        withBusy { process(text) }
    }

    private suspend fun process(text: String) {
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
    }

    private fun httpMessage(e: HttpException): String = when (e.code) {
        401, 403 -> "المفتاح غلط أو مش شغال (${e.code})"
        429 -> "وصلت للحد المجاني، جرّب بعد شوية"
        else -> "الـ API رجّع خطأ ${e.code}: " + e.body.take(140)
    }

    private suspend fun act(res: JSONObject, userText: String) {
        var reply = res.optString("reply").trim()

        res.optString("remember").trim().takeIf { it.isNotEmpty() }?.let { Memory.add(it) }

        val list = ArrayList<JSONObject>()
        res.optJSONArray("actions")?.let { arr ->
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { list.add(it) }
        }
        for ((idx, a) in list.withIndex()) {
            val err = runAction(a)
            if (err != null) { reply = err; break }
            if (idx < list.size - 1) delay(1500)
        }

        if (reply.isNotEmpty()) {
            history.addLast(userText to reply)
            while (history.size > 16) history.removeFirst()
            speak(reply)
        }
        lastInteraction = System.currentTimeMillis()
    }

    /** returns an error sentence, or null when OK */
    private suspend fun runAction(a: JSONObject): String? {
        return when (a.optString("type")) {
            "open_app" -> if (doOpenApp(a.optString("app"))) null else "مش لاقي التطبيق ده"
            "web_search" -> {
                val q = a.optString("query")
                if (q.isNotBlank() && launch(view("https://www.google.com/search?q=" + enc(q)))) null else "معرفتش افتح البحث"
            }
            "youtube_search" -> {
                val q = a.optString("query")
                val i = view("https://www.youtube.com/results?search_query=" + enc(q))
                if (q.isNotBlank() && launch(i)) null else "معرفتش افتح يوتيوب"
            }
            "whatsapp" -> if (doWhatsapp(a.optString("contact"), a.optString("message"))) null
                          else "مش لاقي الرقم ده، ضيفه في جهات الاتصال"
            "call" -> {
                val num = resolveNumber(a.optString("contact"))
                if (num != null && launch(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$num")))) null
                else "مش لاقي الرقم ده"
            }
            "navigate" -> {
                val p = a.optString("place")
                if (p.isNotBlank() && launch(view("geo:0,0?q=" + enc(p)))) null else "معرفتش افتح الخريطة"
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
                    .putExtra(AlarmClock.EXTRA_LENGTH, a.optInt("seconds", 60))
                    .putExtra(AlarmClock.EXTRA_MESSAGE, a.optString("label", "Jarvis"))
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                if (launch(i)) null else "معرفتش اظبط التايمر"
            }
            "wake_pc" -> if (doWakePc()) null else "عنوان الجهاز مش متظبط"
            "create_project" -> {
                val pr = a.optString("project_prompt")
                if (pr.isNotBlank() && launch(view("https://lovable.dev/?autosubmit=true#prompt=" + enc(pr)))) null
                else "معرفتش افتح صفحة المشروع"
            }
            else -> null
        }
    }

    // ------------------------------------------------------------ prompts
    private fun installedApps(): List<Pair<String, String>> {
        val pm = ctx.packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(i, 0)
            .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
            .distinctBy { it.second }
    }

    private fun batteryPercent(): Int = try {
        (ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager)
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    } catch (_: Exception) { -1 }

    private fun systemPrompt(audioMode: Boolean): String {
        val names = contactNames()
        val apps = installedApps().map { it.first }.take(200).joinToString("، ")
        val now = SimpleDateFormat("EEEE d MMMM yyyy، h:mm a", Locale("ar")).format(Date())
        val facts = Memory.all().joinToString("\n") { "- $it" }.ifEmpty { "(لسه مفيش)" }
        val extraKeys = if (audioMode) "\"transcript\": \"...\", \"addressed\": true/false, " else ""
        val extraRules = if (audioMode) """
- transcript: اللي اتقال حرفيا. لو صمت أو ضجيج خليه "".
- addressed: true لو المتحدث نادى "جارفس" أو "جارفيس" أو Jarvis، أو لو in_conversation = true.
- لو addressed=false: actions=[] و reply="".""" else ""
        return """أنت J.A.R.V.I.S مساعد شخصي ذكي جدا على موبايل المستخدم. بتناديه دايما "${Keys.name}" بالإنجليزي زي ما هي، حتى وسط الكلام العربي، وخليها جزء طبيعي من ردك (مش في كل جملة).
شخصيتك: رسمي، خفيف الدم، واثق، ومباشر. رد بنفس لغة المستخدم، وغالبا بالعامية المصرية.
الكلام جاي من تسجيل صوتي، فصحّح الأسماء والحروف الغلط من السياق.

السياق دلوقتي:
- الوقت: $now
- البطارية: ${batteryPercent()}%
- حاجات عارفها عن المستخدم:
$facts

ارجع JSON فقط بالشكل ده:
{$extraKeys"thought": "تحليل قصير لنية المستخدم وإيه المطلوب بالظبط", "reply": "...", "actions": [ {"type": "..."} ], "remember": ""}
$extraRules

الأوامر الممكنة في actions (ممكن أكتر من واحد بالترتيب لو الطلب فيه خطوات):
- {"type":"open_app","app":"..."}: app اسم واحد بالظبط من التطبيقات دي: $apps
- {"type":"web_search","query":"..."}
- {"type":"youtube_search","query":"..."}: بحث وتشغيل في يوتيوب
- {"type":"whatsapp","contact":"...","message":"..."}: contact: اختار أقرب اسم للي المستخدم قاله من الأسماء دي (الأسماء متنضفة من الأرقام والرموز، فـ "ماما 010" بتظهر "ماما"): $names
- {"type":"call","contact":"..."}
- {"type":"navigate","place":"..."}: خريطة وتوجيه
- {"type":"alarm","hour":0-23,"minute":0-59,"label":"..."}
- {"type":"timer","seconds":N,"label":"..."}
- {"type":"wake_pc"}: تشغيل اللابتوب
- {"type":"create_project","project_prompt":"..."}: لما يطلب مشروع أو موقع أو تطبيق، project_prompt وصف مفصل بالإنجليزي

قواعد:
- فكّر في "thought" الأول: المستخدم عايز إيه فعلا؟ في إيه معنى ضمني؟ هل الطلب فيه خطوات؟
- للأوامر: reply قصير. للأسئلة والشرح والمعلومات: جاوب بعمق ووضوح (من 4 لـ 8 جمل) مع السبب والمثال لو مفيد، من غير رموز أو تنسيق لأنها هتتقال بصوت.
- لو المستخدم بيحكي أو بيفكر معاك: ناقشه كصديق ذكي، وادي رأيك بصراحة.
- لو محتاج معلومة حديثة (أخبار، طقس، أسعار، نتايج): استخدم web_search وقول إنك فتحت البحث.
- لو الطلب مش واضح (مثلا مين الشخص): اسأل سؤال واحد قصير وخلي actions=[].
- لو المستخدم قال معلومة عن نفسه تستحق تفتكرها (اسمه، شغله، تفضيلاته، ناس مهمة): حطها في remember كجملة قصيرة، غير كده سيبها "".
- مع كل أمر: reply تأكيد قصير زي "تمام، فتحت يوتيوب".
- متخترعش حاجة. لو معرفتش قول معرفش."""
    }

    // ------------------------------------------------------------ http helpers
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
        return try { JSONObject(s) } catch (_: Exception) { JSONObject().put("reply", s) }
    }

    // ------------------------------------------------------------ speech to text (Whisper)
    private fun transcribe(wav: ByteArray, lang: String): String {
        val code = if (lang.startsWith("ar")) "ar" else "en"
        var last: Exception? = null
        if (Keys.groq.isNotBlank()) {
            try {
                return whisper("https://api.groq.com/openai/v1/audio/transcriptions", Keys.groq,
                    Config.list("groq_stt_models").ifEmpty { listOf("whisper-large-v3-turbo", "whisper-large-v3") }, wav, code)
            } catch (e: Exception) { last = e }
        }
        if (Keys.openai.isNotBlank()) {
            try {
                return whisper("https://api.openai.com/v1/audio/transcriptions", Keys.openai, listOf("whisper-1"), wav, code)
            } catch (e: Exception) { last = e }
        }
        throw last ?: NoKeyException()
    }

    private fun whisper(url: String, key: String, models: List<String>, wav: ByteArray, lang: String): String {
        var last: HttpException? = null
        for (m in models) {
            val boundary = "----jarvis" + System.currentTimeMillis()
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 15000
                conn.readTimeout = 60000
                conn.setRequestProperty("Authorization", "Bearer $key")
                conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                DataOutputStream(conn.outputStream).use { out ->
                    fun w(s: String) = out.write(s.toByteArray(Charsets.UTF_8))
                    fun field(n: String, v: String) =
                        w("--$boundary\r\nContent-Disposition: form-data; name=\"$n\"\r\n\r\n$v\r\n")
                    field("model", m)
                    field("language", lang)
                    field("response_format", "json")
                    field("temperature", "0")
                    field("prompt", "أوامر لمساعد شخصي: جارفس، افتح، ابعت واتساب، دور على، اتصل بـ، فكرني.")
                    w("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\nContent-Type: audio/wav\r\n\r\n")
                    out.write(wav)
                    w("\r\n--$boundary--\r\n")
                    out.flush()
                }
                val code = conn.responseCode
                if (code != 200) {
                    val err = try { conn.errorStream?.bufferedReader()?.readText() ?: "" } catch (_: Exception) { "" }
                    throw HttpException(code, err)
                }
                return JSONObject(conn.inputStream.bufferedReader().readText()).optString("text").trim()
            } catch (e: HttpException) {
                last = e
                if (e.code != 400 && e.code != 404) throw e
            } finally {
                conn.disconnect()
            }
        }
        throw last ?: Exception("no model")
    }

    // ------------------------------------------------------------ LLM
    private fun llmCall(text: String): JSONObject {
        val sys = systemPrompt(false)
        val order = if (Config.brain() == "groq") listOf("groq", "openai", "gemini") else listOf("openai", "groq", "gemini")
        var last: Exception? = null
        for (p in order) {
            try {
                val r: JSONObject? = when (p) {
                    "openai" -> if (Keys.openai.isNotBlank()) openAiChat(
                        "https://api.openai.com/v1/chat/completions", Keys.openai,
                        Config.list("openai_models").ifEmpty { listOf("gpt-4o") }, sys, text) else null
                    "groq" -> if (Keys.groq.isNotBlank()) openAiChat(
                        "https://api.groq.com/openai/v1/chat/completions", Keys.groq,
                        Config.list("groq_models").ifEmpty { listOf("llama-3.3-70b-versatile") }, sys, text) else null
                    else -> if (Keys.gemini.isNotBlank()) geminiGenerate(null, text, true) else null
                }
                if (r != null) return r
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: NoKeyException()
    }

    private fun openAiChat(url: String, key: String, models: List<String>, sys: String, user: String): JSONObject {
        var last: HttpException? = null
        for (m in models) {
            try {
                val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", sys))
                for ((u, r) in history.toList().takeLast(12)) {
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
                if (e.code != 400 && e.code != 404) throw e
            }
        }
        throw last ?: Exception("no model")
    }

    private fun geminiGenerate(wav: ByteArray?, typed: String?, inConv: Boolean): JSONObject {
        val hist = history.toList().takeLast(12).joinToString("\n") { "المستخدم: ${it.first}\nجارفس: ${it.second}" }
        val prompt = "in_conversation = $inConv\nآخر الحوار:\n${hist.ifEmpty { "(لا يوجد)" }}"
        val parts = JSONArray().put(JSONObject().put("text", prompt))
        if (wav != null) {
            parts.put(
                JSONObject().put(
                    "inlineData",
                    JSONObject().put("mimeType", "audio/wav").put("data", Base64.encodeToString(wav, Base64.NO_WRAP))
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

    // ------------------------------------------------------------ Gemini audio (call mode / no Whisper key)
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
            handleAudio(toWav(pcm), false)
        }
    }

    private suspend fun handleAudio(wav: ByteArray, forced: Boolean) {
        if (busy) return
        val inConv = forced || System.currentTimeMillis() - lastInteraction < 20_000
        val res = try {
            withContext(Dispatchers.IO) { geminiGenerate(wav, null, inConv) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpException) {
            Bus.say("err", httpMessage(e))
            if (e.code == 429) delay(30_000)
            return
        } catch (e: Exception) {
            if (forced) Bus.say("err", "مشكلة في الاتصال بالنت")
            return
        }
        val transcript = res.optString("transcript").trim()
        if (transcript.isEmpty()) { if (forced) Bus.say("err", "مفهمتش الكلام"); return }
        if (!forced && !res.optBoolean("addressed")) return
        withBusy {
            Bus.state("thinking", "THINKING")
            Bus.say("u", transcript)
            act(res, transcript)
        }
    }

    // ------------------------------------------------------------ recording
    /** Records until ~1.2s of silence after speech (max 16s). Returns null if nothing was said. */
    @SuppressLint("MissingPermission")
    private suspend fun recordUtterance(): ShortArray? = withContext(Dispatchers.IO) {
        val rate = 16000
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) { Bus.say("err", "المايك مش شغال"); return@withContext null }
        val rec = try {
            AudioRecord(Config.audioSource(), rate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf * 4, rate * 2))
        } catch (e: Exception) {
            Bus.say("err", "مش قادر أفتح المايك"); return@withContext null
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release(); Bus.say("err", "مش قادر أفتح المايك (اسمح بإذن المايك)"); return@withContext null
        }
        val frameSize = rate / 10
        val frame = ShortArray(frameSize)
        val pre = ArrayDeque<ShortArray>()
        val speech = ArrayList<ShortArray>()
        var started = false
        var silent = 0
        var frames = 0
        var noise = 0.0
        var thr = 500.0
        try {
            rec.startRecording()
            while (listeningNow && frames < 160) {
                val n = rec.read(frame, 0, frameSize)
                if (n <= 0) break
                val chunk = frame.copyOf(n)
                val lvl = rms(chunk)
                frames++
                if (frames <= 3) {                       // calibrate on ambient noise
                    noise = maxOf(noise, lvl)
                    thr = minOf(900.0, maxOf(450.0, noise * 2.5))
                    pre.addLast(chunk)
                    continue
                }
                if (lvl > thr) {
                    if (!started) { started = true; speech.addAll(pre) }
                    silent = 0
                } else if (started) {
                    silent++
                }
                if (started) speech.add(chunk) else { pre.addLast(chunk); if (pre.size > 4) pre.removeFirst() }
                if (!started && frames > 70) break        // nothing said for ~7s
                if (started && silent >= 12) break        // 1.2s of silence = done
            }
        } finally {
            try { rec.stop() } catch (_: Exception) {}
            rec.release()
        }
        if (!started) return@withContext null
        val total = speech.sumOf { it.size }
        val out = ShortArray(total)
        var off = 0
        for (c in speech) { System.arraycopy(c, 0, out, off, c.size); off += c.size }
        out
    }

    @SuppressLint("MissingPermission")
    private suspend fun recordChunk(): ShortArray? = withContext(Dispatchers.IO) {
        val rate = 16000
        val total = rate * Config.chunkSeconds()
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return@withContext null
        val rec = try {
            AudioRecord(Config.audioSource(), rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 4)
        } catch (e: Exception) { return@withContext null }
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
    private fun view(url: String) = Intent(Intent.ACTION_VIEW, Uri.parse(url))

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

    /** Arabic-aware normalization: drops diacritics, digits and symbols, unifies alef/ya/ta-marbuta. */
    private fun normAr(src: String): String {
        val sb = StringBuilder()
        for (c0 in src.lowercase()) {
            val c = when (c0) {
                '\u0623', '\u0625', '\u0622', '\u0671' -> '\u0627'
                '\u0649' -> '\u064A'
                '\u0629' -> '\u0647'
                '\u0624' -> '\u0648'
                '\u0626' -> '\u064A'
                else -> c0
            }
            if (c in '\u064B'..'\u065F' || c == '\u0640' || c == '\u0670') continue
            if (c.isLetter()) sb.append(c) else if (c.isWhitespace() || c == '-' || c == '_') sb.append(' ')
        }
        return sb.toString().trim().replace(Regex("\\s+"), " ")
    }

    private fun loadContacts(): List<Contact> {
        val now = System.currentTimeMillis()
        if (now - contactsAt < 60_000 && contactsCache.isNotEmpty()) return contactsCache
        if (ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return emptyList()
        val list = ArrayList<Contact>()
        try {
            ctx.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    val n = c.getString(0) ?: continue
                    val num = c.getString(1) ?: continue
                    val clean = normAr(n)
                    if (clean.isNotEmpty()) list.add(Contact(n, clean, num))
                }
            }
        } catch (_: Exception) {}
        contactsCache = list
        contactsAt = now
        return list
    }

    private fun contactNames(): String {
        val fromPhone = loadContacts().map { it.clean }.distinct().take(150)
        val fromCfg = Config.contacts().keys().asSequence().map { normAr(it) }.toList()
        return (fromCfg + fromPhone).distinct().joinToString("، ").ifEmpty { "(لا يوجد)" }
    }

    private fun matchScore(q: String, name: String): Int {
        if (name == q) return 100
        val words = name.split(" ")
        if (words.contains(q)) return 80
        if (name.startsWith(q)) return 70
        if (name.contains(q)) return 50
        if (name.length >= 3 && q.contains(name)) return 40
        return 0
    }

    /** "ماما" يطابق "ماما 010": config.json أول، وبعدين جهات الاتصال في الموبايل */
    private fun resolveNumber(name: String): String? {
        val q = normAr(name)
        if (q.isEmpty()) return null
        val cfg = Config.contacts()
        for (k in cfg.keys()) if (normAr(k) == q) return cfg.getString(k)
        val best = loadContacts()
            .map { it to matchScore(q, it.clean) }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<Contact, Int>> { it.second }.thenBy { it.first.name.length })
            .firstOrNull()
        if (best != null) return best.first.number
        for (k in cfg.keys()) {
            val nk = normAr(k)
            if (nk.contains(q) || q.contains(nk)) return cfg.getString(k)
        }
        return null
    }

    private fun normalizeNumber(raw: String): String {
        var d = raw.filter { it.isDigit() }
        if (d.startsWith("00")) d = d.substring(2)
        if (d.startsWith("0")) d = "20" + d.substring(1)
        return d
    }

    private fun doWhatsapp(contact: String, message: String): Boolean {
        val number = resolveNumber(contact) ?: return false
        if (message.isBlank()) return false
        val url = "https://wa.me/${normalizeNumber(number)}?text=${enc(message)}"
        Bus.log("WHATSAPP -> $contact: $message")
        val a = JarvisAccessibility.instance
        if (a != null) return a.openWhatsApp(url)
        return launch(view(url).setPackage("com.whatsapp"))
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

    // ------------------------------------------------------------ tts: cloud voice + robot effect, system fallback
    private class Pcm(val samples: ShortArray, val rate: Int)

    private fun postBytes(url: String, headers: Map<String, String>, body: JSONObject): ByteArray {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 15000
            conn.readTimeout = 60000
            conn.setRequestProperty("Content-Type", "application/json")
            for ((k, v) in headers) conn.setRequestProperty(k, v)
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            if (code != 200) {
                val err = try { conn.errorStream?.bufferedReader()?.readText() ?: "" } catch (_: Exception) { "" }
                throw HttpException(code, err)
            }
            return conn.inputStream.readBytes()
        } finally {
            conn.disconnect()
        }
    }

    private fun bytesToShorts(b: ByteArray): ShortArray {
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        val out = ShortArray(b.size / 2)
        for (i in out.indices) out[i] = bb.getShort(i * 2)
        return out
    }

    private fun parseWav(b: ByteArray): Pcm {
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        var rate = 24000
        var ch = 1
        while (pos + 8 <= b.size) {
            val id = String(b, pos, 4, Charsets.US_ASCII)
            var size = bb.getInt(pos + 4)
            if (id == "fmt ") { ch = bb.getShort(pos + 10).toInt(); rate = bb.getInt(pos + 12) }
            if (id == "data") {
                if (size < 0 || pos + 8 + size > b.size) size = b.size - pos - 8
                val n = size / 2
                val raw = ShortArray(n) { bb.getShort(pos + 8 + it * 2) }
                val mono = if (ch == 2) ShortArray(n / 2) { ((raw[it * 2] + raw[it * 2 + 1]) / 2).toShort() } else raw
                return Pcm(mono, rate)
            }
            pos += 8 + size + (size and 1)
        }
        throw Exception("bad wav")
    }

    private fun openAiTts(text: String): Pcm {
        var last: HttpException? = null
        for (m in listOf("gpt-4o-mini-tts", "tts-1")) {
            val body = JSONObject().put("model", m).put("voice", Config.ttsVoice())
                .put("input", text).put("response_format", "pcm")
            if (m == "gpt-4o-mini-tts") {
                body.put("instructions",
                    "Voice: a calm, deep, precise male AI assistant robot with a subtle synthetic metallic quality, like JARVIS. " +
                    "Pace: steady and measured. Tone: confident, polished, slightly witty. " +
                    "If the text is Arabic, speak clear Egyptian-accented Arabic.")
            }
            try {
                val bytes = postBytes("https://api.openai.com/v1/audio/speech",
                    mapOf("Authorization" to "Bearer ${Keys.openai}"), body)
                return Pcm(bytesToShorts(bytes), 24000)
            } catch (e: HttpException) {
                last = e
                if (e.code != 400 && e.code != 404) throw e
            }
        }
        throw last ?: Exception("no tts model")
    }

    private fun splitForTts(text: String, max: Int): List<String> {
        val parts = ArrayList<String>()
        var cur = StringBuilder()
        for (s in text.split(Regex("(?<=[.!?؟،,\\n])\\s*"))) {
            if (s.isBlank()) continue
            if (cur.isNotEmpty() && cur.length + s.length + 1 > max) { parts.add(cur.toString().trim()); cur = StringBuilder() }
            if (s.length > max) s.chunked(max).forEach { parts.add(it) } else cur.append(s).append(' ')
        }
        if (cur.isNotBlank()) parts.add(cur.toString().trim())
        return parts
    }

    private fun groqTts(text: String): Pcm {
        val arabic = text.any { it in '\u0600'..'\u06FF' }
        val model = if (arabic) "canopylabs/orpheus-arabic-saudi" else "canopylabs/orpheus-v1-english"
        val voice = if (arabic) Config.groqArabicVoice() else "daniel"
        val all = ArrayList<ShortArray>()
        var rate = 24000
        for (chunk in splitForTts(text.take(600), 190)) {
            val body = JSONObject().put("model", model).put("voice", voice).put("input", chunk)
                .put("response_format", "wav").put("sample_rate", 24000)
            val p = parseWav(postBytes("https://api.groq.com/openai/v1/audio/speech",
                mapOf("Authorization" to "Bearer ${Keys.groq}"), body))
            rate = p.rate
            all.add(p.samples)
        }
        if (all.isEmpty()) throw Exception("empty tts")
        val out = ShortArray(all.sumOf { it.size })
        var off = 0
        for (a in all) { System.arraycopy(a, 0, out, off, a.size); off += a.size }
        return Pcm(out, rate)
    }

    private fun cloudTts(text: String): Pcm? {
        val order = when (Config.ttsProvider()) {
            "openai" -> listOf("openai")
            "groq" -> listOf("groq")
            else -> listOf("openai", "groq")
        }
        for (p in order) {
            if (p in deadTts) continue
            try {
                if (p == "openai" && Keys.openai.isNotBlank()) return openAiTts(text)
                if (p == "groq" && Keys.groq.isNotBlank()) return groqTts(text)
            } catch (e: Exception) {
                deadTts.add(p)
                val why = if (e is HttpException) "${e.code} ${e.body.take(90)}" else e.javaClass.simpleName
                Bus.say("err", "صوت $p مش شغال ($why)، هستخدم البديل")
            }
        }
        return null
    }

    /** ring-modulator + short comb filter = metallic robot timbre, kept intelligible */
    private fun robotize(p: Pcm, amount: Double): Pcm {
        val x = p.samples
        val sr = p.rate
        val d = max(1, (sr * 0.011).toInt())
        val ringMix = (0.30 * amount).toFloat()
        val combGain = (0.40 * amount).toFloat()
        val y = FloatArray(x.size)
        val w = (2.0 * Math.PI * 95.0 / sr)
        for (i in x.indices) {
            val s = x[i] / 32768f
            val ring = s * sin(w * i).toFloat()
            var v = (1f - ringMix) * s + ringMix * ring
            if (i >= d) v += combGain * y[i - d]
            y[i] = v
        }
        var peak = 0.01f
        for (v in y) peak = max(peak, abs(v))
        val g = min(1.8f, 0.92f / peak)
        val out = ShortArray(y.size) { (y[it] * g * 32767f).toInt().coerceIn(-32768, 32767).toShort() }
        return Pcm(out, sr)
    }

    private suspend fun playPcm(p: Pcm) = withContext(Dispatchers.IO) {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        val fmt = AudioFormat.Builder().setSampleRate(p.rate)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
        val t = AudioTrack.Builder().setAudioAttributes(attrs).setAudioFormat(fmt)
            .setBufferSizeInBytes(p.samples.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC).build()
        track = t
        try {
            t.write(p.samples, 0, p.samples.size)
            t.play()
            val total = p.samples.size
            val deadline = System.currentTimeMillis() + total * 1000L / p.rate + 2500
            while (t.playState == AudioTrack.PLAYSTATE_PLAYING &&
                t.playbackHeadPosition < total && System.currentTimeMillis() < deadline) delay(40)
        } finally {
            try { t.stop() } catch (_: Exception) {}
            t.release()
            track = null
        }
    }

    private suspend fun speak(text: String) {
        Bus.say("j", text)
        Bus.state("speaking", "SPEAKING")
        val clean = text.replace(Regex("[*_#`]"), "")
        if (Config.ttsProvider() != "system") {
            val pcm = try { withContext(Dispatchers.IO) { cloudTts(clean) } } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            if (pcm != null) {
                val fx = Config.robotFx()
                playPcm(if (fx > 0.0) robotize(pcm, fx) else pcm)
                delay(300)
                return
            }
        }
        systemSpeak(clean)
    }

    private suspend fun systemSpeak(text: String) {
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
