// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.latin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Source: the pattern of the links kit's LinkCleanerTest and PastedTextTest (plain JUnit 4 on the JVM, pure object),
// the rules of FlorisBoard's ClipboardManager.enforceExpiryDate and ClipboardItem.displayText
// (https://github.com/florisboard/florisboard/blob/main/app/src/main/kotlin/dev/patrickgold/florisboard/ime/clipboard/ClipboardManager.kt)
class ClipboardPrivacyTest {

    private val mask = "Sensitive content"

    @Test
    fun `a sensitive item shows the mask, never its text`() {
        assertEquals(mask, ClipboardPrivacy.shown("hunter2", true, mask))
        assertEquals(mask, ClipboardPrivacy.shown(null, true, mask))
    }

    @Test
    fun `an ordinary item shows its text`() {
        assertEquals("hello", ClipboardPrivacy.shown("hello", false, mask))
        assertNull(ClipboardPrivacy.shown(null, false, mask))
    }

    @Test
    fun `a sensitive item expires after 20 seconds, not before`() {
        val copied = 1_000_000L
        assertFalse(ClipboardPrivacy.isExpired(true, copied, copied))
        assertFalse(ClipboardPrivacy.isExpired(true, copied, copied + 19_999))
        assertFalse(ClipboardPrivacy.isExpired(true, copied, copied + 20_000))
        assertTrue(ClipboardPrivacy.isExpired(true, copied, copied + 20_001))
        assertTrue(ClipboardPrivacy.isExpired(true, copied, copied + 3_600_000))
    }

    @Test
    fun `an ordinary item never expires here`() {
        assertFalse(ClipboardPrivacy.isExpired(false, 0L, 10_000_000L))
    }

    @Test
    fun `the wait until expiry counts down and never reaches zero`() {
        val copied = 1_000_000L
        assertEquals(20_000L, ClipboardPrivacy.delayUntilExpiry(copied, copied))
        assertEquals(5_000L, ClipboardPrivacy.delayUntilExpiry(copied, copied + 15_000))
        assertEquals(1L, ClipboardPrivacy.delayUntilExpiry(copied, copied + 20_000))
        assertEquals(1L, ClipboardPrivacy.delayUntilExpiry(copied, copied + 99_000))
    }

    @Test
    fun `a copied link is pasted without its tracking`() {
        assertEquals(
            "https://example.org/p?id=3",
            ClipboardPrivacy.textForPaste("https://example.org/p?id=3&utm_source=x&fbclid=abc", true)
        )
    }

    @Test
    fun `a link inside a sentence is cleaned and the words and punctuation stay`() {
        assertEquals(
            "Look at this: https://example.org/a?x=1. Nice, (https://example.org/b) too",
            ClipboardPrivacy.textForPaste(
                "Look at this: https://example.org/a?x=1&gclid=zz. Nice, (https://example.org/b?utm_medium=m) too", true)
        )
    }

    @Test
    fun `a search engine detour is unwrapped`() {
        assertEquals(
            "https://example.org/in/someone",
            ClipboardPrivacy.textForPaste("https://www.google.com/url?sa=t&url=https%3A%2F%2Fexample.org%2Fin%2Fsomeone&ved=2ah", true)
        )
    }

    @Test
    fun `every link of a text is cleaned`() {
        assertEquals(
            "https://a.example/x https://b.example/y",
            ClipboardPrivacy.textForPaste("https://a.example/x?igsh=1 https://b.example/y?si=2", true)
        )
    }

    @Test
    fun `a text without a link, or a clean link, is pasted as it is`() {
        assertEquals("just words, 12*3=36?", ClipboardPrivacy.textForPaste("just words, 12*3=36?", true))
        assertEquals("https://example.org/p?id=3#top", ClipboardPrivacy.textForPaste("https://example.org/p?id=3#top", true))
        assertEquals("", ClipboardPrivacy.textForPaste("", true))
    }

    @Test
    fun `with the switch off the text is pasted as copied`() {
        val copied = "https://example.org/p?id=3&utm_source=x"
        assertEquals(copied, ClipboardPrivacy.textForPaste(copied, false))
    }

    @Test
    fun `a very long text without spaces is handled`() {
        val long = "https://example.org/" + "a".repeat(200_000) + "?fbclid=1"
        assertEquals("https://example.org/" + "a".repeat(200_000), ClipboardPrivacy.textForPaste(long, true))
    }
}
