package com.jarvis.tablet

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/** Speech-to-text using the phone's own Google recognizer (no API key). */
class Stt(
    private val ctx: Context,
    private val onResult: (String) -> Unit,
    private val onError: (String) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private var rec: SpeechRecognizer? = null

    fun start(lang: String) {
        main.post {
            if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
                onError("خدمة التعرف على الصوت مش متاحة على الجهاز (ثبّت Google app)")
                return@post
            }
            rec?.destroy()
            val r = SpeechRecognizer.createSpeechRecognizer(ctx)
            rec = r
            r.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}

                override fun onError(error: Int) {
                    onError(
                        when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "ما سمعتش حاجة، جرّب تاني"
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "لازم إذن المايك"
                            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "مشكلة نت في التعرف على الصوت"
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "المايك مشغول، جرّب تاني"
                            else -> "خطأ في التعرف على الصوت ($error)"
                        }
                    )
                }

                override fun onResults(results: Bundle?) {
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (text.isNullOrBlank()) onError("ما سمعتش حاجة، جرّب تاني") else onResult(text)
                }
            })
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            r.startListening(i)
        }
    }

    fun cancel() {
        main.post { rec?.cancel() }
    }
}
