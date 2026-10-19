// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.latin

import android.annotation.SuppressLint
import android.content.ClipDescription
import android.content.Context
import android.net.Uri
import android.os.Build
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.FileProvider
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.core.security.MediaCheck
import androidx.core.view.inputmethod.InputContentInfoCompat
import helium314.keyboard.latin.database.ClipboardDao
import java.io.File

class ClipboardHistoryEntry(
    val id: Long,
    var timeStamp: Long,
    var isPinned: Boolean,
    val text: String?,
    val filename: String?,
    val mimeTypes: List<String>?,
    // Keyboard: ClipDescription.EXTRA_IS_SENSITIVE, kept with the entry as FlorisBoard's ClipboardItem.isSensitive
    // (https://github.com/florisboard/florisboard/blob/main/app/src/main/kotlin/dev/patrickgold/florisboard/ime/clipboard/provider/ClipboardDatabase.kt)
    val isSensitive: Boolean = false
) : Comparable<ClipboardHistoryEntry> {
    // for display order
    override fun compareTo(other: ClipboardHistoryEntry): Int {
        val result = other.isPinned.compareTo(isPinned)
        if (result == 0) return other.timeStamp.compareTo(timeStamp)
        if (Settings.getValues()?.mClipboardHistoryPinnedFirst == false) return -result
        return result
    }

    // Keyboard: what the stored file really is, from its first bytes (Modules' safemedia kit, MediaCheck), read once
    @Volatile private var realKind: MediaCheck.Kind? = null

    fun mediaKind(context: Context): MediaCheck.Kind = realKind ?: (
        if (filename == null) MediaCheck.Kind.OTHER
        else MediaCheck.kind(context, Uri.fromFile(File(ClipboardDao.clipFilesDir, filename)))
    ).also { realKind = it }

    // Keyboard: the types given to the app receiving the file; a picture, film or sound type only when the bytes agree
    // (MediaCheck.typeFor, WHATWG MIME Sniffing: https://mimesniff.spec.whatwg.org/#context-specific-sniffing),
    // else application/octet-stream: a file that lies about its type reaches no viewer as a picture
    fun safeMimeTypes(context: Context): List<String>? {
        val real = mediaKind(context)
        return mimeTypes?.map { MediaCheck.typeFor(real, it) }
    }

    fun getContentInfo(context: Context): InputContentInfoCompat =
        InputContentInfoCompat(getContentUri(context)!!, ClipDescription(text, safeMimeTypes(context)?.toTypedArray()), null)

    fun getContentUri(context: Context) = filename?.let { FileProvider.getUriForFile(
        context,
        context.getString(R.string.clipboard_provider_authority),
        File(ClipboardDao.clipFilesDir, it)
    ) }

    // Keyboard: HeliBoard's setImageAndDescription without its picture half
    // (https://github.com/HeliBorg/HeliBoard/blob/main/app/src/main/java/helium314/keyboard/latin/ClipboardHistoryEntry.kt):
    // a file that is no picture (or whose bytes are none) shows its type's icon and name; the picture itself is
    // decoded in the isolated decoder (keyboard/clipboard/ClipThumbnails)
    @SuppressLint("SetTextI18n")
    fun setFileDescription(imageView: ImageView, textView: TextView) {
        val mimeTypes = safeMimeTypes(imageView.context)
        if (mimeTypes == null || filename == null) return // should never happen
        val description = if (text.isNullOrBlank()) ""
            else "\n" + textView.context.getString(R.string.item_description, text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val info = imageView.context.contentResolver.getTypeInfo(mimeTypes[0])
            info.icon.setTint(Settings.getValues().mColors.get(ColorType.EMOJI_CATEGORY))
            imageView.setImageIcon(info.icon)
            textView.text = textView.context.getString(R.string.item_type, info.label.toString()) + description
            return
        }
        imageView.setImageResource(R.drawable.ic_dictionary)
        Settings.getValues().mColors.setColor(imageView, ColorType.EMOJI_CATEGORY)
        textView.text = textView.context.getString(R.string.item_type, mimeTypes.first()) + description
    }
}
