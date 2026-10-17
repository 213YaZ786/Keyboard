// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.core.net

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.UnknownHostException
import java.security.MessageDigest

/** Why a download was not kept: what the user is told (see DictionaryRows). */
enum class DownloadFailure { OFFLINE, SERVER, TOO_LARGE, REFUSED }

class DownloadException(val failure: DownloadFailure) : IOException(failure.name)

/** A dictionary file of the repo: its folder and its name without ".dict", both checked by [DictionaryRepo.fromLink]. */
class RepoDictionary(val folder: String, val name: String) {
    val url: String get() = "${DictionaryRepo.BASE}$folder$name.dict"
}

/**
 * The dictionaries of https://codeberg.org/Helium314/aosp-dictionaries fetched on a tap, the list being the one of
 * HeliBoard's assets/dictionaries_in_dict_repo.txt (getKnownDictionariesForLocale). One host, HTTPS only, every
 * redirect read here and checked before it is followed, the body capped, the SHA-256 of what came compared with the
 * hashes the app knows before the file goes anywhere: an unknown file is deleted, never installed.
 *
 * Ported from the update kit's Updates.open / checked / get (Modules, shared/update/main/core/update/Updates.kt.tpl),
 * itself after Mushaf's Net.open, with
 * https://developer.android.com/reference/java/net/HttpURLConnection#setInstanceFollowRedirects(boolean),
 * https://developer.android.com/privacy-and-security/risks/cleartext and the size limit of Signal-Android's
 * OkHttpUtil.readAsBytes (https://github.com/signalapp/Signal-Android/blob/main/core-util-jvm/src/main/java/org/signal/core/util/OkHttpUtil.kt).
 */
object DictionaryRepo {

    const val HOST = "codeberg.org"
    const val BASE = "https://$HOST/Helium314/aosp-dictionaries/raw/branch/main/"
    /** The biggest dictionary of the repo is 17 MB. */
    const val MAX_BYTES = 20L * 1024 * 1024
    const val MAX_REDIRECTS = 3
    private const val TIMEOUT_MS = 15_000
    private val FOLDERS = listOf("dictionaries/", "emoji_cldr_signal_dictionaries/")
    /** main_fr, main_pt_br, main_en__experimental, emoji_es_419: letters, digits and underscores, nothing that walks a path. */
    private val NAME = Regex("[A-Za-z0-9_]{1,64}")

    /** The file a link of getKnownDictionariesForLocale points to, or null for anything that is not a dictionary of the repo's two folders. */
    fun fromLink(link: String): RepoDictionary? {
        if (!link.startsWith(BASE) || !link.endsWith(".dict")) return null
        val rest = link.removePrefix(BASE).removeSuffix(".dict")
        val folder = FOLDERS.firstOrNull { rest.startsWith(it) } ?: return null
        val name = rest.removePrefix(folder)
        return if (NAME.matches(name)) RepoDictionary(folder, name) else null
    }

    /** [url] when it is HTTPS to codeberg.org itself (no other host, no user part, no port), else refused. */
    fun checked(url: URL): URL {
        val plain = url.protocol == "https" && url.host.lowercase() == HOST && url.userInfo == null &&
            (url.port == -1 || url.port == 443)
        if (!plain) throw DownloadException(DownloadFailure.REFUSED)
        return url
    }

    /** The next address after a redirect: [location] resolved against [from], checked, at most [MAX_REDIRECTS] hops. */
    fun nextHop(from: URL, location: String?, hops: Int): URL {
        if (location.isNullOrBlank() || hops >= MAX_REDIRECTS) throw DownloadException(DownloadFailure.REFUSED)
        return checked(URL(from, location))
    }

    /** Whether [sha256] (hex, any case) is one of [known]. */
    fun accepts(sha256: String, known: Set<String>): Boolean = sha256.lowercase() in known

    /**
     * Copies [input] to [out] for at most [max] bytes, returns the SHA-256 (lower-case hex) of what was copied; one
     * byte more throws TOO_LARGE. [onChunk] gets the running total, [cancelled] is polled so a cancelled coroutine stops.
     */
    fun copyCapped(input: InputStream, out: OutputStream, max: Long, cancelled: () -> Unit = {}, onChunk: (Long) -> Unit = {}): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            cancelled()
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > max) throw DownloadException(DownloadFailure.TOO_LARGE)
            digest.update(buffer, 0, n)
            out.write(buffer, 0, n)
            onChunk(total)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Downloads [entry] into [into] and returns its SHA-256, after checking it against [known]; on any failure the
     * file is deleted and a [DownloadException] says why. [onProgress] gets 0..1 (0 while the size is unknown).
     */
    suspend fun download(entry: RepoDictionary, into: File, known: Set<String>, onProgress: (Float) -> Unit): String =
        withContext(Dispatchers.IO) {
            var kept = false
            try {
                val conn = open(URL(entry.url))
                try {
                    val total = conn.contentLengthLong
                    if (total > MAX_BYTES) throw DownloadException(DownloadFailure.TOO_LARGE)
                    val context = currentCoroutineContext()
                    val hash = conn.inputStream.use { input ->
                        into.outputStream().use { out ->
                            copyCapped(input, out, MAX_BYTES, { context.ensureActive() }) { read ->
                                onProgress(if (total > 0) read.toFloat() / total else 0f)
                            }
                        }
                    }
                    if (!accepts(hash, known)) throw DownloadException(DownloadFailure.REFUSED)
                    kept = true
                    hash
                } finally {
                    conn.disconnect()
                }
            } catch (e: DownloadException) {
                throw e
            } catch (e: UnknownHostException) {
                throw DownloadException(DownloadFailure.OFFLINE)
            } catch (e: IOException) {
                throw DownloadException(DownloadFailure.SERVER)
            } finally {
                if (!kept) into.delete()
            }
        }

    /** Opens [start] once it answers 200; redirects are never left to HttpURLConnection, each hop goes through [nextHop]. */
    private fun open(start: URL): HttpURLConnection {
        var url = checked(start)
        var hops = 0
        while (true) {
            val conn = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                // The bytes as they are in the repo: the size cap and the hash are on them, not on an unpacked copy.
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty("User-Agent", "Keyboard")
            }
            when (conn.responseCode) {
                200 -> return conn
                301, 302, 303, 307, 308 -> {
                    val location = conn.getHeaderField("Location")
                    conn.disconnect()
                    url = nextHop(url, location, hops++)
                }
                else -> {
                    conn.disconnect()
                    throw DownloadException(DownloadFailure.SERVER)
                }
            }
        }
    }

    /**
     * The hashes the app accepts: HeliBoard's known_dict_hashes.txt (main and add-on dictionaries) and the app's own
     * known_dict_hashes_keyboard.txt (what the repo holds besides: the emoji dictionaries and the versions newer than
     * HeliBoard's list), read once. Lines that are not 64 hex digits (comments, blanks) are skipped.
     */
    fun knownHashes(context: Context): Set<String> = synchronized(this) {
        known ?: listOf("known_dict_hashes.txt", "known_dict_hashes_keyboard.txt").flatMap { file ->
            context.assets.open(file).use { it.reader().readLines() }
        }.mapNotNull { parseHash(it) }.toHashSet().also { known = it }
    }

    @Volatile
    private var known: Set<String>? = null

    /** The hash a line of a hashes file holds, lower-case, or null for a comment, a blank or anything else. */
    fun parseHash(line: String): String? =
        line.substringBefore('#').trim().lowercase().takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
}
