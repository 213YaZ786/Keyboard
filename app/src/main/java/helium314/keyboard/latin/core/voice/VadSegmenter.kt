package helium314.keyboard.latin.core.voice

/**
 * Cuts a live sound into phrases from a voice detector's probabilities, the way whisper.cpp's streaming example does in
 * its voice-detection mode: when the last second is silent after speech, the speech buffered so far is handed over to
 * be written out and the listening goes on; here Silero's probabilities replace the example's energy test (vad_simple).
 * Silence going on for [Params.stopSilenceMs] after speech ends the dictation.
 *
 * Sources:
 * - examples/stream/stream.cpp in its VAD mode (step 0): the last 1000 ms silent, then transcribe, length_ms 10000
 *   https://github.com/ggml-org/whisper.cpp/blob/v1.9.4/examples/stream/stream.cpp
 * - Transcribro's thresholds (START_THRESHOLD 0.6, END_THRESHOLD 0.45) and its 3000 ms of silence that ends a dictation
 *   https://github.com/soupslurpr/Transcribro/blob/main/app/src/main/kotlin/dev/soupslurpr/transcribro/recognitionservice/MainRecognitionService.kt
 * - whisper_vad_default_params: min_speech_duration_ms 250
 *   https://github.com/ggml-org/whisper.cpp/blob/v1.9.4/include/whisper.h
 * - Silero's window of 512 samples at 16 kHz
 *   https://github.com/snakers4/silero-vad
 * It holds no Android and no native code: feed() takes the sound and the probabilities, so it is tested on the JVM.
 */
class VadSegmenter(private val params: Params = Params()) {

    data class Params(
        /** Samples of one probability (Silero at 16 kHz). */
        val window: Int = 512,
        val sampleRate: Int = 16_000,
        /** Speech begins at this probability, and goes on while it stays above [endThreshold] (Transcribro). */
        val startThreshold: Float = 0.6f,
        val endThreshold: Float = 0.45f,
        /** Silence after speech that closes a phrase (stream.cpp: the last 1000 ms). */
        val phraseSilenceMs: Int = 1_000,
        /** Silence after speech that ends the dictation (Transcribro's MIN_SILENCE_DURATION_MS). */
        val stopSilenceMs: Int = 3_000,
        /** A phrase never goes beyond this (stream.cpp's length_ms). */
        val maxPhraseMs: Int = 10_000,
        /** Sound kept from before the speech began, since the detector is a little late (stream.cpp keeps 200 ms: keep_ms). */
        val preRollMs: Int = 500,
        /** Speech shorter than this is a click, not a phrase (whisper_vad_default_params). */
        val minSpeechMs: Int = 250,
        /** Nothing said at all for this long ends the dictation: the microphone is never left open for ever. */
        val noSpeechStopMs: Int = 10_000,
    )

    enum class Reason { SILENCE, NO_SPEECH }

    sealed interface Event {
        /** Speech just began. */
        data object Speech : Event
        /** A phrase to write out; [endsUtterance] false when it was cut at the longest length and the speech goes on. */
        class Phrase(val pcm: FloatArray, val endsUtterance: Boolean) : Event
        /** The dictation should end. */
        class Stop(val reason: Reason) : Event
    }

    private val windowMs = params.window * 1000 / params.sampleRate
    private var buffer = FloatArray(params.sampleRate * 2)
    private var length = 0
    private var speaking = false
    private var spoke = false
    private var quietMs = 0
    private var speechMs = 0
    private var stopped = false

    /** [pcm] is the sound (a whole number of windows) and [probs] the probability of speech in each window. */
    fun feed(pcm: FloatArray, probs: FloatArray): List<Event> {
        require(pcm.size == probs.size * params.window) { "sound and probabilities do not match" }
        val events = ArrayList<Event>(2)
        for (i in probs.indices) {
            if (stopped) break
            append(pcm, i * params.window)
            val p = probs[i]
            if (!speaking) {
                if (p >= params.startThreshold) {
                    speaking = true
                    spoke = true
                    quietMs = 0
                    speechMs = windowMs
                    events += Event.Speech
                } else {
                    quietMs += windowMs
                    dropOldest(params.preRollMs * params.sampleRate / 1000)
                    if (spoke && quietMs >= params.stopSilenceMs) {
                        stopped = true
                        events += Event.Stop(Reason.SILENCE)
                    } else if (!spoke && quietMs >= params.noSpeechStopMs) {
                        stopped = true
                        events += Event.Stop(Reason.NO_SPEECH)
                    }
                }
            } else {
                if (p >= params.endThreshold) {
                    quietMs = 0
                    speechMs += windowMs
                } else {
                    quietMs += windowMs
                }
                if (quietMs >= params.phraseSilenceMs) {
                    phrase(events, endsUtterance = true)
                    speaking = false
                } else if (length * 1000L >= params.maxPhraseMs.toLong() * params.sampleRate) {
                    phrase(events, endsUtterance = false)
                }
            }
        }
        return events
    }

    /** The speech still held when the user ends the dictation, if it was long enough to be a phrase. */
    fun flush(): List<Event> {
        val events = ArrayList<Event>(1)
        if (speaking) phrase(events, endsUtterance = true)
        speaking = false
        return events
    }

    private fun phrase(events: MutableList<Event>, endsUtterance: Boolean) {
        if (speechMs >= params.minSpeechMs) events += Event.Phrase(buffer.copyOf(length), endsUtterance)
        speechMs = 0
        if (endsUtterance) {
            // What follows may be the next phrase: the end of this sound stays as its lead-in.
            dropOldest(params.preRollMs * params.sampleRate / 1000)
        } else {
            length = 0
        }
    }

    private fun append(pcm: FloatArray, from: Int) {
        if (length + params.window > buffer.size) buffer = buffer.copyOf(maxOf(buffer.size * 2, length + params.window))
        System.arraycopy(pcm, from, buffer, length, params.window)
        length += params.window
    }

    private fun dropOldest(keep: Int) {
        if (length <= keep) return
        System.arraycopy(buffer, length - keep, buffer, 0, keep)
        length = keep
    }
}
