package helium314.keyboard.latin.core.security

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.Gainmap
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import androidx.annotation.RequiresApi
import java.io.DataInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Pictures from outside, decoded by [DecoderService] in its isolated
 * process. Null for anything that is not a picture, too large, or when the
 * decoder took too long or died on it: the caller shows its placeholder.
 *
 * The decoder stays bound a little while after the last picture, so a
 * conversation or a feed full of them does not start a process for each.
 * [decodeHdr] adds the Ultra HDR gain map; [SafeRegions] reads tiles of a
 * picture kept open in the decoder, through this object's connection.
 *
 * Shared across the apps of this base: edit Modules/shared, then run sync.sh.
 */
object SafeImages {

    suspend fun decode(context: Context, uri: Uri, maxSide: Int = 2048): Bitmap? = withContext(Dispatchers.IO) {
        runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()?.use { decode(context, it, maxSide) }
    }

    suspend fun decode(context: Context, bytes: ByteArray, maxSide: Int = 1440): Bitmap? = withContext(Dispatchers.IO) {
        decode(context, bytes.inputStream(), maxSide)
    }

    suspend fun decode(context: Context, input: InputStream, maxSide: Int): Bitmap? = withContext(Dispatchers.IO) {
        // Two at a time: each can hold a large picture in the decoder's memory.
        running.withPermit {
            withTimeoutOrNull(TIMEOUT) { transact(context.applicationContext, input, maxSide) }
        }.also { releaseLater(context.applicationContext) }
    }

    /** An animated GIF's frames and each one's time on screen, in ms. */
    class Frames(val pictures: List<Bitmap>, val delays: IntArray)

    /**
     * Every frame of an animated GIF, decoded in [DecoderService] as
     * Glide's GifDrawable takes them from its GifDecoder
     * (https://github.com/bumptech/glide, load/resource/gif/GifFrameLoader.java);
     * null for a still picture, a GIF too large to hold, or a failure:
     * the caller shows [decode]'s still.
     */
    suspend fun frames(context: Context, uri: Uri, maxSide: Int = 720): Frames? = withContext(Dispatchers.IO) {
        runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()?.use { input ->
            running.withPermit {
                withTimeoutOrNull(TIMEOUT) {
                    val app = context.applicationContext
                    val result = call(app, DecoderService.DECODE_FRAMES, input, maxSide) ?: return@withTimeoutOrNull null
                    runCatching {
                        DataInputStream(ParcelFileDescriptor.AutoCloseInputStream(result).buffered()).use { inp ->
                            val count = inp.readInt()
                            val w = inp.readInt()
                            val h = inp.readInt()
                            require(count in 2..MAX_FRAMES && w in 1..DecoderService.MAX_SIDE && h in 1..DecoderService.MAX_SIDE)
                            require(w.toLong() * h * 4 * count <= DecoderService.MAX_FRAMES_BYTES)
                            val delays = IntArray(count)
                            val pictures = List(count) { i ->
                                delays[i] = inp.readInt().coerceIn(MIN_DELAY, MAX_DELAY)
                                val pixels = ByteArray(w * h * 4)
                                inp.readFully(pixels)
                                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { copyPixelsFromBuffer(ByteBuffer.wrap(pixels)) }
                            }
                            Frames(pictures, delays)
                        }
                    }.getOrNull()
                }
            }.also { releaseLater(context.applicationContext) }
        }
    }

    /**
     * A picture with its Ultra HDR gain map when it has one: the Bitmap is
     * rebuilt from the decoder's pixels, in the colour space it was decoded
     * in, and on API 34+ a Gainmap built from the decoder's pixels and
     * numbers is set on it (Bitmap.setGainmap, as ReFra puts the gain map back on
     * a tile, https://github.com/IacobIonut01/ReFra, HeifRegionDecoder.kt
     * attachTileGainmap and copyGainmapMetadata; Android's contract,
     * https://developer.android.com/media/grow/ultra-hdr/display). The app
     * parses no picture format: `hasGainmap()` on the result says whether
     * there is one. Without a gain map, below API 34, or if the gain map
     * fails its checks, the plain [decode] picture comes back. Null as [decode].
     */
    suspend fun decodeHdr(context: Context, uri: Uri, maxSide: Int = 2048): Bitmap? = withContext(Dispatchers.IO) {
        runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()?.use { input ->
            running.withPermit {
                withTimeoutOrNull(TIMEOUT) {
                    val app = context.applicationContext
                    val result = call(app, DecoderService.DECODE_HDR, input, maxSide) ?: return@withTimeoutOrNull null
                    runCatching {
                        DataInputStream(ParcelFileDescriptor.AutoCloseInputStream(result).buffered()).use { inp -> readHdr(inp) }
                    }.getOrNull()
                }
            }.also { releaseLater(context.applicationContext) }
        }
    }

