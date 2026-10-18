package helium314.keyboard.latin.core.voice

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder

/**
 * A recorded sound (a voice message, a shared audio file) as 16 kHz mono samples between -1 and 1, read by Android's
 * own extractor and decoders (they run in the system's sandboxed media processes, never a decoder of the app's).
 * Moved from SMS's core/voice/Transcriber.kt (decode, FloatCollector, Resampler), which follows MediaCodec's
 * "synchronous processing using buffers" https://developer.android.com/reference/android/media/MediaCodec
 * and MediaExtractor's track selection https://developer.android.com/reference/android/media/MediaExtractor
 */
object AudioDecode {

    /** The first audio track of [uri], at most [maxSeconds]; empty when there is none. Throws when it cannot be read. */
    fun decode(context: Context, uri: Uri, maxSeconds: Int = 600): FloatArray {
        val extractor = MediaExtractor()
        val out = FloatCollector()
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return FloatArray(0)
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            val resampler = Resampler()
            try {
                while (true) {
                    if (!inputDone) {
                        val i = codec.dequeueInputBuffer(10_000)
                        if (i >= 0) {
                            val n = extractor.readSampleData(codec.getInputBuffer(i)!!, 0)
                            if (n < 0) {
                                codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(i, 0, n, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val o = codec.dequeueOutputBuffer(info, 10_000)
                    when {
                        o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            rate = codec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }
                        o >= 0 -> {
                            val buffer = codec.getOutputBuffer(o)!!.order(ByteOrder.LITTLE_ENDIAN)
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            val shorts = ShortArray(info.size / 2).also { buffer.asShortBuffer().get(it) }
                            codec.releaseOutputBuffer(o, false)
                            resampler.push(shorts, channels, rate, out)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                            if (out.size > Dictation.SAMPLE_RATE * maxSeconds) break
                        }
                    }
                }
            } finally {
                runCatching { codec.stop() }
                codec.release()
            }
        } finally {
            extractor.release()
        }
        return out.toArray()
    }

    /** Samples gathered in a growing array (SMS's Transcriber.FloatCollector). */
    private class FloatCollector {
        private var data = FloatArray(Dictation.SAMPLE_RATE * 30)
        var size = 0
            private set
        fun add(v: Float) {
            if (size == data.size) data = data.copyOf(data.size * 2)
            data[size++] = v
        }
        fun toArray() = data.copyOf(size)
    }

    /** Mixes down to mono and brings any rate to 16 kHz, across buffers (SMS's Transcriber.Resampler). */
    private class Resampler {
        private var position = 0.0
        private var last = 0f
        fun push(samples: ShortArray, channels: Int, rate: Int, out: FloatCollector) {
            val ch = channels.coerceAtLeast(1)
            val frames = samples.size / ch
            if (frames == 0) return
            val mono = FloatArray(frames) { f -> var sum = 0f; for (c in 0 until ch) sum += samples[f * ch + c]; sum / ch / 32768f }
            val step = rate.toDouble() / Dictation.SAMPLE_RATE
            // Position runs in input frames; -1 is the last frame of the buffer before.
            while (position < frames - 1) {
                val i = kotlin.math.floor(position).toInt()
                val t = (position - i).toFloat()
                val a = if (i < 0) last else mono[i]
                val b = mono[i + 1]
                out.add(a + (b - a) * t)
                position += step
            }
            position -= frames
            last = mono[frames - 1]
        }
    }
}

/**
 * Decoded samples handed to a Dictation in place of the microphone, then -1: the end of the sound writes out what
 * is held (PcmSource's contract). As Keyboard's DictationTestReceiver plays a file into the engine (FileSound).
 */
class SamplesSource(private val samples: FloatArray) : PcmSource {
    private var at = 0
    override fun start() = Unit
    override fun read(buffer: ShortArray): Int {
        if (at >= samples.size) return -1
        val n = minOf(buffer.size, samples.size - at)
        for (k in 0 until n) buffer[k] = (samples[at + k] * 32767f).toInt().coerceIn(-32768, 32767).toShort()
        at += n
        return n
    }
    override fun release() = Unit
}
