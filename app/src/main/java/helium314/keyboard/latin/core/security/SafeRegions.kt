package helium314.keyboard.latin.core.security

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.IBinder
import android.os.ParcelFileDescriptor
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Deep zoom on a picture from outside: [open] sends the file to
 * [DecoderService] once, which keeps it open in its isolated process; each
 * [Session.tile] then reads one rectangle of it at a sample size, so no
 * tile sends the file again. The shape is zoomimage's RegionDecoder, a
 * decoder per image that decodes a region at a sample size and is closed
 * (https://github.com/panpf/zoomimage,
 * zoomimage-core/src/commonMain/kotlin/com/github/panpf/zoomimage/subsampling/RegionDecoder.kt)
 * and Subsampling Scale Image View's ImageRegionDecoder (init gives the
 * size, decodeRegion(rect, sampleSize), recycle,
 * https://github.com/davemorrissey/subsampling-scale-image-view,
 * library/.../decoder/SkiaImageRegionDecoder.java); the app adapts this to
 * the zoom library it uses. Orientation stays the caller's: the pixels
 * and sizes are the file's own, before any EXIF turn.
 *
 * The decoder stays bound while a session is open (SafeImages' connection,
 * not a second one); at most two sessions live at once, a third open frees
 * the oldest, whose tiles then come back null. Close a session as soon as
 * its picture leaves the screen: it holds the file in the decoder.
 *
 * Shared across the apps of this base: edit Modules/shared, then run sync.sh.
 */
object SafeRegions {

    /**
     * An open picture. [width] and [height] are the file's, unturned.
     * Closed by [close], which the decoder dying also makes true in effect:
     * tiles then return null.
     */
    class Session internal constructor(
        private val app: Context,
        private val binder: IBinder,
        private val handle: Int,
        val width: Int,
        val height: Int,
    ) : AutoCloseable {

        private val closed = AtomicBoolean()

        /**
         * The pixels of [rect] (cut to the picture) read at 1 / [sampleSize]:
         * a power of two from 1 to 64 (BitmapFactory.Options.inSampleSize,
         * https://developer.android.com/reference/android/graphics/BitmapFactory.Options#inSampleSize,
         * as zoomimage's AndroidRegionDecoder.decodeRegion passes it). Null
         * when the rect is outside the picture, the sample size or the tile
         * is past the caps, the session is closed or lost, or the decoder
         * failed or took too long.
         */
        suspend fun tile(rect: Rect, sampleSize: Int): Bitmap? = withContext(Dispatchers.IO) {
            if (closed.get()) return@withContext null
            val r = DecoderLimits.clamp(rect.left, rect.top, rect.right, rect.bottom, width, height) ?: return@withContext null
            if (!DecoderLimits.tileFits(r[2] - r[0], r[3] - r[1], sampleSize)) return@withContext null
            tiling.withPermit {
                withTimeoutOrNull(TIMEOUT) {
                    val result = SafeImages.raw(app, DecoderService.DECODE_REGION, null, binder, {
                        writeInt(handle)
                        writeInt(r[0])
                        writeInt(r[1])
                        writeInt(r[2])
                        writeInt(r[3])
                        writeInt(sampleSize)
                    }) { reply, _ -> if (reply.readInt() == 1) reply.readFileDescriptor() else null } ?: return@withTimeoutOrNull null
                    runCatching {
                        DataInputStream(ParcelFileDescriptor.AutoCloseInputStream(result).buffered()).use { inp ->
                            val w = inp.readInt()
                            val h = inp.readInt()
                            require(w in 1..DecoderService.MAX_SIDE && h in 1..DecoderService.MAX_SIDE)
                            require(w.toLong() * h <= DecoderLimits.MAX_TILE_PIXELS)
                            val pixels = ByteArray(w * h * 4)
                            inp.readFully(pixels)
                            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { copyPixelsFromBuffer(ByteBuffer.wrap(pixels)) }
                        }
                    }.getOrNull()
                }
            }
        }

        /** Frees the picture in the decoder; the connection is let go 20 s later when nothing else holds it. Safe to call twice. */
        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            scope.launch {
                free(app, binder, handle)
                SafeImages.letGo(app)
            }
        }
    }

    /**
     * CLOSE_REGION for [handle] on [binder], as a region decoder is recycled
     * once its picture is done with (SkiaImageRegionDecoder.recycle in
     * https://github.com/davemorrissey/subsampling-scale-image-view,
     * library/.../decoder/SkiaImageRegionDecoder.java; zoomimage's
     * RegionDecoder.close). Nothing happens when that decoder is gone: its
     * pictures went with it.
     */
    private suspend fun free(app: Context, binder: IBinder, handle: Int) {
        runCatching {
            withTimeoutOrNull(TIMEOUT) {
                SafeImages.raw(app, DecoderService.CLOSE_REGION, null, binder, { writeInt(handle) }) { reply, _ -> reply.readInt() }
            }
        }
    }

    /**
     * Opens [uri] for tiles. Null for anything that is not a picture, a
     * format the platform cannot read by region (a GIF), a picture too
     * large (side past 30000) or too big a file, or a failure.
     */
    suspend fun open(context: Context, uri: Uri): Session? = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val input = runCatching { app.contentResolver.openInputStream(uri) }.getOrNull() ?: return@withContext null
        SafeImages.hold()
        var session: Session? = null
        try {
            session = input.use { stream ->
                // The file travels as large as the decoder reads it: held to the same two at a time as a decode.
                SafeImages.running.withPermit {
                    withTimeoutOrNull(TIMEOUT) {
                        SafeImages.raw(app, DecoderService.OPEN_REGION, stream, null, {}) { reply, binder ->
                            if (reply.readInt() != 1) return@raw null
                            val handle = reply.readInt()
                            val w = reply.readInt()
                            val h = reply.readInt()
                            // The decoder's numbers are checked here too: it is the part that reads hostile files.
                            if (handle > 0 && w in 1..DecoderService.MAX_DIMENSION && h in 1..DecoderService.MAX_DIMENSION) {
                                Session(app, binder, handle, w, h)
                            } else {
                                if (handle > 0) scope.launch { free(app, binder, handle) }
                                null
                            }
                        }
                    }
                }
            }
            session
        } finally {
            if (session == null) SafeImages.letGo(app)
        }
    }

    private val tiling = Semaphore(2)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private const val TIMEOUT = SafeImages.TIMEOUT
}
