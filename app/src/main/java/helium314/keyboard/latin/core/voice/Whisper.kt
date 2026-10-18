package helium314.keyboard.latin.core.voice

/**
 * whisper.cpp (MIT, https://github.com/ggml-org/whisper.cpp) and the Silero voice detector that ships with it, built
 * with the app for 64-bit phones (arm64-v8a, and x86_64 for computers' emulators). The methods are the app's own
 * bridge (cpp/whisper_jni.cpp), registered when the library loads. From SMS's core/voice/Whisper.kt.
 */
internal object Whisper {

    /** Whether the engine is in this build for this phone. */
    val loaded: Boolean = runCatching { System.loadLibrary("keyboardwhisper"); true }.getOrDefault(false)

    /** A model opened from [model], the CPU variants found in [libDir]; 0 when it cannot be read. */
    @JvmStatic external fun open(libDir: String, model: String): Long

    /**
     * The words in [pcm] (16 kHz mono, -1 to 1) as UTF-8, in [language] (a code whisper knows, else "auto": found from
     * the sound itself), never translated. [live]: dictation, which leaves out sound effects written as words.
     * [prompt]: words the speech likely holds, whisper's initial prompt (cli.cpp's --prompt), or null.
     */
    @JvmStatic external fun transcribe(handle: Long, pcm: FloatArray, threads: Int, language: String, live: Boolean, prompt: String?): ByteArray?

    /** The words in [pcm], in whatever language they are spoken (SMS's voice messages). */
    fun transcribe(handle: Long, pcm: FloatArray, threads: Int): ByteArray? = transcribe(handle, pcm, threads, "auto", false, null)

    /**
     * The language heard in the last sound written out with [handle] (its code, "fr"), or null: cli.cpp's
     * whisper_lang_str(whisper_full_lang_id(ctx)) https://github.com/ggml-org/whisper.cpp/blob/v1.9.4/examples/cli/cli.cpp
     */
    @JvmStatic external fun heardLanguage(handle: Long): String?

    @JvmStatic external fun close(handle: Long)

    /** Stops the work in hand at its next step (a dictation ended); [on] false lets the next one run. */
    @JvmStatic external fun abort(on: Boolean)

    /** Whether whisper knows [language] (its two-letter code, "fr"). */
    @JvmStatic external fun knows(language: String): Boolean

    /** The voice detector opened from [model] (Silero, ggml form); 0 when it cannot be read. */
    @JvmStatic external fun vadOpen(libDir: String, model: String): Long

    /** The probability of speech in each 512-sample window of [pcm] (16 kHz mono), the detector keeping its memory. */
    @JvmStatic external fun vadProbs(handle: Long, pcm: FloatArray): FloatArray?

    /** The detector's memory cleared, between two utterances. */
    @JvmStatic external fun vadReset(handle: Long)

    @JvmStatic external fun vadClose(handle: Long)
}
