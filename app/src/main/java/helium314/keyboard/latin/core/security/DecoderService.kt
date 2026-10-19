package helium314.keyboard.latin.core.security

import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Decodes pictures that come from outside (a message's photo, a picture
 * from the web, a file chosen on the phone) in a process of its own with
 * no permission, no files and no network: a picture crafted to attack the
 * image decoder can reach nothing of the user's. What comes back is plain
 * pixels, which the app takes without parsing any picture format.
 *
 * Declared by each app taking it, never exported:
 * `<service android:name=".core.security.DecoderService" android:exported="false"
 * android:isolatedProcess="true" android:process=":decoder" />`
 *
 * One call over a plain Binder (no AIDL, so no build feature to switch on):
 * in, the picture as a pipe and the largest side wanted; out, a pipe that
 * gives the width, the height and the ARGB pixels, or nothing. A second
 * call, [DECODE_FRAMES], gives every frame of an animated GIF.
 *
 * Shared across the apps of this base: edit Modules/shared, then run sync.sh.
 */
class DecoderService : Service() {

    private val binder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != DECODE && code != DECODE_FRAMES) return super.onTransact(code, data, reply, flags)
            data.enforceInterface(DESCRIPTOR)
            val input = data.readFileDescriptor()
            val maxSide = data.readInt()
            val output = input?.let { runCatching { if (code == DECODE) decode(it, maxSide) else frames(it, maxSide) }.getOrNull() }
            if (reply != null) {
                if (output == null) {
                    reply.writeInt(0)
                } else {
                    reply.writeInt(1)
                    reply.writeFileDescriptor(output.fileDescriptor)
                }
            }
            output?.close()
            return true
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun decode(input: ParcelFileDescriptor, maxSide: Int): ParcelFileDescriptor? {
        val bytes = read(input) ?: return null
        // Only the formats a picture comes in; anything else is not decoded at all.
        if (MediaCheck.kind(bytes) != MediaCheck.Kind.IMAGE) return null
        val side = maxSide.coerceIn(MIN_SIDE, MAX_SIDE)
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val w = info.size.width
            val h = info.size.height
            require(w in 1..MAX_DIMENSION && h in 1..MAX_DIMENSION)
            val scale = min(1f, side.toFloat() / max(w, h))
            if (scale < 1f) decoder.setTargetSize((w * scale).roundToInt().coerceAtLeast(1), (h * scale).roundToInt().coerceAtLeast(1))
        }.let { decoded -> if (decoded.config == Bitmap.Config.ARGB_8888) decoded else decoded.copy(Bitmap.Config.ARGB_8888, false) }
        val pipe = ParcelFileDescriptor.createPipe()
        Thread {
            runCatching {
                DataOutputStream(ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).buffered()).use { out ->
                    out.writeInt(bitmap.width)
                    out.writeInt(bitmap.height)
                    val pixels = ByteBuffer.allocate(bitmap.byteCount)
                    bitmap.copyPixelsToBuffer(pixels)
                    out.write(pixels.array())
                }
            }
            bitmap.recycle()
        }.start()
        return pipe[0]
    }

    /**
     * The frames of an animated GIF, decoded here by Glide's pure Java
     * StandardGifDecoder (https://github.com/bumptech/glide,
     * third_party/gif_decoder), set up as Glide's
     * library/.../load/resource/gif/ByteBufferGifDecoder.java decode() and
     * getSampleSize() do: header parsed, a power of two sample size for the
     * side wanted, ARGB_8888. Out: the count, the width, the height, then
     * each frame's delay in ms and its pixels. All frames must fit
     * [MAX_FRAMES_BYTES], the sample size grown up to 8 to fit; else
     * nothing, and the caller shows the still picture.
     */
    private fun frames(input: ParcelFileDescriptor, maxSide: Int): ParcelFileDescriptor? {
        val bytes = read(input) ?: return null
        if (MediaCheck.kind(bytes) != MediaCheck.Kind.IMAGE) return null
        val header = com.bumptech.glide.gifdecoder.GifHeaderParser().setData(bytes).parseHeader()
        if (header.status != com.bumptech.glide.gifdecoder.GifDecoder.STATUS_OK || header.numFrames < 2) return null
        if (header.width !in 1..MAX_DIMENSION || header.height !in 1..MAX_DIMENSION) return null
        val side = maxSide.coerceIn(MIN_SIDE, MAX_SIDE)
        val exact = max(header.width, header.height) / side
        var sample = if (exact == 0) 1 else Integer.highestOneBit(exact)
        // The memory all frames take at a sample size.
        fun frameBytes(s: Int) = (header.width / s).toLong() * (header.height / s) * 4
        while (frameBytes(sample) * header.numFrames > MAX_FRAMES_BYTES && sample < 8) sample *= 2
        if (frameBytes(sample) * header.numFrames > MAX_FRAMES_BYTES) return null
        // Glide's GifBitmapProvider (https://github.com/bumptech/glide, load/resource/gif/GifBitmapProvider.java)
        // without its pools: each frame is copied out at once.
        val provider = object : com.bumptech.glide.gifdecoder.GifDecoder.BitmapProvider {
            override fun obtain(width: Int, height: Int, config: Bitmap.Config): Bitmap = Bitmap.createBitmap(width, height, config)
            override fun release(bitmap: Bitmap) = bitmap.recycle()
            override fun obtainByteArray(size: Int) = ByteArray(size)
            override fun release(bytes: ByteArray) = Unit
            override fun obtainIntArray(size: Int) = IntArray(size)
            override fun release(array: IntArray) = Unit
        }
        val gif = com.bumptech.glide.gifdecoder.StandardGifDecoder(provider, header, ByteBuffer.wrap(bytes), sample)
        gif.setDefaultBitmapConfig(Bitmap.Config.ARGB_8888)
        val pipe = ParcelFileDescriptor.createPipe()
        Thread {
            runCatching {
                DataOutputStream(ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).buffered()).use { out ->
                    out.writeInt(gif.frameCount)
                    out.writeInt(gif.width)
                    out.writeInt(gif.height)
                    repeat(gif.frameCount) {
                        gif.advance()
                        val frame = gif.nextFrame ?: Bitmap.createBitmap(gif.width, gif.height, Bitmap.Config.ARGB_8888)
                        out.writeInt(gif.getDelay(gif.currentFrameIndex))
                        val pixels = ByteBuffer.allocate(gif.width * gif.height * 4)
                        frame.copyPixelsToBuffer(pixels)
                        out.write(pixels.array())
                    }
                }
            }
            gif.clear()
        }.start()
        return pipe[0]
    }

    /** The whole input, or null past [MAX_INPUT] (the read decode() always did, https://developer.android.com/reference/android/os/ParcelFileDescriptor.AutoCloseInputStream). */
    private fun read(input: ParcelFileDescriptor): ByteArray? =
        ParcelFileDescriptor.AutoCloseInputStream(input).use { stream ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            var total = 0
            while (true) {
                val n = stream.read(buffer)
                if (n < 0) break
                total += n
                if (total > MAX_INPUT) return null
                out.write(buffer, 0, n)
            }
            out.toByteArray()
        }

    companion object {
        internal const val DESCRIPTOR = "helium314.keyboard.latin.core.security.Decoder"
        internal const val DECODE = IBinder.FIRST_CALL_TRANSACTION
        internal const val DECODE_FRAMES = IBinder.FIRST_CALL_TRANSACTION + 1
        /** All of an animation's frames together, as the app holds them. */
        internal const val MAX_FRAMES_BYTES = 32L * 1024 * 1024
        internal const val MIN_SIDE = 16
        internal const val MAX_SIDE = 4096
        private const val MAX_INPUT = 40 * 1024 * 1024
        /** Larger than any camera makes; a bigger header is a trap. */
        private const val MAX_DIMENSION = 30_000
    }
}
