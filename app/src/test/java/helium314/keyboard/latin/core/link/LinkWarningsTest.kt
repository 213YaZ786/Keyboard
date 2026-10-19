package helium314.keyboard.latin.core.link

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * LinkWarnings on addresses of the kinds a scam text uses: URLCheck's patterns (http, a non-ASCII
 * host, capitals, the Rickroll; PatternCatalog.java) and the semantic attacks of RFC 3986 section 7.6
 * (https://www.rfc-editor.org/rfc/rfc3986#section-7.6). URLCheck has no tests of its own.
 */
class LinkWarningsTest {

    // JUnit 4's fixture, https://github.com/junit-team/junit4/wiki/Test-fixtures.
    @Before
    fun listInPlace() = PublicSuffix.install(PublicSuffix.parse(
        listOf("src/main/assets/links/public_suffix_list.txt", "app/src/main/assets/links/public_suffix_list.txt")
            .map(::File).first { it.exists() }.readText()
    ))

    @After
    fun listOut() = PublicSuffix.install(null)

    private fun kinds(url: String) = LinkWarnings.of(url).map { it.kind }

    // Cyrillic small a, the letter that stands for Latin a in "paypal".
    private val cyrillicA = "а"

    @Test
    fun `a plain https address has nothing to say`() {
        assertEquals(emptyList<LinkWarnings.Kind>(), kinds("https://example.org/in/someone?q=1"))
        assertEquals(emptyList<LinkWarnings.Kind>(), kinds("https://fr.wikipedia.org/wiki/%C3%89t%C3%A9"))
        assertEquals(emptyList<LinkWarnings.Kind>(), kinds("https://fr.wikipedia.org/wiki/Été"))
    }

    @Test
    fun `http is not encrypted`() {
        assertEquals(listOf(LinkWarnings.Kind.HTTP), kinds("http://example.org/"))
        assertEquals(listOf(LinkWarnings.Kind.HTTP), kinds("HTTP://example.org/"))
    }

    @Test
    fun `a name before the at sign is not the site`() {
        val found = LinkWarnings.of("https://paypal.com@evil.example/login")
        assertEquals(listOf(LinkWarnings.Kind.USER_INFO), found.map { it.kind })
        assertEquals(LinkWarnings.Level.DANGER, found.single().level)
        assertEquals("evil.example", LinkWarnings.host("https://paypal.com@evil.example/login")?.domain)
        // A backslash ends the host in a browser: this goes to evil.example, not paypal.com.
        assertEquals("evil.example", LinkWarnings.host("https://evil.example\\@paypal.com/")?.domain)
        assertEquals(emptyList<LinkWarnings.Kind>(), kinds("https://evil.example\\@paypal.com/"))
    }

    @Test
    fun `a number for a host has no site name`() {
        assertTrue(LinkWarnings.Kind.IP_HOST in kinds("https://192.168.0.1/admin"))
        assertTrue(LinkWarnings.Kind.IP_HOST in kinds("https://[2001:db8::1]/"))
        assertTrue(LinkWarnings.Kind.IP_HOST in kinds("https://3232235777/"))
        assertTrue(LinkWarnings.Kind.IP_HOST in kinds("https://0x7f000001/"))
        assertEquals(listOf(LinkWarnings.Kind.HTTP, LinkWarnings.Kind.IP_HOST).toSet(), kinds("http://10.0.0.1:8080/x").toSet())
        assertTrue(LinkWarnings.Kind.IP_HOST !in kinds("https://example.org:8443/"))
    }

    @Test
    fun `letters of another alphabet show the decoded name`() {
        // xn--80ak6aa92e.com is the Cyrillic name that reads like apple.com.
        val punycode = LinkWarnings.of("https://xn--80ak6aa92e.com/").single()
        assertEquals(LinkWarnings.Kind.OTHER_LETTERS, punycode.kind)
        assertTrue(punycode.label, punycode.label.endsWith("аррӏе.com"))
        val raw = LinkWarnings.of("https://münchen.de/").single()
        assertEquals(LinkWarnings.Kind.OTHER_LETTERS, raw.kind)
        assertTrue(raw.label, raw.label.endsWith("münchen.de"))
    }

    @Test
    fun `latin mixed with cyrillic is dangerous`() {
        val found = LinkWarnings.of("https://p${cyrillicA}ypal.com/signin").single()
        assertEquals(LinkWarnings.Kind.MIXED_LETTERS, found.kind)
        assertEquals(LinkWarnings.Level.DANGER, found.level)
        assertTrue(found.label, found.label.endsWith("p${cyrillicA}ypal.com"))
    }

    @Test
    fun `capitals, the rickroll and a tracking domain are noted`() {
        assertEquals(listOf(LinkWarnings.Kind.CAPITALS), kinds("https://Example.COM/Path"))
        assertEquals(listOf(LinkWarnings.Kind.RICKROLL), kinds("https://youtu.be/dQw4w9WgXcQ"))
        val tracker = ClearUrls.Cleaned("https://ads.example/", redirected = false, stripped = false, tracker = "adtech")
        assertEquals(listOf(LinkWarnings.Kind.TRACKER), LinkWarnings.of("https://ads.example/", tracker).map { it.kind })
    }

    @Test
    fun `the worst warning comes first and other schemes have none`() {
        val found = LinkWarnings.of("http://paypal.com@Evil.example/")
        assertEquals(LinkWarnings.Kind.USER_INFO, found.first().kind)
        assertEquals(found.sortedByDescending { it.level.ordinal }.map { it.kind }, found.map { it.kind })
        assertEquals(emptyList<LinkWarnings.Kind>(), kinds("ftp://example.org/"))
        assertEquals(emptyList<LinkWarnings.Kind>(), kinds("mailto:someone@example.org"))
        assertEquals(emptyList<LinkWarnings.Kind>(), kinds("someone"))
    }

    @Test
    fun `the host is split at its registrable domain`() {
        LinkWarnings.host("https://mail.example.co.uk/x")!!.let {
            assertEquals("mail.", it.subdomain)
            assertEquals("example.co.uk", it.domain)
        }
        LinkWarnings.host("https://example.com")!!.let {
            assertEquals("", it.subdomain)
            assertEquals("example.com", it.domain)
        }
        LinkWarnings.host("https://evil.github.io:8080/")!!.let {
            assertEquals("", it.subdomain)
            assertEquals("evil.github.io", it.domain)
        }
        LinkWarnings.host("https://www.xn--80ak6aa92e.com/")!!.let {
            assertEquals("www.", it.subdomain)
            assertEquals("аррӏе.com", it.domain)
        }
        LinkWarnings.host("https://localhost/")!!.let {
            assertEquals("", it.subdomain)
            assertEquals("localhost", it.domain)
        }
        assertEquals("192.168.0.1", LinkWarnings.host("http://192.168.0.1/")?.domain)
        assertNull(LinkWarnings.host("ftp://example.org/"))
    }
}
