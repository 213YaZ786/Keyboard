package helium314.keyboard.latin.core.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatesTest {

    @Test
    fun `a later version is newer, number by number, suffixes aside`() {
        assertTrue(Updates.isNewer("2.10.0", "2.9.22"))
        assertTrue(Updates.isNewer("0.3.10", "0.3.9"))
        assertTrue(Updates.isNewer("1.0", "0.9.9"))
        assertFalse(Updates.isNewer("2.9.22", "2.10.0"))
        assertFalse(Updates.isNewer("0.3.4", "0.3.4"))
        assertFalse(Updates.isNewer("0.3.4", "0.3.4-debug"))
        assertTrue(Updates.isNewer("0.3.5", "0.3.4-debug"))
    }

    @Test
    fun `only files of this app's own releases are fetched`() {
        assertTrue(Updates.fromRepo("https://github.com/213YaZ786/Keyboard/releases/download/v1.0.0/Keyboard-1.0.0.apk"))
        assertFalse(Updates.fromRepo("http://github.com/213YaZ786/Keyboard/releases/download/v1.0.0/a.apk"))
        assertFalse(Updates.fromRepo("https://github.com/someone/Keyboard/releases/download/v1.0.0/a.apk"))
        assertFalse(Updates.fromRepo("https://github.com.evil.example/213YaZ786/Keyboard/releases/download/a.apk"))
    }
}
