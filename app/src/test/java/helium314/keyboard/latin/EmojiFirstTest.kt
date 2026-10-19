// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.latin

import android.view.inputmethod.EditorInfo
import helium314.keyboard.keyboard.emoji.EmojiFirst
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Source: the pattern of this app's own Robolectric tests (SubtypeTest, InputLogicTest build an EditorInfo the same way);
// the reading under test is HeliBoard's InputAttributes.inPrivateImeOptions
// (https://github.com/HeliBorg/HeliBoard/blob/main/app/src/main/java/helium314/keyboard/latin/InputAttributes.java)
@RunWith(RobolectricTestRunner::class)
class EmojiFirstTest {

    private fun editor(options: String?) = EditorInfo().apply { privateImeOptions = options }

    @Test
    fun `the option alone asks for the emoji page`() {
        assertTrue(EmojiFirst.wanted(editor("com.yaz.keyboard.emoji")))
    }

    @Test
    fun `the option among others, comma separated, asks for it`() {
        assertTrue(EmojiFirst.wanted(editor("noBackground,com.yaz.keyboard.emoji")))
    }

    @Test
    fun `no options, another option or no editor do not`() {
        assertFalse(EmojiFirst.wanted(editor(null)))
        assertFalse(EmojiFirst.wanted(editor("")))
        assertFalse(EmojiFirst.wanted(editor("noBackground")))
        assertFalse(EmojiFirst.wanted(null))
    }

    @Test
    fun `the search panel's own option does not`() {
        assertFalse(EmojiFirst.wanted(editor("helium314.keyboard.keyboard.emoji.search.300,")))
    }

    @Test
    fun `a word that only contains the option does not`() {
        assertFalse(EmojiFirst.wanted(editor("com.yaz.keyboard.emoji.more")))
        assertFalse(EmojiFirst.wanted(editor("xcom.yaz.keyboard.emoji")))
    }
}
