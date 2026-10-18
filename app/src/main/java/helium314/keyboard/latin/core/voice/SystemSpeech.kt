package helium314.keyboard.latin.core.voice

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.coroutines.resume

/**
 * The phone's own on-device speech recognizer, when it has one with the
 * phone's language already installed (on stock Pixels, Google's; on
 * GrapheneOS, theirs once it ships): nothing to fetch, the phone's
 * language only. Never a recognizer that sends the sound to a server.
 * From SMS's core/voice/SystemSpeech.kt; `ready` takes the language asked about, and `Live` is the microphone's mode.
 */
internal class SystemSpeech(private val context: Context) {

    /** Whether the phone hears [language] on the device, nothing to fetch. Asked once. */
    suspend fun ready(language: Locale = Locale.getDefault()): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        if (!runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false)) return false
        return withTimeoutOrNull(5_000) { withContext(Dispatchers.Main) { installed(language.toLanguageTag()) } } ?: false
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private suspend fun installed(language: String): Boolean = suspendCancellableCoroutine { done ->
        val asker = runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(context) }.getOrNull()
        if (asker == null) {
            done.resume(false)
            return@suspendCancellableCoroutine
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
        }
        fun finish(ok: Boolean) {
            runCatching { asker.destroy() }
            if (done.isActive) done.resume(ok)
        }
        done.invokeOnCancellation { runCatching { asker.destroy() } }
        runCatching {
            asker.checkRecognitionSupport(intent, context.mainExecutor, object : RecognitionSupportCallback {
                override fun onSupportResult(support: RecognitionSupport) =
                    finish(support.installedOnDeviceLanguages.any { it.equals(language, true) || it.substringBefore('-').equals(language.substringBefore('-'), true) })
                override fun onError(error: Int) = finish(false)
            })
        }.onFailure { finish(false) }
    }

    /** The words in [pcm] (16 kHz mono, -1 to 1), or null. */
    suspend fun hear(pcm: FloatArray): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        val pipe = ParcelFileDescriptor.createPipe()
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16_000)
            // The whole message, through its pauses, to its end.
            putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
        }
        // The sound goes into the pipe as the recognizer reads it.
        val feeder = Thread {
            ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { out ->
                runCatching {
                    val bytes = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                    pcm.forEach { bytes.putShort((it * 32767f).toInt().coerceIn(-32768, 32767).toShort()) }
                    out.write(bytes.array())
                }
            }
        }.apply { start() }
        return try {
            withContext(Dispatchers.Main) { listen(intent) }
        } finally {
            runCatching { pipe[0].close() }
            withContext(Dispatchers.IO) { feeder.join(2000) }
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private suspend fun listen(intent: Intent): String? = suspendCancellableCoroutine { done ->
        val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        val words = StringBuilder()
        fun finish(text: String?) {
            runCatching { recognizer.destroy() }
            if (done.isActive) done.resume(text)
        }
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onSegmentResults(segment: Bundle) {
                segment.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    if (words.isNotEmpty()) words.append(' ')
                    words.append(it)
                }
            }
            override fun onEndOfSegmentedSession() = finish(words.toString())
            override fun onResults(results: Bundle) {
                results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { if (words.isEmpty()) words.append(it.trim()) }
                finish(words.toString())
            }
            override fun onError(error: Int) = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> finish(words.toString())
                else -> finish(words.toString().takeIf { it.isNotEmpty() })
            }
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        done.invokeOnCancellation { runCatching { recognizer.cancel(); recognizer.destroy() } }
        recognizer.startListening(intent)
    }

    /** What a live session tells its owner, always on the main thread. */
    interface LiveCallback {
        /** The words of the segment being heard, as far as they are known. */
        fun partial(text: String)
        /** The words of a segment that is over. */
        fun segment(text: String)
        /** The session is over: [error] is a SpeechRecognizer.ERROR_* code, 0 when it ended by itself or on request. */
        fun ended(error: Int)
        /** The loudness of the sound now, in the recognizer's dB: RecognitionListener.onRmsChanged
         *  https://developer.android.com/reference/android/speech/RecognitionListener#onRmsChanged(float) */
        fun level(rmsDb: Float) = Unit
    }

    /**
     * The microphone read by the phone's own recognizer, live: the words of the segment being said come as partial
     * results, each finished segment as a segment result, and the session goes on through the pauses until stop()
     * (a segmented session). [silenceMs] after the last words, it stops by itself.
     * Sources: Android's SpeechRecognizer, RecognizerIntent.EXTRA_PARTIAL_RESULTS and EXTRA_SEGMENTED_SESSION
     * https://developer.android.com/reference/android/speech/RecognizerIntent#EXTRA_SEGMENTED_SESSION
     * https://developer.android.com/reference/android/speech/SpeechRecognizer#createOnDeviceSpeechRecognizer(android.content.Context)
     * and Transcribro's recognition service, which gives the same callbacks (partialResults, results):
     * https://github.com/soupslurpr/Transcribro/blob/main/app/src/main/kotlin/dev/soupslurpr/transcribro/recognitionservice/MainRecognitionService.kt
     */
    class Live(
        private val context: Context,
        private val language: Locale,
        private val silenceMs: Long,
        private val callback: LiveCallback,
    ) {
        private val handler = Handler(Looper.getMainLooper())
        private var recognizer: SpeechRecognizer? = null
        private var finished = false
        private var segments = 0
        private val silence = Runnable { stop() }
        private val giveUp = Runnable { end(0) }

        /** Starts listening; false when this phone has no on-device recognizer or the microphone is not allowed. */
        fun start(): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
            val created = runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(context) }.getOrNull() ?: return false
            recognizer = created
            created.setRecognitionListener(listener)
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, language.toLanguageTag())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                // Segmented session by silence: the extra is a string naming the extra that decides the end, here the
                // length of silence (AOSP RecognizerIntent.java, EXTRA_SEGMENTED_SESSION); the recognizer then also
                // ends the session by itself after [silenceMs] of silence, as the timer below does
                // https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/speech/RecognizerIntent.java
                putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, silenceMs.toInt())
            }
            val started = runCatching { created.startListening(intent) }.isSuccess
            if (!started) release()
            return started
        }

        /** Asks the recognizer to finish: the words it still holds come, then the session ends. */
        fun stop() {
            handler.removeCallbacks(silence)
            val r = recognizer ?: return
            if (finished) return
            runCatching { r.stopListening() }
            handler.postDelayed(giveUp, 2_500) // a recognizer that never answers is let go
        }

        /** Ends at once, the words not yet final lost, nothing reported. */
        fun cancel() {
            finished = true
            release()
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
            callback.ended(error)
        }

        private fun first(bundle: Bundle?): String? =
            bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }

        private val listener = object : RecognitionListener {
            override fun onBeginningOfSpeech() = handler.removeCallbacks(silence)
            override fun onEndOfSpeech() {
                handler.removeCallbacks(silence)
                handler.postDelayed(silence, silenceMs)
            }
            override fun onPartialResults(partialResults: Bundle?) {
                handler.removeCallbacks(silence)
                first(partialResults)?.let { if (!finished) callback.partial(it) }
            }
            override fun onSegmentResults(segment: Bundle) {
                first(segment)?.let { segments++; if (!finished) callback.segment(it) }
                handler.removeCallbacks(silence)
                handler.postDelayed(silence, silenceMs)
            }
            override fun onEndOfSegmentedSession() = end(0)
            override fun onResults(results: Bundle) {
                // A recognizer that does not segment gives its words once, at the end.
                if (segments == 0) first(results)?.let { callback.segment(it) }
                end(0)
            }
            override fun onError(error: Int) = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> end(0)
                else -> end(error)
            }
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onRmsChanged(rmsdB: Float) {
                if (!finished) callback.level(rmsdB)
            }
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        }
    }
}
