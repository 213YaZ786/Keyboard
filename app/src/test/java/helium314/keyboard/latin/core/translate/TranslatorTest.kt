package helium314.keyboard.latin.core.translate

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * JUnit 4 unit tests (https://junit.org/junit4/), in the style of the links kit's tests; the tags are
 * BCP 47 as java.util.Locale reads them
 * https://developer.android.com/reference/java/util/Locale#forLanguageTag(java.lang.String)
 * against the languages of Translator's catalogue
 * https://github.com/DavidVentura/offline-translator/blob/main/app/src/main/assets/index_v6.json
 */
class TranslatorTest {

    private fun code(tag: String) = Translator.codeFor(Locale.forLanguageTag(tag))

    @Test
    fun `a language of the catalogue gives its code`() {
        assertEquals("fr", code("fr-FR"))
        assertEquals("en", code("en"))
        assertEquals("ar", code("ar-MA"))
    }

    @Test
    fun `a language outside the catalogue gives nothing`() {
        assertNull(code("zgh"))
        assertNull(code("kab"))
        assertNull(Translator.codeFor(Locale.ROOT))
    }

    @Test
    fun `Chinese is simplified unless the script or the region says traditional`() {
        assertEquals("zh", code("zh"))
        assertEquals("zh", code("zh-CN"))
        assertEquals("zh", code("zh-Hans-SG"))
        assertEquals("zh_hant", code("zh-Hant"))
        assertEquals("zh_hant", code("zh-TW"))
        assertEquals("zh_hant", code("zh-HK"))
        assertEquals("zh_hant", code("zh-MO"))
    }

    @Test
    fun `Java's old codes and Filipino are the catalogue's`() {
        assertEquals("he", Translator.codeFor(Locale("iw", "IL")))
        assertEquals("id", Translator.codeFor(Locale("in", "ID")))
        assertEquals("tl", code("fil-PH"))
    }

    @Test
    fun `Norwegian is Bokmal only, as Language's catalogue`() {
        // Language's index_v7.json (Firefox's selection) has nb, not nn nor no:
        // /home/yghouar/Claude Code/APK/YAZ/Language/app/src/main/assets/index_v7.json, engine/catalog/languages.py
        assertEquals("nb", code("nb-NO"))
        assertNull(code("nn"))
        assertNull(code("no"))
    }

    @Test
    fun `the locale of a code shows its language`() {
        assertEquals("fr", Translator.localeOf("fr").language)
        assertEquals("Hant", Translator.localeOf("zh_hant").script)
    }
}
