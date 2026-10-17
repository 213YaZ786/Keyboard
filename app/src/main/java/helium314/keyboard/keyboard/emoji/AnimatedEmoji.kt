// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard.emoji

import android.content.ClipDescription
import android.content.Context
import android.content.ContextWrapper
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.inputmethodservice.InputMethodService
import android.view.inputmethod.EditorInfo
import androidx.core.content.FileProvider
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.Log
import java.io.File
import java.io.IOException

/**
 * Noto Animated Emoji (Google, CC BY 4.0, https://googlefonts.github.io/noto-emoji-animation/) bundled as 160 px GIFs
 * in assets/animated, named by the emoji's code points as the files of that site are.
 */
object AnimatedEmoji {
    private val TAG = AnimatedEmoji::class.java.simpleName
    private const val ASSETS_FOLDER = "animated"
    private const val MIME_GIF = "image/gif"

    /** "263a_fe0f.gif" for the emoji U+263A U+FE0F, the file name of https://fonts.gstatic.com/s/e/notoemoji/latest/263a_fe0f/512.gif */
    @JvmStatic
    fun fileName(emoji: String): String {
        val codes = ArrayList<String>()
        var i = 0
        while (i < emoji.length) {
            val cp = emoji.codePointAt(i)
            codes.add(Integer.toHexString(cp))
            i += Character.charCount(cp)
        }
        return codes.joinToString("_") + ".gif"
    }

    /**
     * The animation of [emoji] from the APK's own assets, repeating, not started (the caller starts it while on screen).
     * ImageDecoder, https://developer.android.com/reference/android/graphics/ImageDecoder#createSource(android.content.res.AssetManager,%20java.lang.String)
     * and AnimatedImageDrawable, https://developer.android.com/reference/android/graphics/drawable/AnimatedImageDrawable;
     * only files shipped in the APK are decoded here, never one from outside.
     */
    @JvmStatic
    fun load(context: Context, emoji: String): AnimatedImageDrawable? = try {
        val source = ImageDecoder.createSource(context.assets, "$ASSETS_FOLDER/${fileName(emoji)}")
        (ImageDecoder.decodeDrawable(source) as? AnimatedImageDrawable)?.also {
            it.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
        }
    } catch (e: Exception) { // IOException or a decoder refusal: the plain emoji is sent instead
        Log.w(TAG, "cannot load animated emoji ${fileName(emoji)}", e)
        null
    }

    /**
     * Sends [emoji] as a GIF when the field takes images, and tells whether it did; false: the caller types the plain emoji.
     * Steps of isCommitContentSupported and doCommitContent in the CommitContentSampleIME (Apache-2.0),
     * https://github.com/android/input-samples/blob/main/CommitContentSampleIME/app/src/main/java/com/example/android/commitcontent/ime/ImageKeyboard.java
     * and https://developer.android.com/develop/ui/views/touch-and-input/image-keyboard
     */
    @JvmStatic
    fun commit(context: Context, emoji: String): Boolean {
        val ime = inputMethodService(context) ?: return false
        val info = ime.currentInputEditorInfo ?: return false
        val connection = ime.currentInputConnection ?: return false
        if (info.packageName == null || !acceptsGif(info)) return false
        val file = copyToFiles(context, emoji) ?: return false
        return try {
            val uri = FileProvider.getUriForFile(context, context.getString(R.string.animated_provider_authority), file)
            val content = InputContentInfoCompat(uri, ClipDescription(emoji, arrayOf(MIME_GIF)), null)
            // the read permission goes to the receiving app alone, for this content only (API 25 and later)
            InputConnectionCompat.commitContent(connection, info, content, InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null)
        } catch (e: Exception) {
            Log.w(TAG, "cannot send animated emoji", e)
            false
        }
    }

    private fun acceptsGif(info: EditorInfo) =
        EditorInfoCompat.getContentMimeTypes(info).any { ClipDescription.compareMimeTypes(MIME_GIF, it) }

    // the sample copies its raw resources to files/images the same way (getFileForResource); written beside, then renamed
    private fun copyToFiles(context: Context, emoji: String): File? {
        val dir = File(context.filesDir, ASSETS_FOLDER)
        if (!dir.isDirectory && !dir.mkdirs()) return null
        val name = fileName(emoji)
        val target = File(dir, name)
        val temp = File(dir, "$name.tmp")
        return try {
            context.assets.open("$ASSETS_FOLDER/$name").use { input -> temp.outputStream().use { input.copyTo(it) } }
            if (temp.renameTo(target)) target else null
        } catch (e: IOException) {
            temp.delete()
            Log.w(TAG, "cannot copy animated emoji $name", e)
            null
        }
    }

    private fun inputMethodService(context: Context): InputMethodService? {
        var c: Context? = context
        while (c is ContextWrapper) {
            if (c is InputMethodService) return c
            c = c.baseContext
        }
        return null
    }
}
