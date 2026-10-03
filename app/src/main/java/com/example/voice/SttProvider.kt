package com.example.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

interface SttProvider {
  fun startListening(onResult: (String) -> Unit, onError: (String) -> Unit, onPartial: (String) -> Unit = {}, onLevel: (Float) -> Unit = {})
  fun stopListening()
  fun destroy()
  fun isAvailable(): Boolean
}

/**
 * Android SpeechRecognizer. Must run on the main thread. No fake provider exists: if recognition is
 * unavailable the caller gets an explicit error. Language order: Hindi, Hinglish(en-IN), English.
 */
class AndroidSttProvider(private val context: Context) : SttProvider {
  private var recognizer: SpeechRecognizer? = null
  private val main = Handler(Looper.getMainLooper())
  private var retried = false

  override fun isAvailable() = SpeechRecognizer.isRecognitionAvailable(context)

  private fun ensure(): SpeechRecognizer? {
    if (recognizer == null && isAvailable()) recognizer = SpeechRecognizer.createSpeechRecognizer(context)
    return recognizer
  }

  override fun startListening(onResult: (String) -> Unit, onError: (String) -> Unit, onPartial: (String) -> Unit, onLevel: (Float) -> Unit) {
    main.post {
      if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) { onError("MIC_PERMISSION: Microphone permission chahiye."); return@post }
      val r = ensure() ?: run { onError("STT_UNAVAILABLE: Is device par speech recognition service nahi hai."); return@post }
      retried = false
      val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
        putExtra(RecognizerIntent.EXTRA_SUPPORTED_LANGUAGES, arrayListOf("hi-IN", "en-IN", "en-US"))
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
      }
      r.setRecognitionListener(object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) { onLevel(((rmsdB + 2f) / 12f).coerceIn(0.05f, 1f)) }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(p: Bundle?) { p?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(onPartial) }
        override fun onEvent(eventType: Int, params: Bundle?) {}
        override fun onError(error: Int) {
          val transient = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY
          if (transient && !retried) { retried = true; main.postDelayed({ runCatching { r.cancel(); r.startListening(intent) } }, 300); return }
          onError(when (error) {
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "NO_SPEECH: Awaaz samajh nahi aayi, dobara bolein."
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "NETWORK: Speech recognition ke liye internet/offline language pack chahiye."
            SpeechRecognizer.ERROR_AUDIO -> "AUDIO: Mic record nahi ho raha."
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "MIC_PERMISSION: Microphone permission chahiye."
            else -> "STT_ERROR($error)"
          })
        }
        override fun onResults(results: Bundle?) {
          val best = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
          if (best.isNullOrBlank()) onError("NO_SPEECH: Kuch sunai nahi diya.") else onResult(best)
        }
      })
      try { r.startListening(intent) } catch (e: Exception) { onError("STT_ERROR: ${e.message}") }
    }
  }

  override fun stopListening() { main.post { runCatching { recognizer?.stopListening() } } }
  override fun destroy() { main.post { runCatching { recognizer?.destroy() }; recognizer = null } }
}
