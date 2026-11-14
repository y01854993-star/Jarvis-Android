package com.jarvis.tablet

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/** Speech-to-text with the phone's own recognizer. Prefers the on-device (offline) model, falls back to the default one. */
class DeviceStt(private val ctx: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var rec: SpeechRecognizer? = null

    private class Outcome(val text: String?, val code: Int)

    suspend fun listen(lang: String, onError: (String) -> Unit): String? {
        val attempts = ArrayList<Boolean>()   // true = on-device model, false = default recognizer
        if (Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)) attempts.add(true)
        if (SpeechRecognizer.isRecognitionAvailable(ctx)) attempts.add(false)
        if (attempts.isEmpty()) {
            onError("خدمة التعرف على الصوت مش موجودة. ثبّت تطبيق Google / Speech Services")
            return null
        }
        var lastCode = -1
        for ((i, onDevice) in attempts.withIndex()) {
            val o = once(lang, onDevice)
            if (o?.text != null) return o.text
            lastCode = o?.code ?: -1
            val retryable = lastCode in listOf(1, 2, 5, 11, 12, 13, -1)
            if (!(retryable && i < attempts.size - 1)) break
        }
        onError(message(lastCode))
        return null
    }

    private fun message(code: Int): String = when (code) {
        6, 7 -> "ما سمعتش حاجة، جرّب تاني"
        9 -> "لازم إذن المايك"
        12, 13 -> "حزمة العربي الأوفلاين مش متنزلة. نزّلها من إعدادات Google ← الصوت ← التعرف على الكلام بدون إنترنت ← العربية"
        1, 2 -> "التعرف على الصوت محتاج إنترنت (الحزمة الأوفلاين مش متنزلة)"
        8 -> "المايك مشغول، جرّب تاني"
        5, 11 -> "خدمة التعرف على الصوت وقفت، جرّب تاني"
        -1 -> "التعرف على الصوت فشل"
        else -> "خطأ في التعرف على الصوت ($code)"
    }

    private suspend fun once(lang: String, onDevice: Boolean): Outcome? {
        val d = CompletableDeferred<Outcome>()
        main.post {
            try {
                rec?.destroy()
                val r = if (onDevice && Build.VERSION.SDK_INT >= 33) SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)
                        else SpeechRecognizer.createSpeechRecognizer(ctx)
                rec = r
                r.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                    override fun onError(error: Int) { d.complete(Outcome(null, error)) }
                    override fun onResults(results: Bundle?) {
                        val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                        d.complete(if (t.isNullOrBlank()) Outcome(null, 7) else Outcome(t, 0))
                    }
                })
                r.startListening(
                    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
                        .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                        .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                )
            } catch (e: Exception) {
                d.complete(Outcome(null, 5))
            }
        }
        val o = withTimeoutOrNull(20_000) { d.await() }
        if (o == null) cancel()
        return o
    }

    fun cancel() {
        main.post { try { rec?.cancel() } catch (_: Exception) {} }
    }
}
