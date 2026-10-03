package com.example.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

interface TtsProvider {
  fun speak(text: String, onComplete: () -> Unit, onError: (String) -> Unit)
  fun stop()
  fun setSpeed(speed: Float)
  fun setVolume(volume: Float)
  fun destroy()
}

/** Real TextToSpeech with UtteranceProgressListener completion (no timing heuristics), Hindi/English auto-pick. */
class AndroidTtsProvider(context: Context) : TtsProvider {
  private var tts: TextToSpeech? = null
  @Volatile private var ready = false
  @Volatile private var initFailed = false
  private var queued: Triple<String, () -> Unit, (String) -> Unit>? = null
  private var speed = 1.0f
  private var volume = 1.0f
  private val ids = AtomicInteger()
  private val callbacks = HashMap<String, Pair<() -> Unit, (String) -> Unit>>()

  init {
    tts = TextToSpeech(context.applicationContext) { status ->
      if (status == TextToSpeech.SUCCESS) {
        ready = true
        tts?.setSpeechRate(speed)
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
          override fun onStart(id: String?) {}
          override fun onDone(id: String?) { synchronized(callbacks) { callbacks.remove(id) }?.first?.invoke() }
          @Deprecated("Deprecated in Java") override fun onError(id: String?) { synchronized(callbacks) { callbacks.remove(id) }?.second?.invoke("TTS playback error") }
          override fun onError(id: String?, errorCode: Int) { synchronized(callbacks) { callbacks.remove(id) }?.second?.invoke("TTS error $errorCode") }
          override fun onStop(id: String?, interrupted: Boolean) { synchronized(callbacks) { callbacks.remove(id) }?.first?.invoke() }
        })
        queued?.let { (t, c, e) -> queued = null; speak(t, c, e) }
      } else { initFailed = true; queued?.let { (_, _, e) -> e("TTS engine initialise nahi hua") }; queued = null }
    }
  }

  private fun isDevanagari(s: String) = s.any { it in '\u0900'..'\u097F' }

  override fun speak(text: String, onComplete: () -> Unit, onError: (String) -> Unit) {
    if (initFailed) { onError("TTS engine unavailable"); return }
    if (!ready) { queued = Triple(text, onComplete, onError); return }
    val engine = tts ?: run { onError("TTS released"); return }
    val loc = if (isDevanagari(text)) Locale("hi", "IN") else Locale("en", "IN")
    val avail = engine.isLanguageAvailable(loc)
    if (avail >= TextToSpeech.LANG_AVAILABLE) engine.language = loc else engine.language = Locale.US
    val id = "jarvis_${ids.incrementAndGet()}"
    synchronized(callbacks) { callbacks[id] = onComplete to onError }
    val params = android.os.Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume) }
    val rc = engine.speak(text.take(3500), TextToSpeech.QUEUE_FLUSH, params, id)
    if (rc != TextToSpeech.SUCCESS) { synchronized(callbacks) { callbacks.remove(id) }; onError("TTS speak() fail ($rc)") }
  }

  override fun stop() { runCatching { tts?.stop() } }
  override fun setSpeed(speed: Float) { this.speed = speed; runCatching { tts?.setSpeechRate(speed) } }
  override fun setVolume(volume: Float) { this.volume = volume.coerceIn(0f, 1f) }
  override fun destroy() { runCatching { tts?.stop(); tts?.shutdown() }; tts = null }
}
