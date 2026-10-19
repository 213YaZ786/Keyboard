package helium314.keyboard.latin.core.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test
import java.io.File

class LinkCleanerTest {

    // JUnit 4's fixture, https://github.com/junit-team/junit4/wiki/Test-fixtures: the catalog left in place is emptied.
    @After
    fun forgetCatalog() = ClearUrls.install(null)

    // The results below hold when the ClearURLs catalog (see ClearUrlsTest) is in place too: it cleans first.
    // Cases of the catalog's own providers, https://github.com/ClearURLs/Rules/blob/master/data.min.json
    @Test
    fun `the catalog cleans first and the own rules finish`() {
        ClearUrls.install(ClearUrls.parse(
            listOf("src/main/assets/links/clearurls.json", "app/src/main/assets/links/clearurls.json")
                .map(::File).first { it.exists() }.readText()
        ))
        assertEquals("https://www.amazon.com/dp/B08N5WRWNW", LinkCleaner.clean("https://www.amazon.com/dp/B08N5WRWNW/ref=sr_1_1?tag=x-20&utm_source=y"))
        assertEquals("https://example.org/in/someone", LinkCleaner.clean("https://www.google.com/url?sa=t&source=web&url=https%3A%2F%2Fexample.org%2Fin%2Fsomeone&ved=2ah"))
        assertEquals("https://example.org/p", LinkCleaner.clean("https://www.google.fr/url?q=https://example.org/p&sa=U"))
        assertEquals("https://example.org/b", LinkCleaner.clean("https://www.bing.com/ck/a?!&&p=abc&u=a1aHR0cHM6Ly9leGFtcGxlLm9yZy9i&ntb=1"))
        assertEquals("https://example.org/d", LinkCleaner.clean("https://duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.org%2Fd&rut=x"))
        // The catalog unwraps Facebook's detour, the own rules drop the tracking left in its target.
        assertEquals("https://example.org/f", LinkCleaner.clean("https://l.facebook.com/l.php?u=https%3A%2F%2Fexample.org%2Ff%3Ffbclid%3Dzz&h=AT0"))
        assertEquals("https://example.org/a?id=4#top", LinkCleaner.clean("https://example.org/a?utm_source=x&id=4&fbclid=1#top"))
        assertEquals("https://x.com/someone/status/1", LinkCleaner.clean("https://x.com/someone/status/1?s=20&t=abc"))
        assertEquals("https://example.org/a?s=20", LinkCleaner.clean("https://example.org/a?s=20"))
        assertEquals("someone", LinkCleaner.clean("someone"))
        // A Google link that hides its address goes to the resolver as it came, usg and all.
        val coded = "https://www.google.com/url?sa=t&source=web&rct=j&url=CAESYgHrOzAV&usg=AOvVaw0"
        assertEquals(coded, LinkCleaner.clean(coded))
        assertTrue(LinkCleaner.needsResolving(LinkCleaner.clean(coded)))
    }

    @Test
    fun `reads the page out of a Google result link`() {
        assertEquals(
            "https://example.org/in/someone",
            LinkCleaner.clean("https://www.google.com/url?sa=t&source=web&url=https%3A%2F%2Fexample.org%2Fin%2Fsomeone&ved=2ah")
        )
        assertEquals("https://example.org/p", LinkCleaner.clean("https://www.google.fr/url?q=https://example.org/p&sa=U"))
    }

    @Test
    fun `reads Bing, DuckDuckGo and Facebook detours`() {
        // "https://example.org/b" in URL safe base64, after Bing's a1 prefix.
        assertEquals("https://example.org/b", LinkCleaner.clean("https://www.bing.com/ck/a?!&&p=abc&u=a1aHR0cHM6Ly9leGFtcGxlLm9yZy9i&ntb=1"))
        assertEquals("https://example.org/d", LinkCleaner.clean("https://duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.org%2Fd&rut=x"))
        assertEquals("https://example.org/f", LinkCleaner.clean("https://l.facebook.com/l.php?u=https%3A%2F%2Fexample.org%2Ff%3Ffbclid%3Dzz&h=AT0"))
    }

    @Test
    fun `drops tracking and keeps what the page needs`() {
        assertEquals("https://example.org/a?id=4#top", LinkCleaner.clean("https://example.org/a?utm_source=x&id=4&fbclid=1#top"))
        assertEquals("https://x.com/someone/status/1", LinkCleaner.clean("https://x.com/someone/status/1?s=20&t=abc"))
        assertEquals("https://example.org/a?s=20", LinkCleaner.clean("https://example.org/a?s=20"))
    }

    @Test
    fun `leaves plain links and plain words alone`() {
        assertEquals("https://fr.example.org/in/someone", LinkCleaner.clean("https://fr.example.org/in/someone"))
        assertEquals("someone", LinkCleaner.clean("someone"))
    }

    @Test
    fun `knows which Google links hide their address`() {
        assertTrue(LinkCleaner.needsResolving("https://www.google.com/goto?url=CAESbAHrOzAV"))
        assertTrue(LinkCleaner.needsResolving("https://share.google/abc123"))
        assertTrue(LinkCleaner.needsResolving("https://www.google.com/url?sa=t&source=web&rct=j&url=CAESYgHrOzAV&usg=AOvVaw0"))
        assertFalse(LinkCleaner.needsResolving("https://www.google.com/url?q=https://example.org"))
        assertFalse(LinkCleaner.needsResolving("https://example.org/goto?url=x"))
    }
}
