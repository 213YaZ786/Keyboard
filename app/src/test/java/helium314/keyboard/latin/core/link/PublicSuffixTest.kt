package helium314.keyboard.latin.core.link

import java.io.File
import java.net.IDN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The Public Suffix List's own test cases, https://github.com/publicsuffix/list/blob/master/tests/test_psl.txt
 * (CC0), each checkPublicSuffix(host, registrable) of the file, over the list shipped in the assets.
 */
class PublicSuffixTest {

    private val rules = PublicSuffix.parse(
        listOf("src/main/assets/links/public_suffix_list.txt", "app/src/main/assets/links/public_suffix_list.txt")
            .map(::File).first { it.exists() }.readText()
    )

    /** The file's cases are written in letters; the host reaches PublicSuffix in punycode. */
    private fun check(host: String, expected: String?) {
        // An empty label (a leading dot) cannot be converted: the host goes as it is.
        val ascii = runCatching { IDN.toASCII(host, IDN.ALLOW_UNASSIGNED) }.getOrDefault(host)
        assertEquals(host, expected?.let { IDN.toASCII(it, IDN.ALLOW_UNASSIGNED).lowercase() }, PublicSuffix.registrable(ascii, rules))
    }

    @Test
    fun `mixed case, leading dots and unlisted top levels`() {
        check("COM", null)
        check("example.COM", "example.com")
        check("WwW.example.COM", "example.com")
        check(".com", null)
        check(".example", null)
        check(".example.com", null)
        check(".example.example", null)
        check("example", null)
        check("example.example", "example.example")
        check("b.example.example", "example.example")
        check("a.b.example.example", "example.example")
    }

    @Test
    fun `top levels with one rule or a few`() {
        check("biz", null)
        check("domain.biz", "domain.biz")
        check("b.domain.biz", "domain.biz")
        check("a.b.domain.biz", "domain.biz")
        check("com", null)
        check("example.com", "example.com")
        check("b.example.com", "example.com")
        check("a.b.example.com", "example.com")
        check("uk.com", null)
        check("example.uk.com", "example.uk.com")
        check("b.example.uk.com", "example.uk.com")
        check("a.b.example.uk.com", "example.uk.com")
        check("test.ac", "test.ac")
    }

    @Test
    fun `wildcard rules and their exceptions`() {
        check("mm", null)
        check("c.mm", null)
        check("b.c.mm", "b.c.mm")
        check("a.b.c.mm", "b.c.mm")
        check("jp", null)
        check("test.jp", "test.jp")
        check("www.test.jp", "test.jp")
        check("ac.jp", null)
        check("test.ac.jp", "test.ac.jp")
        check("www.test.ac.jp", "test.ac.jp")
        check("kyoto.jp", null)
        check("test.kyoto.jp", "test.kyoto.jp")
        check("ide.kyoto.jp", null)
        check("b.ide.kyoto.jp", "b.ide.kyoto.jp")
        check("a.b.ide.kyoto.jp", "b.ide.kyoto.jp")
        check("c.kobe.jp", null)
        check("b.c.kobe.jp", "b.c.kobe.jp")
        check("a.b.c.kobe.jp", "b.c.kobe.jp")
        check("city.kobe.jp", "city.kobe.jp")
        check("www.city.kobe.jp", "city.kobe.jp")
        check("ck", null)
        check("test.ck", null)
        check("b.test.ck", "b.test.ck")
        check("a.b.test.ck", "b.test.ck")
        check("www.ck", "www.ck")
        check("www.www.ck", "www.ck")
        check("us", null)
        check("test.us", "test.us")
        check("www.test.us", "test.us")
        check("ak.us", null)
        check("test.ak.us", "test.ak.us")
        check("www.test.ak.us", "test.ak.us")
        check("k12.ak.us", null)
        check("test.k12.ak.us", "test.k12.ak.us")
        check("www.test.k12.ak.us", "test.k12.ak.us")
    }

    @Test
    fun `internationalised labels, in letters and in punycode`() {
        check("食狮.com.cn", "食狮.com.cn")
        check("食狮.公司.cn", "食狮.公司.cn")
        check("www.食狮.公司.cn", "食狮.公司.cn")
        check("shishi.公司.cn", "shishi.公司.cn")
        check("公司.cn", null)
        check("食狮.中国", "食狮.中国")
        check("www.食狮.中国", "食狮.中国")
        check("shishi.中国", "shishi.中国")
        check("中国", null)
        check("xn--85x722f.com.cn", "xn--85x722f.com.cn")
        check("xn--85x722f.xn--55qx5d.cn", "xn--85x722f.xn--55qx5d.cn")
        check("www.xn--85x722f.xn--55qx5d.cn", "xn--85x722f.xn--55qx5d.cn")
        check("shishi.xn--55qx5d.cn", "shishi.xn--55qx5d.cn")
        check("xn--55qx5d.cn", null)
        check("xn--85x722f.xn--fiqs8s", "xn--85x722f.xn--fiqs8s")
        check("www.xn--85x722f.xn--fiqs8s", "xn--85x722f.xn--fiqs8s")
        check("shishi.xn--fiqs8s", "shishi.xn--fiqs8s")
        check("xn--fiqs8s", null)
    }

    @Test
    fun `the common cases of a link, and no list at all`() {
        check("mail.example.co.uk", "example.co.uk")
        check("evil.github.io", "evil.github.io")
        check("www.bbc.co.uk", "bbc.co.uk")
        assertNull(PublicSuffix.registrable("example.com", null))
    }
}
