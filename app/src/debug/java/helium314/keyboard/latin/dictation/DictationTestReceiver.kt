// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.dictation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import helium314.keyboard.latin.core.voice.Dictation
import helium314.keyboard.latin.core.voice.PcmSource
import helium314.keyboard.latin.core.voice.SileroModel
import helium314.keyboard.latin.core.voice.VadSegmenter
import helium314.keyboard.latin.core.voice.Whisper
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/**
 * Debug builds only. Tries the dictation engine on a recorded file, the way whisper.cpp tests its own voice detector
 * and transcription on samples/jfk.wav (tests/test-vad.cpp, tests/test-vad-full.cpp, tests/run-tests.sh):
 * https://github.com/ggml-org/whisper.cpp/blob/v1.9.4/tests/test-vad.cpp
 * The file is a WAV of 16 kHz, mono, 16 bits, put in the app's files (run-as), never shipped in the APK.
 *   adb shell am broadcast -n <package>/helium314.keyboard.latin.dictation.DictationTestReceiver --es wav jfk.wav --es lang en
 * Writes files/dictation-test.txt: what the voice detector found, then what the whole pipeline (detector, phrases,
 * whisper) wrote out of the file followed by silence. The sound is not played in real time.
 */
class DictationTestReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val out = File(context.filesDir, "dictation-test.txt")
        out.writeText("running\n")
        val wav = File(context.filesDir, intent.getStringExtra("wav") ?: "jfk.wav")
        val lang = intent.getStringExtra("lang") ?: "en"
        val samples = runCatching { readWav(wav) }.getOrNull()
        if (samples == null) {
            out.writeText("no readable 16 kHz mono 16-bit wav at ${wav.name}\n")
            return
        }
        val report = StringBuilder("samples=${samples.size} seconds=${samples.size / 16000f}\n")
        report.append(vadReport(context, samples))
        out.writeText(report.toString() + "pipeline: running\n")

        val model = KeyboardSpeech.model(context).file
        if (!model.exists()) {
            out.writeText(report.toString() + "pipeline: no model at ${model.name}\n")
            return
        }
        val started = System.currentTimeMillis()
        val texts = ArrayList<String>()
        val ok = Dictation(context).start(Dictation.Engine.WHISPER, Locale.forLanguageTag(lang), model, object : Dictation.Listener {
            override fun partial(text: String) = Unit
            override fun committed(text: String) { texts += text }
            override fun stopped(why: Dictation.Why) {
                out.writeText(
                    report.toString() + "pipeline: why=$why phrases=${texts.size} ms=${System.currentTimeMillis() - started}\n" +
                        texts.joinToString("\n") { "> $it" } + "\n"
                )
            }
        }, FileSound(samples, tailSeconds = 6))
        if (!ok) out.writeText(report.toString() + "pipeline: busy\n")
    }

    /** What the detector alone finds: seconds of speech and the phrases the segmenter cuts. */
    private fun vadReport(context: Context, samples: ShortArray): String {
        if (!Whisper.loaded) return "vad: library not loaded\n"
        val file = SileroModel.file(context) ?: return "vad: model refused (sum)\n"
        val vad = Whisper.vadOpen(context.applicationInfo.nativeLibraryDir, file.path)
        if (vad == 0L) return "vad: model not opened\n"
        try {
            val chunk = 2048
            val padded = FloatArray((samples.size + 6 * 16000 + chunk - 1) / chunk * chunk)
            for (i in samples.indices) padded[i] = samples[i] / 32768f
            val segmenter = VadSegmenter()
            var windows = 0
            var speech = 0
            var phrases = 0
            var stop: String? = null
            for (at in padded.indices step chunk) {
                val pcm = padded.copyOfRange(at, at + chunk)
                val probs = Whisper.vadProbs(vad, pcm) ?: return "vad: no answer\n"
                windows += probs.size
                speech += probs.count { it >= 0.6f }
                for (e in segmenter.feed(pcm, probs)) when (e) {
                    is VadSegmenter.Event.Phrase -> { phrases++; if (e.endsUtterance) Whisper.vadReset(vad) }
                    is VadSegmenter.Event.Stop -> stop = e.reason.name
                    else -> Unit
                }
            }
            return "vad: windows=$windows speechWindows=$speech speechSeconds=${speech * 0.032f} phrases=$phrases stop=$stop\n"
        } finally {
            Whisper.vadClose(vad)
        }
    }

    /** The samples of a canonical WAV (RIFF, a fmt chunk of PCM 16 kHz mono 16 bits, then data), or null. */
    private fun readWav(file: File): ShortArray? {
        val b = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        if (b.limit() < 44 || b.getInt(0) != 0x46464952 || b.getInt(8) != 0x45564157) return null
        var at = 12
        var fmtOk = false
        while (at + 8 <= b.limit()) {
            val id = b.getInt(at)
            val size = b.getInt(at + 4)
            if (id == 0x20746d66) { // "fmt "
                fmtOk = b.getShort(at + 8).toInt() == 1 && b.getShort(at + 10).toInt() == 1 &&
                    b.getInt(at + 12) == 16000 && b.getShort(at + 22).toInt() == 16
            } else if (id == 0x61746164) { // "data"
                if (!fmtOk) return null
                val n = minOf(size, b.limit() - at - 8) / 2
                return ShortArray(n) { b.getShort(at + 8 + it * 2) }
            }
            at += 8 + size + (size and 1)
        }
        return null
    }

    /** The recorded sound, then silence, as a source for a dictation. */
    private class FileSound(private val samples: ShortArray, tailSeconds: Int) : PcmSource {
        private val total = samples.size + tailSeconds * 16000
        private var at = 0
        override fun start() = Unit
        override fun read(buffer: ShortArray): Int {
            if (at >= total) return -1
            val n = minOf(buffer.size, total - at)
            for (i in 0 until n) buffer[i] = if (at + i < samples.size) samples[at + i] else 0
            at += n
            return n
        }
        override fun release() = Unit
    }
}
