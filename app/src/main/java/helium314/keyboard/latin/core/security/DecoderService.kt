package helium314.keyboard.latin.core.security

import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.ColorSpace
import android.graphics.Gainmap
import android.graphics.ImageDecoder
import android.graphics.Rect
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import androidx.annotation.RequiresApi
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

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
 * call, [DECODE_FRAMES], gives every frame of an animated GIF. A third,
 * [DECODE_HDR], is [DECODE]'s reply followed by the colour space and the
 * Ultra HDR gain map when the picture has one. Three more keep a picture
 * open for deep zoom, so a tile is read without sending the file again:
 * [OPEN_REGION], [DECODE_REGION], [CLOSE_REGION].
 *
 * Replies, all big endian (DataOutputStream), after the Binder reply's int 1
 * (0 = refused, nothing else follows):
 * - DECODE: pipe of `int width, int height, width*height*4` ARGB_8888 bytes.
 * - DECODE_HDR: the same, then `int colorSpaceId` (ColorSpace.Named ordinal,
 *   -1 = sRGB), then `int hasGainmap`; when 1: `int config` (0 ALPHA_8, 1
 *   ARGB_8888), `int gw, int gh, gw*gh*bytes`, 3 floats each of ratioMin,
 *   ratioMax, gamma, epsilonSdr, epsilonHdr, then displayRatioForFullHdr and
 *   minDisplayRatioForHdrTransition, then `int hasExtra` (API 36): when 1,
 *   `int gainmapDirection, int alternativeImagePrimariesId` (-1 = none).
 * - OPEN_REGION (in: the file's pipe): Binder reply `int handle, int width,
 *   int height` directly, no pipe.
 * - DECODE_REGION (in: handle, left, top, right, bottom, sampleSize): DECODE's pipe.
 * - CLOSE_REGION (in: handle): Binder reply int 1 when it was open.
 *
 * Shared across the apps of this base: edit Modules/shared, then run sync.sh.
 */
class DecoderService : Service() {

    /** The pictures kept open for deep zoom, at most [DecoderLimits.MAX_SESSIONS]; every access holds its own lock. */
    private val sessions = RegionSessions<BitmapRegionDecoder>(DecoderLimits.MAX_SESSIONS, Random.nextInt(1, 1 shl 24))

    private val binder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == OPEN_REGION || code == DECODE_REGION || code == CLOSE_REGION) return regionCall(code, data, reply)
            if (code != DECODE && code != DECODE_FRAMES && code != DECODE_HDR) return super.onTransact(code, data, reply, flags)
            data.enforceInterface(DESCRIPTOR)
            val input = data.readFileDescriptor()
            val maxSide = data.readInt()
            val output = input?.let {
                runCatching {
                    when (code) {
                        DECODE -> decode(it, maxSide)
                        DECODE_HDR -> decodeHdr(it, maxSide)
                        else -> frames(it, maxSide)
                    }
                }.getOrNull()
            }
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

    /** Every picture kept open is freed when the last client lets go of the service, or the service ends. */
    override fun onUnbind(intent: Intent?): Boolean {
        closeAllRegions()
        return false
    }

    override fun onDestroy() {
        closeAllRegions()
        super.onDestroy()
    }

    private fun decode(input: ParcelFileDescriptor, maxSide: Int): ParcelFileDescriptor? {
        val bytes = read(input) ?: return null
        // Only the formats a picture comes in; anything else is not decoded at all.
        if (MediaCheck.kind(bytes) != MediaCheck.Kind.IMAGE) return null
        val side = maxSide.coerceIn(MIN_SIDE, MAX_SIDE)
        val bitmap = argb(decodePicture(bytes, side))
        val pipe = ParcelFileDescriptor.createPipe()
        Thread {
            runCatching {
                DataOutputStream(ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).buffered()).use { out ->
                    writePixels(out, bitmap)
                }
            }
            bitmap.recycle()
        }.start()
        return pipe[0]
    }

    /** The picture as the platform's ImageDecoder gives it, software pixels, at most [side] on its longest side. */
    private fun decodePicture(bytes: ByteArray, side: Int): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val w = info.size.width
            val h = info.size.height
            require(w in 1..MAX_DIMENSION && h in 1..MAX_DIMENSION)
            val scale = min(1f, side.toFloat() / max(w, h))
            if (scale < 1f) decoder.setTargetSize((w * scale).roundToInt().coerceAtLeast(1), (h * scale).roundToInt().coerceAtLeast(1))
        }

    private fun argb(decoded: Bitmap): Bitmap =
        if (decoded.config == Bitmap.Config.ARGB_8888) decoded else decoded.copy(Bitmap.Config.ARGB_8888, false)

    /** DECODE's reply body: width, height, the pixels as the bitmap holds them. */
    private fun writePixels(out: DataOutputStream, bitmap: Bitmap) {
        out.writeInt(bitmap.width)
        out.writeInt(bitmap.height)
        val pixels = ByteBuffer.allocate(bitmap.byteCount)
        bitmap.copyPixelsToBuffer(pixels)
        out.write(pixels.array())
    }

    /** A gain map ready to leave: its metadata and its pixels as ALPHA_8 (0) or ARGB_8888 (1). */
    private class Hdr(val map: Gainmap, val pixels: Bitmap, val config: Int)

    /**
     * DECODE, then the Ultra HDR gain map the decoded Bitmap carries. The
     * gain map is read off the Bitmap as ReFra does for its tiles
     * (https://github.com/IacobIonut01/ReFra, HeifRegionDecoder.kt
     * loadSourceGainmap and copyGainmapMetadata: Bitmap.hasGainmap, then
     * gainmapContents and every Gainmap field), and as Android documents it
     * (https://developer.android.com/media/grow/ultra-hdr/display). It is
     * read before the copy to ARGB_8888, which is not asked to keep it.
     * Below API 34, or without a gain map, or with one past the caps, the
     * flag is 0 and the picture is the plain DECODE.
     */
    private fun decodeHdr(input: ParcelFileDescriptor, maxSide: Int): ParcelFileDescriptor? {
        val bytes = read(input) ?: return null
        if (MediaCheck.kind(bytes) != MediaCheck.Kind.IMAGE) return null
        val raw = decodePicture(bytes, maxSide.coerceIn(MIN_SIDE, MAX_SIDE))
        val hdr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) runCatching { hdrOf(raw) }.getOrNull() else null
        val bitmap = argb(raw)
        // ColorSpace.getId (https://developer.android.com/reference/android/graphics/ColorSpace#getId()): the Named ordinal, -1 for any other.
        val colorSpaceId = bitmap.colorSpace?.id ?: -1
        val pipe = ParcelFileDescriptor.createPipe()
        Thread {
            runCatching {
                DataOutputStream(ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).buffered()).use { out ->
                    writePixels(out, bitmap)
                    out.writeInt(colorSpaceId)
                    if (hdr == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) out.writeInt(0) else writeHdr(out, hdr)
                }
            }
            bitmap.recycle()
        }.start()
        return pipe[0]
    }

    /** The gain map of [raw] when it has one that fits the caps and whose numbers make sense, else null. */
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun hdrOf(raw: Bitmap): Hdr? {
        if (!raw.hasGainmap()) return null
        val map = raw.gainmap ?: return null
        val contents = map.gainmapContents
        if (!DecoderLimits.gainmapFits(contents.width, contents.height)) return null
        if (!DecoderLimits.gainmapSane(map.ratioMin, map.ratioMax, map.gamma, map.epsilonSdr, map.epsilonHdr, map.displayRatioForFullHdr, map.minDisplayRatioForHdrTransition)) return null
        val config = if (contents.config == Bitmap.Config.ALPHA_8) 0 else 1
        val pixels = when (contents.config) {
            Bitmap.Config.ALPHA_8, Bitmap.Config.ARGB_8888 -> contents
            else -> contents.copy(Bitmap.Config.ARGB_8888, false) ?: return null
        }
        // The client reads width * height * bytes exactly: rows must be packed.
        if (pixels.rowBytes != pixels.width * (if (config == 0) 1 else 4)) return null
        return Hdr(map, pixels, config)
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun writeHdr(out: DataOutputStream, hdr: Hdr) {
        val map = hdr.map
        out.writeInt(1)
        out.writeInt(hdr.config)
        out.writeInt(hdr.pixels.width)
        out.writeInt(hdr.pixels.height)
        val pixels = ByteBuffer.allocate(hdr.pixels.byteCount)
        hdr.pixels.copyPixelsToBuffer(pixels)
        out.write(pixels.array())
        for (values in arrayOf(map.ratioMin, map.ratioMax, map.gamma, map.epsilonSdr, map.epsilonHdr)) {
            for (i in 0 until 3) out.writeFloat(values[i])
        }
        out.writeFloat(map.displayRatioForFullHdr)
        out.writeFloat(map.minDisplayRatioForHdrTransition)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            out.writeInt(1)
            writeHdrExtra(out, map)
        } else {
            out.writeInt(0)
        }
    }

    /** The fields Android 16 added: the direction the map is applied in and the colour space its maths runs in. */
    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private fun writeHdrExtra(out: DataOutputStream, map: Gainmap) {
        out.writeInt(map.gainmapDirection)
        out.writeInt(map.alternativeImagePrimaries?.id ?: -1)
    }

    /** The calls of the deep zoom sessions: open a picture, read a tile of it, close it. */
    private fun regionCall(code: Int, data: Parcel, reply: Parcel?): Boolean {
        data.enforceInterface(DESCRIPTOR)
        when (code) {
            OPEN_REGION -> {
                val input = data.readFileDescriptor()
                val opened = input?.let { runCatching { openRegion(it) }.getOrNull() }
                if (reply != null) {
                    if (opened == null) {
                        reply.writeInt(0)
                    } else {
                        reply.writeInt(1)
                        opened.forEach { reply.writeInt(it) }
                    }
                }
            }
            DECODE_REGION -> {
                val handle = data.readInt()
                val left = data.readInt()
                val top = data.readInt()
                val right = data.readInt()
                val bottom = data.readInt()
                val sampleSize = data.readInt()
                val output = runCatching { decodeRegion(handle, left, top, right, bottom, sampleSize) }.getOrNull()
                if (reply != null) {
                    if (output == null) {
                        reply.writeInt(0)
                    } else {
                        reply.writeInt(1)
                        reply.writeFileDescriptor(output.fileDescriptor)
                    }
                }
                output?.close()
            }
            else -> {
                val handle = data.readInt()
                val closed = runCatching { closeRegion(handle) }.getOrDefault(false)
                reply?.writeInt(if (closed) 1 else 0)
            }
        }
        return true
    }

    /**
     * A picture opened for tiles: the file is read as decode() reads it, its
     * type checked, and BitmapRegionDecoder made on the bytes, as zoomimage's
     * AndroidRegionDecoder.prepare does
     * (https://github.com/panpf/zoomimage, zoomimage-core/src/androidMain/.../subsampling/internal/AndroidRegionDecoder.kt:
     * the stream-less newInstance on API 31+, the isSharable one below) and
     * Subsampling Scale Image View's SkiaImageRegionDecoder.init reads the
     * size (https://github.com/davemorrissey/subsampling-scale-image-view,
     * library/.../decoder/SkiaImageRegionDecoder.java). Out: handle, width,
     * height; null for anything not a picture, past [MAX_DIMENSION], or a
     * format BitmapRegionDecoder cannot do. A third open session frees the
     * oldest ([RegionSessions]).
     */
    private fun openRegion(input: ParcelFileDescriptor): IntArray? {
        val bytes = read(input) ?: return null
        if (MediaCheck.kind(bytes) != MediaCheck.Kind.IMAGE) return null
        val decoder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            BitmapRegionDecoder.newInstance(bytes, 0, bytes.size)
        } else {
            @Suppress("DEPRECATION")
            BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false)
        }
        val w = decoder.width
        val h = decoder.height
        if (w !in 1..MAX_DIMENSION || h !in 1..MAX_DIMENSION) {
            decoder.recycle()
            return null
        }
        val handle = synchronized(sessions) {
            val (handle, evicted) = sessions.add(decoder)
            evicted.forEach { it.recycle() }
            handle
        }
        return intArrayOf(handle, w, h)
    }

    /**
     * One tile of an open picture: the rect clamped to the image
     * ([DecoderLimits.clamp], as viewToFileRect of Subsampling Scale Image
     * View clamps it, https://github.com/davemorrissey/subsampling-scale-image-view,
     * SubsamplingScaleImageView.java), BitmapFactory.Options.inSampleSize a
     * power of two and the preferred config set, a null bitmap an error, as
     * its SkiaImageRegionDecoder.decodeRegion and zoomimage's
     * AndroidRegionDecoder.decodeRegion do. Decoding holds the sessions'
     * lock, so a session is never recycled under a tile (what SSIV's
     * ReadWriteLock is for). The reply is DECODE's: width, height, ARGB pixels.
     * EXIF orientation is left to the caller.
     */
    private fun decodeRegion(handle: Int, left: Int, top: Int, right: Int, bottom: Int, sampleSize: Int): ParcelFileDescriptor? {
        if (!DecoderLimits.isSampleSize(sampleSize)) return null
        val decoded = synchronized(sessions) {
            val decoder = sessions[handle] ?: return null
            val rect = DecoderLimits.clamp(left, top, right, bottom, decoder.width, decoder.height) ?: return null
            if (!DecoderLimits.tileFits(rect[2] - rect[0], rect[3] - rect[1], sampleSize)) return null
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            decoder.decodeRegion(Rect(rect[0], rect[1], rect[2], rect[3]), options) ?: return null
        }
        val bitmap = argb(decoded)
        if (bitmap.width !in 1..MAX_SIDE || bitmap.height !in 1..MAX_SIDE || bitmap.width.toLong() * bitmap.height > DecoderLimits.MAX_TILE_PIXELS) {
            bitmap.recycle()
            return null
        }
        val pipe = ParcelFileDescriptor.createPipe()
        Thread {
            runCatching {
                DataOutputStream(ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).buffered()).use { out ->
                    writePixels(out, bitmap)
                }
            }
            bitmap.recycle()
        }.start()
        return pipe[0]
    }

    /** Frees one open picture; false when the handle is not open (https://github.com/davemorrissey/subsampling-scale-image-view, SkiaImageRegionDecoder.recycle). */
    private fun closeRegion(handle: Int): Boolean = synchronized(sessions) {
        val decoder = sessions.remove(handle) ?: return false
        decoder.recycle()
        true
    }

    private fun closeAllRegions() {
        synchronized(sessions) { sessions.clear().forEach { runCatching { it.recycle() } } }
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
        internal const val DECODE_HDR = IBinder.FIRST_CALL_TRANSACTION + 2
        internal const val OPEN_REGION = IBinder.FIRST_CALL_TRANSACTION + 3
        internal const val DECODE_REGION = IBinder.FIRST_CALL_TRANSACTION + 4
        internal const val CLOSE_REGION = IBinder.FIRST_CALL_TRANSACTION + 5
        /** All of an animation's frames together, as the app holds them. */
        internal const val MAX_FRAMES_BYTES = 32L * 1024 * 1024
        internal const val MIN_SIDE = 16
        internal const val MAX_SIDE = 4096
        private const val MAX_INPUT = 40 * 1024 * 1024
        /** Larger than any camera makes; a bigger header is a trap. */
        internal const val MAX_DIMENSION = 30_000
    }
}

/**
 * The numbers the decoder and its clients agree on, kept apart from Android
 * so they are tested on the JVM: what a tile may be, what a gain map may be.
 */
internal object DecoderLimits {
    /** Pictures kept open at once; a third open frees the oldest. */
    const val MAX_SESSIONS = 2
    /** BitmapFactory.Options.inSampleSize is a power of two (https://developer.android.com/reference/android/graphics/BitmapFactory.Options#inSampleSize); 64 is far past what a zoom needs. */
    const val MAX_SAMPLE = 64
    /** The most pixels of one tile: half the main picture's cap (MAX_SIDE squared). */
    const val MAX_TILE_PIXELS = 4096L * 2048
    private const val MAX_RATIO = 10_000f

    /**
     * [left, top, right, bottom] cut to the image as Subsampling Scale Image
     * View's viewToFileRect does (https://github.com/davemorrissey/subsampling-scale-image-view,
     * SubsamplingScaleImageView.java: max with 0, min with the size); null
     * when nothing of the rect is inside the image.
     */
    fun clamp(left: Int, top: Int, right: Int, bottom: Int, width: Int, height: Int): IntArray? {
        val l = max(0, left)
        val t = max(0, top)
        val r = min(width, right)
        val b = min(height, bottom)
        return if (l < r && t < b) intArrayOf(l, t, r, b) else null
    }

    /**
     * Whether [sample] is an inSampleSize this service decodes: a power of
     * two, as Subsampling Scale Image View's calculateInSampleSize rounds it
     * (https://github.com/davemorrissey/subsampling-scale-image-view,
     * SubsamplingScaleImageView.java), from 1 to [MAX_SAMPLE].
     */
    fun isSampleSize(sample: Int): Boolean = sample in 1..MAX_SAMPLE && sample and (sample - 1) == 0

    /** Pixels a side of [side] gives at [sample]: rounded up, so a limit checked on it is never short (BitmapFactory.Options.inSampleSize, https://developer.android.com/reference/android/graphics/BitmapFactory.Options#inSampleSize). */
    fun sampledSide(side: Int, sample: Int): Int = ((side.toLong() + sample - 1) / sample).toInt().coerceAtLeast(1)

    /** Whether a tile of [width] x [height] source pixels at [sample] stays within a picture's side and [MAX_TILE_PIXELS]. */
    fun tileFits(width: Int, height: Int, sample: Int): Boolean {
        if (width < 1 || height < 1 || !isSampleSize(sample)) return false
        val w = sampledSide(width, sample)
        val h = sampledSide(height, sample)
        return w <= DecoderService.MAX_SIDE && h <= DecoderService.MAX_SIDE && w.toLong() * h <= MAX_TILE_PIXELS
    }

    /** A gain map is capped like the main picture: each side from 1 to MAX_SIDE. */
    fun gainmapFits(width: Int, height: Int): Boolean =
        width in 1..DecoderService.MAX_SIDE && height in 1..DecoderService.MAX_SIDE

    /**
     * The numbers of a Gainmap (https://developer.android.com/reference/android/graphics/Gainmap)
     * are finite and in a range any real gain map is far inside: ratios,
     * gamma and display ratios above 0, epsilons from 0 to 1. A number
     * crafted outside it is no gain map.
     */
    fun gainmapSane(
        ratioMin: FloatArray, ratioMax: FloatArray, gamma: FloatArray, epsilonSdr: FloatArray, epsilonHdr: FloatArray,
        displayRatioForFullHdr: Float, minDisplayRatioForHdrTransition: Float,
    ): Boolean =
        listOf(ratioMin, ratioMax, gamma).all { it.size == 3 && it.all { v -> v.isFinite() && v > 0f && v <= MAX_RATIO } } &&
            listOf(epsilonSdr, epsilonHdr).all { it.size == 3 && it.all { v -> v.isFinite() && v >= 0f && v <= 1f } } &&
            listOf(displayRatioForFullHdr, minDisplayRatioForHdrTransition).all { it.isFinite() && it > 0f && it <= MAX_RATIO }
}

/**
 * The open pictures by handle, oldest first: adding one past [max] hands
 * back the oldest to free. Handles start from a random number, so a handle
 * of a service that died is not the handle of another picture in its
 * successor. Not thread safe: the service holds its own lock around it.
 * Kept as zoomimage keeps a decoder per image and frees it with close()
 * (https://github.com/panpf/zoomimage, RegionDecoder.kt).
 */
internal class RegionSessions<T : Any>(private val max: Int, first: Int) {
    private val open = LinkedHashMap<Int, T>()
    private var next = first.coerceAtLeast(1)

    /** The handle of [value] and the sessions pushed out to make room, to be freed. */
    fun add(value: T): Pair<Int, List<T>> {
        val evicted = ArrayList<T>()
        while (open.size >= max) {
            val oldest = open.keys.first()
            evicted += open.remove(oldest)!!
        }
        val handle = next
        next = if (next == Int.MAX_VALUE) 1 else next + 1
        open[handle] = value
        return handle to evicted
    }

    operator fun get(handle: Int): T? = open[handle]

    fun remove(handle: Int): T? = open.remove(handle)

    /** Every session, to be freed. */
    fun clear(): List<T> = open.values.toList().also { open.clear() }

    val size: Int get() = open.size
}