    /** DECODE_HDR's reply (the format is in [DecoderService]'s header); every number the decoder sent is checked here. */
    private fun readHdr(inp: DataInputStream): Bitmap {
        val w = inp.readInt()
        val h = inp.readInt()
        require(w in 1..DecoderService.MAX_SIDE && h in 1..DecoderService.MAX_SIDE)
        val pixels = ByteArray(w * h * 4)
        inp.readFully(pixels)
        // ColorSpace.Named ordinals (https://developer.android.com/reference/android/graphics/ColorSpace#getId()): only an RGB one can hold pixels.
        val space = ColorSpace.Named.values().getOrNull(inp.readInt())?.let { ColorSpace.get(it) }?.takeIf { it.model == ColorSpace.Model.RGB }
        val bitmap = runCatching { Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888, true, space ?: ColorSpace.get(ColorSpace.Named.SRGB)) }
            .getOrElse { Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) }
            .apply { copyPixelsFromBuffer(ByteBuffer.wrap(pixels)) }
        if (inp.readInt() == 1 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // A gain map that fails its checks leaves the picture plain, not lost.
            runCatching { bitmap.gainmap = readGainmap(inp) }
        }
        return bitmap
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun readGainmap(inp: DataInputStream): Gainmap {
        val config = inp.readInt()
        val gw = inp.readInt()
        val gh = inp.readInt()
        require(config in 0..1 && DecoderLimits.gainmapFits(gw, gh))
        val bytes = ByteArray(gw * gh * (if (config == 0) 1 else 4))
        inp.readFully(bytes)
        fun floats() = FloatArray(3) { inp.readFloat() }
        val ratioMin = floats()
        val ratioMax = floats()
        val gamma = floats()
        val epsilonSdr = floats()
        val epsilonHdr = floats()
        val fullHdr = inp.readFloat()
        val minHdr = inp.readFloat()
        require(DecoderLimits.gainmapSane(ratioMin, ratioMax, gamma, epsilonSdr, epsilonHdr, fullHdr, minHdr))
        val extra = inp.readInt() == 1
        val direction = if (extra) inp.readInt() else 0
        val primaries = if (extra) inp.readInt() else -1
        val contents = Bitmap.createBitmap(gw, gh, if (config == 0) Bitmap.Config.ALPHA_8 else Bitmap.Config.ARGB_8888)
            .apply { copyPixelsFromBuffer(ByteBuffer.wrap(bytes)) }
        val map = Gainmap(contents)
        map.setRatioMin(ratioMin[0], ratioMin[1], ratioMin[2])
        map.setRatioMax(ratioMax[0], ratioMax[1], ratioMax[2])
        map.setGamma(gamma[0], gamma[1], gamma[2])
        map.setEpsilonSdr(epsilonSdr[0], epsilonSdr[1], epsilonSdr[2])
        map.setEpsilonHdr(epsilonHdr[0], epsilonHdr[1], epsilonHdr[2])
        map.displayRatioForFullHdr = fullHdr
        map.minDisplayRatioForHdrTransition = minHdr
        if (extra && Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) setGainmapExtra(map, direction, primaries)
        return map
    }

    /** The fields Android 16 added (Gainmap.setGainmapDirection, setAlternativeImagePrimaries); a value out of range is left at its default. */
    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private fun setGainmapExtra(map: Gainmap, direction: Int, primaries: Int) {
        if (direction == Gainmap.GAINMAP_DIRECTION_SDR_TO_HDR || direction == Gainmap.GAINMAP_DIRECTION_HDR_TO_SDR) map.gainmapDirection = direction
        ColorSpace.Named.values().getOrNull(primaries)?.let { ColorSpace.get(it) }?.takeIf { it.model == ColorSpace.Model.RGB }?.let { map.alternativeImagePrimaries = it }
    }

    private suspend fun transact(app: Context, input: InputStream, maxSide: Int): Bitmap? {
        val result = call(app, DecoderService.DECODE, input, maxSide) ?: return null
        return runCatching {
            DataInputStream(ParcelFileDescriptor.AutoCloseInputStream(result).buffered()).use { inp ->
                val w = inp.readInt()
                val h = inp.readInt()
                require(w in 1..DecoderService.MAX_SIDE && h in 1..DecoderService.MAX_SIDE)
                val pixels = ByteArray(w * h * 4)
                inp.readFully(pixels)
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { copyPixelsFromBuffer(ByteBuffer.wrap(pixels)) }
            }
        }.getOrNull()
    }

    /** One call to the decoder, as [transact] always made it: the reply's pipe, or null. */
    private suspend fun call(app: Context, code: Int, input: InputStream, maxSide: Int): ParcelFileDescriptor? =
        raw(app, code, input, null, { writeInt(maxSide.coerceIn(DecoderService.MIN_SIDE, DecoderService.MAX_SIDE)) }) { reply, _ ->
            if (reply.readInt() == 1) reply.readFileDescriptor() else null
        }

    /**
     * One transaction with the decoder, [call]'s body made general (the
     * Parcel and IBinder.transact contract,
     * https://developer.android.com/reference/android/os/IBinder#transact(int,%20android.os.Parcel,%20android.os.Parcel,%20int)):
     * the interface token, then [input] as a pipe when there is one, then
     * what [args] writes; [result] reads the reply and gets the binder it
     * came from. Null when the decoder is not there, or when [only] is
     * given and is no longer the binder (the decoder died since: a session
     * opened on it is gone).
     */
    internal suspend fun <T> raw(
        app: Context, code: Int, input: InputStream?, only: IBinder?,
        args: Parcel.() -> Unit, result: (Parcel, IBinder) -> T?,
    ): T? {
        if (only != null && !only.isBinderAlive) return null
        val decoder = binder(app) ?: return null
        if (only != null && decoder !== only) return null
        val pipe = input?.let { ParcelFileDescriptor.createPipe() }
        // Fed from here while the decoder reads it.
        if (input != null && pipe != null) {
            Thread {
                runCatching { ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { out -> input.copyTo(out) } }
            }.start()
        }
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DecoderService.DESCRIPTOR)
            if (pipe != null) data.writeFileDescriptor(pipe[0].fileDescriptor)
            data.args()
            val sent = runCatching { decoder.transact(code, data, reply, 0) }.getOrDefault(false)
            if (sent) result(reply, decoder) else null
        } finally {
            pipe?.get(0)?.close()
            data.recycle()
            reply.recycle()
        }
    }

    /**
     * Sessions open on the decoder (see [SafeRegions]): while there is one
     * the connection is kept, as a bound service lives while a client holds
     * its binding (https://developer.android.com/develop/background-work/services/bound-services),
     * and its pictures live in it. Counted before the open call, given back
     * by [letGo] when the session ends or fails to open.
     */
    private val holds = AtomicInteger()

    /** A session is about to open: keep the connection (see [holds]). */
    internal fun hold() {
        holds.incrementAndGet()
    }

    /** A session ended (see [holds]): the connection is let go 20 s later if nothing else holds it. */
    internal fun letGo(app: Context) {
        holds.decrementAndGet()
        releaseLater(app)
    }

    internal val running = Semaphore(2)
    private val lock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var bound: IBinder? = null
    private var connection: ServiceConnection? = null
    private var release: Job? = null

    private suspend fun binder(app: Context): IBinder? = lock.withLock {
        release?.cancel()
        bound?.takeIf { it.isBinderAlive }?.let { return@withLock it }
        connection?.let { runCatching { app.unbindService(it) } }
        connection = null
        bound = null
        suspendCancellableCoroutine<IBinder?> { cont ->
            val c = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    bound = service
                    if (cont.isActive) cont.resume(service)
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    bound = null
                }

                override fun onBindingDied(name: ComponentName?) {
                    bound = null
                    if (cont.isActive) cont.resume(null)
                }
            }
            connection = c
            val ok = runCatching { app.bindService(Intent(app, DecoderService::class.java), c, Context.BIND_AUTO_CREATE) }.getOrDefault(false)
            if (!ok && cont.isActive) cont.resume(null)
        }
    }

    private fun releaseLater(app: Context) {
        scope.launch {
            lock.withLock {
                release?.cancel()
                release = scope.launch {
                    delay(KEEP_BOUND)
                    lock.withLock {
                        // A picture open for deep zoom lives in the decoder: it stays bound until the session ends.
                        if (holds.get() > 0) return@withLock
                        connection?.let { runCatching { app.unbindService(it) } }
                        connection = null
                        bound = null
                    }
                }
            }
        }
    }

    internal const val TIMEOUT = 15_000L
    private const val MAX_FRAMES = 1000
    /** Glide's floor and the browsers' (GifHeaderParser MIN_FRAME_DELAY), and a frame never held past a minute. */
    private const val MIN_DELAY = 20
    private const val MAX_DELAY = 60_000
    private const val KEEP_BOUND = 20_000L
}
