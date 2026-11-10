package com.jarvis.tablet

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
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
import java.io.ByteArrayOutputStream
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

class HttpException(val code: Int) : Exception("HTTP $code")

class Engine(private val ctx: Context, private val scope: CoroutineScope) {

    private val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var callActive = false
    private var lastInteraction = 0L
    @Volatile private var ptt = false
    private val history = ArrayDeque<Pair<String, String>>()

    fun start() {
        tts = TextToSpeech(ctx) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale("ar")
                ttsReady = true
            }
        }
        if (Config.apiKey.isBlank()) Bus.log("GEMINI_API_KEY فاضي - ضيفه كـ GitHub Secret وابني تاني")
        pushStatus()
        Bus.onText = { t -> scope.launch(Dispatchers.Default) { handle(null, t) } }
        Bus.onToggle = { ptt = true }
        Bus.log("Jarvis engine online")
        scope.launch(Dispatchers.Default) { loop() }
    }

    private fun pushStatus() {
        Bus.emit(
            JSONObject().put("type", "status")
                .put("whatsapp", "ONLINE 🟢")
                .put("call", if (callActive) "JARVIS VOICE DEPLOYED / LIVE CALL 🟢" else "AWAITING CALL")
                .put("live", callActive)
        )
    }

    // ------------------------------------------------------------ main loop
    @Suppress("DEPRECATION")
    private suspend fun loop() {
        while (scope.isActive) {
            val inCall = audio.mode == AudioManager.MODE_IN_COMMUNICATION
            if (inCall != callActive) {
                callActive = inCall
                history.clear()
                lastInteraction = 0
                Bus.log(if (inCall) "CALL CONNECTED - voice channel open" else "CALL ENDED")
                pushStatus()
                if (inCall) {
                    try { audio.isSpeakerphoneOn = true } catch (_: Exception) {}
                    speak("أهلا، جارفس معاك")
                }
            }

            if (!(callActive || Config.alwaysListen() || ptt)) { delay(300); continue }

            val forced = ptt
            if (forced) Bus.state("listening", "LISTENING")
            val pcm = recordChunk()
            if (pcm == null) {
                ptt = false
                Bus.state("", "TAP JARVIS TO SPEAK")
                delay(1000); continue
            }
            val level = rms(pcm)
            if (level < Config.silenceRms()) {
                if (forced) {
                    ptt = false
                    Bus.say("err", "ما سمعتش حاجة")
                    Bus.state("", "TAP JARVIS TO SPEAK")
                }
                continue
            }
            ptt = false
            handle(toWav(pcm), null, forced)
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
            Bus.log("Mic init failed: ${e.javaClass.simpleName}")
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

    // ------------------------------------------------------------ gemini
    private fun installedApps(): List<Pair<String, String>> {
        val pm = ctx.packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(i, 0)
            .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
            .distinctBy { it.second }
    }

    private fun systemPrompt(): String {
        val names = Config.contacts().keys().asSequence().joinToString("، ").ifEmpty { "(لا يوجد)" }
        val apps = installedApps().map { it.first }.take(200).joinToString("، ")
        return """أنت جارفس، مساعد صوتي شخصي على الموبايل. بتستقبل مقطع صوتي قصير، غالبا بالعامية المصرية.
ارجع JSON فقط بالشكل ده:
{"transcript": "...", "addressed": true/false, "action": "none" | "whatsapp" | "wake_pc" | "open_app" | "web_search" | "create_project", "contact": "...", "message": "...", "app": "...", "query": "...", "project_prompt": "...", "reply": "..."}

القواعد:
- transcript: اللي اتقال حرفيا. لو صمت أو ضجيج خليه "".
- addressed: true لو المتحدث نادى "جارفس" أو "جارفيس" أو Jarvis، أو لو in_conversation = true وده استكمال للكلام.
- لو addressed=false: action="none" و reply="".
- action="whatsapp": رسالة واتساب. contact لازم يكون واحد من الأسماء دي بالظبط: $names. message نص الرسالة فقط.
- action="wake_pc": تشغيل اللابتوب أو الكمبيوتر.
- action="open_app": فتح تطبيق. app لازم يكون اسم واحد بالظبط من التطبيقات المثبتة دي: $apps
- action="web_search": بحث على الإنترنت. query نص البحث.
- action="create_project": المستخدم عايز يعمل مشروع أو موقع أو تطبيق. project_prompt وصف واضح ومفصل بالإنجليزي للي هيتبني.
- غير كده action="none" و reply إجابة قصيرة جدا (جملة أو اتنين) بالعامية المصرية، من غير رموز أو تنسيق، لأنها هتتقال بصوت.
- لو action مش none، reply تأكيد قصير زي "تمام، فتحت يوتيوب"."""
    }

    private fun geminiCall(wav: ByteArray?, typed: String?, inConv: Boolean): JSONObject {
        val hist = history.takeLast(4).joinToString("\n") { "المستخدم: ${it.first}\nجارفس: ${it.second}" }
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
        if (typed != null) parts.put(JSONObject().put("text", "المستخدم كتب مباشرة (addressed=true): $typed"))
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt()))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
            .put("generationConfig", JSONObject().put("responseMimeType", "application/json").put("temperature", 0.4))

        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/${Config.model()}:generateContent")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 15000
            conn.readTimeout = 40000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("x-goog-api-key", Config.apiKey)
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            if (conn.responseCode != 200) throw HttpException(conn.responseCode)
            val resp = conn.inputStream.bufferedReader().readText()
            val text = JSONObject(resp).getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
            return JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }

    private suspend fun handle(wav: ByteArray?, typed: String?, forced: Boolean = false) {
        if (typed != null || forced) Bus.state("thinking", "THINKING")
        try {
            handleInner(wav, typed, forced)
        } finally {
            Bus.state("", "TAP JARVIS TO SPEAK")
        }
    }

    private suspend fun handleInner(wav: ByteArray?, typed: String?, forced: Boolean) {
        val inConv = typed != null || forced || System.currentTimeMillis() - lastInteraction < 20_000
        val res = try {
            withContext(Dispatchers.IO) { geminiCall(wav, typed, inConv) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpException) {
            Bus.log("Gemini HTTP ${e.code}")
            if (e.code == 429) delay(30_000)
            return
        } catch (e: Exception) {
            Bus.log("Gemini error: ${e.javaClass.simpleName}")
            return
        }

        val transcript = (typed ?: res.optString("transcript")).trim()
        if (transcript.isEmpty()) return
        if (typed == null && !forced && !res.optBoolean("addressed")) return
        Bus.say("u", transcript)

        lastInteraction = System.currentTimeMillis()
        var reply = res.optString("reply").trim()

        when (res.optString("action", "none")) {
            "whatsapp" -> {
                val ok = doWhatsapp(res.optString("contact"), res.optString("message"))
                if (!ok) reply = "مش قادر ابعت الرسالة، اتأكد من الاسم"
            }
            "wake_pc" -> {
                val ok = doWakePc()
                if (!ok) reply = "عنوان الجهاز مش متظبط"
            }
            "open_app" -> {
                if (!doOpenApp(res.optString("app"))) reply = "مش لاقي التطبيق ده"
            }
            "web_search" -> {
                val q = res.optString("query")
                val ok = q.isNotBlank() && launch(
                    Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.google.com/search?q=" + enc(q)))
                )
                if (!ok) reply = "معرفتش افتح البحث"
            }
            "create_project" -> {
                val pr = res.optString("project_prompt")
                val ok = pr.isNotBlank() && launch(
                    Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://lovable.dev/?autosubmit=true#prompt=" + enc(pr)))
                )
                if (!ok) reply = "معرفتش افتح صفحة المشروع"
            }
        }

        if (reply.isNotEmpty()) {
            history.addLast(transcript to reply)
            while (history.size > 6) history.removeFirst()
            speak(reply)
            lastInteraction = System.currentTimeMillis()
        }
    }

    // ------------------------------------------------------------ actions

    private fun enc(t: String) = URLEncoder.encode(t, "UTF-8").replace("+", "%20")

    private fun launch(intent: Intent): Boolean {
        val a11y = JarvisAccessibility.instance
        if (a11y == null) { Bus.log("Accessibility service مش شغالة"); return false }
        return a11y.launch(intent)
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
        val text = URLEncoder.encode(message, "UTF-8").replace("+", "%20")
        val url = "https://wa.me/${normalizeNumber(number)}?text=$text"
        Bus.log("WHATSAPP -> $contact: $message")
        return JarvisAccessibility.instance?.openWhatsApp(url) ?: run {
            Bus.log("Accessibility service مش شغالة")
            false
        }
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
            Bus.log("WOL failed: ${e.javaClass.simpleName}")
            false
        }
    }

    // ------------------------------------------------------------ tts
    private suspend fun speak(text: String) {
        Bus.emit(JSONObject().put("type", "speaking").put("text", text))
        Bus.say("j", text)
        Bus.state("speaking", "SPEAKING")
        val engine = tts
        if (!ttsReady || engine == null) return

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
        delay(600)   // let the echo die before recording again
    }
}
