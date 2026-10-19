// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.dictation

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/**
 * One dictation heard by Android's SpeechRecognizer: Voice's recognition service when [start] is given its component
 * (SpeechRecognizer.createSpeechRecognizer(context, ComponentName)), else the phone's on-device recognizer
 * (createOnDeviceSpeechRecognizer). The microphone is Voice's or the recognizer's; this class only listens to the
 * RecognitionListener. Everything is called on the main thread and answers on it. Nothing is logged: neither words nor
 * sound. The recognizer is destroyed at each end.
 *
 * Sources: the session, its listener, the silence timer and the release are the voice kit's SystemSpeech.Live and
 * Dictation.SystemSession (Modules/shared/voice, from SMS's core/voice/SystemSpeech.kt), made to take a component;
 * Android's API https://developer.android.com/reference/android/speech/SpeechRecognizer and
 * https://developer.android.com/reference/android/speech/RecognitionListener
 * The extras are RecognizerIntent's EXTRA_LANGUAGE_MODEL, EXTRA_LANGUAGE, EXTRA_PARTIAL_RESULTS,
 * EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS and EXTRA_SEGMENTED_SESSION
 * https://developer.android.com/reference/android/speech/RecognizerIntent
 * Voice (YAZ/Voice, recognition/VoiceRecognitionService) honours exactly these: it gives all the words heard so far in
 * each partial result and the whole text in the final result, and ends after the silence asked; the on-device
 * recognizer of the phone is asked for a session segmented by silence, its segments committed one by one.
 */
class KeyboardRecognizer(private val context: Context) {

    enum class Why {
        /** The user ended it. */
        USER,
        /** Silence after speech ended it. */
        SILENCE,
        /** Nothing was said. */
        NO_SPEECH,
        /** The app has no right to the microphone yet. */
        NO_MICROPHONE_RIGHT,
        /** This recognizer cannot hear the language, or is not there. */
        UNAVAILABLE,
        FAILED,
    }

    interface Listener {
        /** The words being said, as far as known; replaces the previous ones. */
        fun partial(text: String)
        /** Words that are final. */
        fun committed(text: String)
        /** The dictation is over, the recognizer destroyed. */
        fun stopped(why: Why)
    }

    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var listener: Listener? = null
    private var segmented = false
    private var heard = false
    private var userStopped = false
    private var finished = true
    private var segments = 0
    private val silence = Runnable { stop(true) }
    private val giveUp = Runnable { end(0) }

    /** A dictation is running or still finishing its last words. */
    val busy: Boolean get() = !finished

    /**
     * Starts listening in [language]. [service]: Voice's recognition service, or null for the phone's on-device
     * recognizer. False when one is already running, or the recognizer cannot be created.
     */
    fun start(language: Locale, service: ComponentName?, listener: Listener): Boolean {
        if (!finished) return false
        val created = runCatching {
            if (service != null) SpeechRecognizer.createSpeechRecognizer(context, service)
            else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            else null
        }.getOrNull() ?: return false
        this.listener = listener
        recognizer = created
        segmented = service == null
        heard = false
        userStopped = false
        finished = false
        segments = 0
        created.setRecognitionListener(callbacks)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, SILENCE_MS.toInt())
            // The phone's recognizer ends a plain session at the first pause: a session segmented by silence goes on
            // through the pauses (AOSP RecognizerIntent.EXTRA_SEGMENTED_SESSION). Voice keeps one session and gives
            // the whole text at the end.
            if (segmented)
                putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS)
        }
        val started = runCatching { created.startListening(intent) }.isSuccess
        if (!started) {
            release()
            finished = true
            this.listener = null
        }
        return started
    }

    /**
     * Ends the dictation. With [flush] the recognizer is asked to finish and the words it still holds come first; without,
     * they are dropped and nothing more is reported (a key, the keyboard closing, another field).
     */
    fun stop(flush: Boolean) {
        if (finished) return
        if (!flush) {
            finished = true
            release()
            listener = null
            return
        }
        userStopped = true
        handler.removeCallbacks(silence)
        runCatching { recognizer?.stopListening() }
        // a recognizer that never answers is let go; Voice may be writing out its last phrase, which takes longer
        handler.removeCallbacks(giveUp)
        handler.postDelayed(giveUp, if (segmented) 2_500 else 15_000)
    }

    private fun release() {
        handler.removeCallbacks(silence)
        handler.removeCallbacks(giveUp)
        val r = recognizer ?: return
        recognizer = null
        runCatching { r.cancel() }
        runCatching { r.destroy() }
    }

    private fun end(error: Int) {
        if (finished) return
        finished = true
        release()
        val to = listener
        listener = null
        val why = when {
            error == 0 && userStopped -> Why.USER
            error == 0 && heard -> Why.SILENCE
            error == 0 -> Why.NO_SPEECH
            error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> Why.NO_MICROPHONE_RIGHT
            error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED || error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> Why.UNAVAILABLE
            else -> Why.FAILED
        }
        to?.stopped(why)
    }

    private fun first(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }

    private val callbacks = object : RecognitionListener {
        override fun onBeginningOfSpeech() {
            handler.removeCallbacks(silence)
        }

        override fun onEndOfSpeech() {
            handler.removeCallbacks(silence)
            handler.postDelayed(silence, SILENCE_MS)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            handler.removeCallbacks(silence)
            first(partialResults)?.let { if (!finished) { heard = true; listener?.partial(it) } }
        }

        override fun onSegmentResults(segment: Bundle) {
            first(segment)?.let { segments++; if (!finished) { heard = true; listener?.committed(it) } }
            handler.removeCallbacks(silence)
            handler.postDelayed(silence, SILENCE_MS)
        }

        override fun onEndOfSegmentedSession() = end(0)

        override fun onResults(results: Bundle) {
            // a recognizer that does not segment gives its words once, at the end
            if (segments == 0) first(results)?.let { if (!finished) { heard = true; listener?.committed(it) } }
            end(0)
        }

        override fun onError(error: Int) = when (error) {
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> end(0)
            else -> end(error)
        }

        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    companion object {
        /** Silence after speech that ends the dictation (the voice kit's Dictation.SILENCE_MS). */
        const val SILENCE_MS = 3_000L
    }
}
