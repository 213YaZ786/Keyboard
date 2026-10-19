// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.keyboard.clipboard

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.LruCache
import helium314.keyboard.latin.ClipboardHistoryEntry
import helium314.keyboard.latin.core.security.MediaCheck
import helium314.keyboard.latin.core.security.SafeImages
import helium314.keyboard.latin.database.ClipboardDao
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The thumbnails of copied pictures, in the history cards and the suggestion chip. A picture is never decoded in the
 * keyboard's process: its bytes must be a picture (Modules' safemedia kit, MediaCheck) and are then decoded by the kit's
 * isolated DecoderService at the size shown (SafeImages.decode); a file whose bytes are none stays a file.
 *
 * Sources: FlorisBoard shows an IMAGE item as a picture filling its card
 * (https://github.com/florisboard/florisboard/blob/main/app/src/main/kotlin/dev/patrickgold/florisboard/ime/clipboard/ClipboardInputLayout.kt,
 * ClipboardItem IMAGE), Apache-2.0; the work is off the main thread and a view that was recycled meanwhile is not
 * filled by the old job, as Android's guide "Process Bitmaps off the UI thread"
 * (https://developer.android.com/topic/performance/graphics/process-bitmap, Concurrency); the cache is Android's
 * LruCache sized in bytes (https://developer.android.com/topic/performance/graphics/cache-bitmap).
 */
object ClipThumbnails {
    private const val CACHE_BYTES = 8 * 1024 * 1024

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val cache = object : LruCache<String, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    // names of the stored files that turned out not to be pictures (a type that lies, an empty file, too large)
    private val notPictures: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private fun key(entry: ClipboardHistoryEntry, side: Int) = "${entry.filename}@$side"

    /** The thumbnail of [entry] when it is already decoded. */
    fun cached(entry: ClipboardHistoryEntry, side: Int): Bitmap? = cache.get(key(entry, side))

    /** True when [entry]'s file was found not to be a picture (shown as a file). */
    fun isFile(entry: ClipboardHistoryEntry) = entry.filename in notPictures

    /**
     * Decodes [entry]'s picture, its longest side at most [side] pixels, and gives it to [onDone] on the main thread;
     * null when the file is none (it is then remembered as a file). Cancel the returned job when the view is reused.
     */
    fun load(context: Context, entry: ClipboardHistoryEntry, side: Int, onDone: (Bitmap?) -> Unit): Job {
        val app = context.applicationContext
        return scope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                if (entry.mediaKind(app) != MediaCheck.Kind.IMAGE) null
                else runCatching {
                    File(ClipboardDao.clipFilesDir, entry.filename!!).inputStream().use { SafeImages.decode(app, it, side) }
                }.getOrNull()
            }
            if (bitmap != null) cache.put(key(entry, side), bitmap)
            else entry.filename?.let { notPictures.add(it) }
            onDone(bitmap)
        }
    }

    /** As [load] for the picture behind a clip's [uri] (the suggestion chip); [onDone] gets null for anything but a picture. */
    fun loadUri(context: Context, uri: Uri, side: Int, onDone: (Bitmap?) -> Unit): Job {
        val app = context.applicationContext
        return scope.launch {
            // SafeImages.decode reads the stream; the decoder service itself refuses bytes that are not a picture
            onDone(runCatching { SafeImages.decode(app, uri, side) }.getOrNull())
        }
    }
}
