package com.jarvis.tablet

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
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
            while (history.size > 8) history.removeFirst()
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
        val names = Config.contacts().keys().asSequence().joinToString("، ").ifEmpty { "(لا يوجد)" }
        val apps = installedApps().map { it.first }.take(200).joinToString("، ")
        val now = SimpleDateFormat("EEEE d MMMM yyyy، h:mm a", Locale("ar")).format(Date())
        val facts = Memory.all().joinToString("\n") { "- $it" }.ifEmpty { "(لسه مفيش)" }
        val extraKeys = if (audioMode) "\"transcript\": \"...\", \"addressed\": true/false, " else ""
        val extraRules = if (audioMode) """
- transcript: اللي اتقال حرفيا. لو صمت أو ضجيج خليه "".
- addressed: true لو المتحدث نادى "جارفس" أو "جارفيس" أو Jarvis، أو لو in_conversation = true.
- لو addressed=false: actions=[] و reply="".""" else ""
        return """أنت J.A.R.V.I.S مساعد شخصي ذكي جدا على موبايل المستخدم. اسمه "${Keys.name}" وبتناديه بيه.
شخصيتك: رسمي، خفيف الدم، واثق، ومباشر. رد بنفس لغة المستخدم، وغالبا بالعامية المصرية.
الكلام جاي من تسجيل صوتي، فصحّح الأسماء والحروف الغلط من السياق.

السياق دلوقتي:
- الوقت: $now
- البطارية: ${batteryPercent()}%
- حاجات عارفها عن المستخدم:
$facts

ارجع JSON فقط بالشكل ده:
{$extraKeys"reply": "...", "actions": [ {"type": "..."} ], "remember": ""}
$extraRules

الأوامر الممكنة في actions (ممكن أكتر من واحد بالترتيب لو الطلب فيه خطوات):
- {"type":"open_app","app":"..."}: app اسم واحد بالظبط من التطبيقات دي: $apps
- {"type":"web_search","query":"..."}
- {"type":"youtube_search","query":"..."}: بحث وتشغيل في يوتيوب
- {"type":"whatsapp","contact":"...","message":"..."}: contact الاسم زي ما المستخدم قاله (من جهات الاتصال أو من: $names)
- {"type":"call","contact":"..."}
- {"type":"navigate","place":"..."}: خريطة وتوجيه
- {"type":"alarm","hour":0-23,"minute":0-59,"label":"..."}
- {"type":"timer","seconds":N,"label":"..."}
- {"type":"wake_pc"}: تشغيل اللابتوب
- {"type":"create_project","project_prompt":"..."}: لما يطلب مشروع أو موقع أو تطبيق، project_prompt وصف مفصل بالإنجليزي

قواعد:
- لو سؤال عادي أو معلومة عامة: actions=[] و reply إجابة صحيحة ومختصرة (جملة لتلات جمل) من غير رموز أو تنسيق، لأنها هتتقال بصوت.
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
        return if (Keys.groq.isNotBlank()) {
            whisper("https://api.groq.com/openai/v1/audio/transcriptions", Keys.groq,
                Config.list("groq_stt_models").ifEmpty { listOf("whisper-large-v3-turbo", "whisper-large-v3") }, wav, code)
        } else {
            whisper("https://api.openai.com/v1/audio/transcriptions", Keys.openai, listOf("whisper-1"), wav, code)
        }
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
                for ((u, r) in history.toList().takeLast(6)) {
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
        val hist = history.toList().takeLast(6).joinToString("\n") { "المستخدم: ${it.first}\nجارفس: ${it.second}" }
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

    /** config.json contacts first, then the phone's own contacts (needs READ_CONTACTS). */
    private fun resolveNumber(name: String): String? {
        if (name.isBlank()) return null
        val cfg = Config.contacts()
        cfg.optString(name, "").takeIf { it.isNotEmpty() }?.let { return it }
        for (k in cfg.keys()) if (k.contains(name, true) || name.contains(k, true)) return cfg.getString(k)
        if (ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        return try {
            ctx.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?",
                arrayOf("%$name%"), null
            )?.use { c -> if (c.moveToFirst()) c.getString(1) else null }
        } catch (_: Exception) { null }
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
