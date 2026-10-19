// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard.emoji

import android.view.inputmethod.EditorInfo
import helium314.keyboard.latin.InputAttributes

/**
 * Keyboard: a field of one of our apps asks for the emoji page by a word in its EditorInfo.privateImeOptions
 * (SMS's reaction field, Compose PlatformImeOptions). Read the way HeliBoard reads its own options:
 * EmojiSearchActivity.decodePrivateImeOptions (the options of the field being typed in, taken from the editor,
 * https://github.com/HeliBorg/HeliBoard/blob/main/app/src/main/java/helium314/keyboard/keyboard/emoji/EmojiSearchActivity.kt)
 * and InputAttributes.inPrivateImeOptions (comma separated words, StringUtils.containsValueWhenSplit, AOSP LatinIME's
 * way to name a private option, https://github.com/HeliBorg/HeliBoard/blob/main/app/src/main/java/helium314/keyboard/latin/InputAttributes.java).
 */
object EmojiFirst {
    const val OPTION = "com.yaz.keyboard.emoji"

    /** Whether the field in [editorInfo] wants the keyboard to open on its emoji page. */
    @JvmStatic
    fun wanted(editorInfo: EditorInfo?): Boolean = InputAttributes.inPrivateImeOptions(null, OPTION, editorInfo)
}
