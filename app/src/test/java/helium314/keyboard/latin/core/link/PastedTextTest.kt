package helium314.keyboard.latin.core.link

import org.junit.Assert.assertEquals
import org.junit.Test

class PastedTextTest {

    private fun query(raw: String) = PastedText.query(raw) { "example.org/" in it }

    @Test
    fun `pulls the address out of a shared sentence`() {
        assertEquals("https://example.org/a/b", query("Look at this: https://example.org/a/b."))
    }

    @Test
    fun `finds a schemeless link the app recognises`() {
        assertEquals("example.org/someone", query("see (example.org/someone)"))
    }

    @Test
    fun `keeps the first line when there is no address`() {
        assertEquals("jane", query("\n  jane \nsecond line"))
    }

    @Test
    fun `an empty clipboard pastes nothing`() {
        assertEquals("", query("   "))
    }
}
