package helium314.keyboard.latin.core.link

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ClearUrls over the catalog shipped in the assets. URLCheck has no tests of its own for its
 * ClearUrlModule, so these follow the catalog: the two providers the ClearURLs project keeps in it
 * to test with (ClearURLsTest2 and ClearURLsTestBlock2, test.clearurls.xyz, from
 * https://github.com/ClearURLs/Rules/blob/master/data.min.json) and real addresses of its other
 * providers (Amazon, YouTube, GitHub, Google, Facebook, Instagram, Reddit, Spotify, Pantip).
 */
class ClearUrlsTest {

    private val catalog = ClearUrls.parse(
        listOf("src/main/assets/links/clearurls.json", "app/src/main/assets/links/clearurls.json")
            .map(::File).first { it.exists() }.readText()
    )

    private fun clean(url: String, allowReferral: Boolean = false) = ClearUrls.inspect(url, catalog, allowReferral).url

    @Test
    fun `the catalog's own test provider drops its test parameter and unwraps its redirection`() {
        assertEquals("https://test.clearurls.xyz/void/index.html?other=1", clean("https://test.clearurls.xyz/void/index.html?test=abc&other=1"))
        val unwrapped = ClearUrls.inspect("https://test.clearurls.xyz/void/index.html?url=https%3A%2F%2Fexample.org%2Fpage%3Fa%3D1", catalog)
        assertEquals("https://example.org/page?a=1", unwrapped.url)
        assertTrue(unwrapped.redirected)
    }

    @Test
    fun `the catalog's own test block is a complete provider`() {
        val found = ClearUrls.inspect("https://test.clearurls.xyz/void/block.svg", catalog)
        assertEquals("ClearURLsTestBlock2", found.tracker)
        assertEquals("https://test.clearurls.xyz/void/block.svg", found.url)
        assertEquals("YouTube's ad servers", "youtube_pagead", ClearUrls.inspect("https://www.youtube.com/pagead/viewthroughconversion/1", catalog).tracker)
        assertNull(ClearUrls.inspect("https://example.org/", catalog).tracker)
    }

    @Test
    fun `drops the global tracking parameters and keeps the rest and the fragment`() {
        assertEquals("https://example.org/a?id=4#top", clean("https://example.org/a?utm_source=x&utm_medium=y&id=4&fbclid=1#top"))
        assertEquals("https://example.org/a?id=4", clean("https://example.org/a?id=4&Utm_Campaign=x"))
        // Nothing left to ask: no dangling ? or &.
        assertEquals("https://example.org/a", clean("https://example.org/a?utm_source=x"))
        assertEquals("https://example.org/a?s=20", clean("https://example.org/a?s=20"))
    }

    @Test
    fun `referral marketing goes unless it is allowed`() {
        val amazon = "https://www.amazon.com/Some-Product/dp/B08N5WRWNW/ref=sr_1_3?crid=1AB&keywords=cable&qid=1700000000&sr=8-3&tag=site-20&th=1"
        assertEquals("https://www.amazon.com/Some-Product/dp/B08N5WRWNW", clean(amazon))
        assertEquals("https://www.amazon.com/Some-Product/dp/B08N5WRWNW?tag=site-20", clean(amazon, allowReferral = true))
        // The catalog's exception: Amazon's search keeps what it is asked.
        assertEquals("https://www.amazon.com/s?k=cable", clean("https://www.amazon.com/s?k=cable&crid=1AB&sprefix=ca"))
    }

    @Test
    fun `cleans the sites of the catalog`() {
        assertEquals("https://youtu.be/dQw4w9WgXcQ", clean("https://youtu.be/dQw4w9WgXcQ?si=abc123&feature=share"))
        assertEquals("https://github.com/213YaZ786/SMS/issues?q=is%3Aopen", clean("https://github.com/213YaZ786/SMS/issues?email_token=abc&email_source=notifications&q=is%3Aopen"))
        assertEquals("https://www.google.com/search?q=cats", clean("https://www.google.com/search?q=cats&sxsrf=ABC&ved=2ah&ei=xx&oq=cats"))
        assertEquals("https://www.facebook.com/someone/posts/123", clean("https://www.facebook.com/someone/posts/123?__tn__=%2CO*F&mibextid=xyz"))
        assertEquals("https://www.instagram.com/p/ABC/", clean("https://www.instagram.com/p/ABC/?igsh=MTIz"))
        assertEquals("https://www.reddit.com/r/test/comments/1/x/", clean("https://www.reddit.com/r/test/comments/1/x/?utm_source=share&utm_medium=web&rdt=1"))
        assertEquals("https://www.spotify.com/track/1", clean("https://www.spotify.com/track/1?si=abc"))
        // A raw rule: Pantip's "#lead..." anchor.
        assertEquals("https://pantip.com/topic/123", clean("https://pantip.com/topic/123#lead_something"))
    }

    @Test
    fun `unwraps the detours of the catalog`() {
        assertEquals("https://example.org/in/someone", clean("https://www.google.com/url?sa=t&source=web&url=https%3A%2F%2Fexample.org%2Fin%2Fsomeone&ved=2ah"))
        assertEquals("https://example.org/a?b=1", clean("https://www.youtube.com/redirect?event=video_description&redir_token=QUFF&q=https%3A%2F%2Fexample.org%2Fa%3Fb%3D1&v=xyz"))
        assertEquals("https://example.org/d", clean("https://duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.org%2Fd&rut=x"))
        // A plus in the address stays a plus.
        assertEquals("https://example.org/a+b", clean("https://duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.org%2Fa+b"))
    }

    @Test
    fun `a redirection is followed to a web address only`() {
        val hostile = "https://www.instagram.com/accounts/?u=javascript%3Aalert(1)"
        assertEquals(hostile, clean(hostile))
        val fileUrl = "https://www.instagram.com/accounts/?u=file%3A%2F%2F%2Fdata%2Fx"
        assertEquals(fileUrl, clean(fileUrl))
        // A broken escape reads as no redirection.
        val broken = "https://duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.org%2F%zz"
        assertEquals(broken, clean(broken))
    }

    @Test
    fun `leaves what is not a web address, or is too long, or has no catalog`() {
        assertEquals("someone", clean("someone"))
        assertEquals("mailto:a@b.c?utm_source=x", clean("mailto:a@b.c?utm_source=x"))
        val long = "https://example.org/a?utm_source=x&" + "a".repeat(ClearUrls.MAX_LENGTH)
        assertEquals(long, clean(long))
        assertEquals("https://example.org/a?utm_source=x", ClearUrls.inspect("https://example.org/a?utm_source=x", null).url)
        assertEquals("https://example.org/a?utm_source=x", ClearUrls.clean("https://example.org/a?utm_source=x"))
    }

    @Test
    fun `a rule that does not compile skips its provider`() {
        val broken = ClearUrls.parse(
            """{"providers":{"no pattern":{"rules":["a"]},"bad pattern":{"urlPattern":"(["},""" +
                """"bad rule":{"urlPattern":"^https?://example\\.org","rules":["("]},""" +
                """"fine":{"urlPattern":"^https?://example\\.org","rules":["x"]}}}"""
        )
        // The bad rule is skipped with its provider, the fine one still works.
        val found = ClearUrls.inspect("https://example.org/a?x=1&y=2", broken)
        assertEquals("https://example.org/a?y=2", found.url)
        assertTrue(found.stripped)
        assertFalse(ClearUrls.inspect("https://example.org/a?y=2", broken).stripped)
    }
}
