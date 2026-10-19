package helium314.keyboard.latin.core.link

import java.net.IDN

/**
 * What an address may be hiding, read from the address alone: nothing is sent anywhere, no list
 * is fetched. Each warning is a few words for the link sheet (LinkSheet).
 *
 * Port of URLCheck's built-in patterns, https://github.com/TrianguloY/URLCheck/blob/master/app/src/main/java/com/trianguloy/urlchecker/modules/companions/PatternCatalog.java
 * (TrianguloY, licence CC BY 4.0) as PatternModule matches them,
 * https://github.com/TrianguloY/URLCheck/blob/master/app/src/main/java/com/trianguloy/urlchecker/modules/list/PatternModule.java:
 * "http" (^http://), "non-ASCII" (read on the host here: an accented path is no warning), the capital
 * letters of a domain (^.*?://[^/?#]*[A-Z]) and the Rickroll (youtu\.?be.*dQw4w9WgXcQ). The others are
 * the semantic attacks of RFC 3986 section 7.6 (https://www.rfc-editor.org/rfc/rfc3986#section-7.6):
 * a name before an @, a number for a host and look-alike letters, the last as Chromium's IDN rules
 * restrict a label that mixes Latin with Cyrillic or Greek
 * (https://chromium.googlesource.com/chromium/src/+/main/docs/idn.md), with the decoded name shown.
 * The ClearURLs catalog's tracking domains ([ClearUrls.Cleaned.tracker]) are the last one.
 * The authority ends at a backslash as in browsers (https://url.spec.whatwg.org/#authority-state).
 *
 * Shared across the apps of this base: edit Modules/shared, then run sync.sh.
 */
object LinkWarnings {

    enum class Kind { HTTP, USER_INFO, IP_HOST, OTHER_LETTERS, MIXED_LETTERS, CAPITALS, RICKROLL, TRACKER, OTHER }

    enum class Level { NOTE, CAUTION, DANGER }

    class Warning(val kind: Kind, val level: Level, val label: String)

    /** The host of an address split for display: [subdomain] ends with a dot, [domain] is the registrable one. */
    class Host(val subdomain: String, val domain: String, val encoded: String)

    /** Warnings for [url], the worst first. [cleaned] is what ClearUrls found, for its tracking domains. */
    fun of(url: String, cleaned: ClearUrls.Cleaned? = null): List<Warning> {
        val parsed = parse(url) ?: return emptyList()
        val out = ArrayList<Warning>()
        // PatternCatalog "http".
        if (parsed.scheme == "http") out += Warning(Kind.HTTP, Level.CAUTION, "Not encrypted (http)")
        // RFC 3986 7.6: a name before an @ is not where the link goes.
        if (parsed.userInfo != null) out += Warning(Kind.USER_INFO, Level.DANGER, "A name before @ is not the site")
        val host = parsed.host
        if (isNumber(host)) out += Warning(Kind.IP_HOST, Level.CAUTION, "An address with no site name")
        val decoded = decode(host)
        if (decoded.any { it.code > 127 }) {
            // PatternCatalog "non-ASCII", on the host, and the punycode that hides it.
            if (mixesLetters(decoded)) out += Warning(Kind.MIXED_LETTERS, Level.DANGER, "Letters of several alphabets: $decoded")
            else out += Warning(Kind.OTHER_LETTERS, Level.CAUTION, "Written with other letters: $decoded")
        }
        // PatternCatalog "uppercase domain".
        if (host.any { it in 'A'..'Z' }) out += Warning(Kind.CAPITALS, Level.NOTE, "Capital letters in the site name")
        // PatternCatalog "Rickroll".
        if (RICKROLL.containsMatchIn(url)) out += Warning(Kind.RICKROLL, Level.NOTE, "Rickroll")
        // ClearURLs' complete providers: domains made only to track or advertise.
        if (cleaned?.tracker != null) out += Warning(Kind.TRACKER, Level.CAUTION, "A tracking or advertising site")
        return out.sortedByDescending { it.level.ordinal }
    }

    /** The host of [url] for display, its registrable domain apart (PublicSuffix), or null for a non-web address. */
    fun host(url: String): Host? {
        val parsed = parse(url) ?: return null
        val written = parsed.host
        if (isNumber(written)) return Host("", written, written)
        val ascii = runCatching { IDN.toASCII(written, IDN.ALLOW_UNASSIGNED) }.getOrDefault(written).lowercase()
        // A host mixing alphabets is shown as written in ASCII (its punycode), never in the letters that
        // imitate another site: Chromium's IDN display policy
        // (https://chromium.googlesource.com/chromium/src/+/main/docs/idn.md, "fall back to punycode").
        val show: (String) -> String = if (mixesLetters(decode(written))) { s -> s } else ::decode
        val domain = PublicSuffix.registrable(ascii)
        if (domain == null || !ascii.endsWith(domain)) return Host("", show(ascii), ascii)
        val prefix = ascii.removeSuffix(domain)
        return Host(show(prefix), show(domain), ascii)
    }

    private class Parsed(val scheme: String, val userInfo: String?, val host: String)

    private fun parse(url: String): Parsed? {
        val end = url.indexOf("://")
        if (end < 1) return null
        val scheme = url.substring(0, end).lowercase()
        if (scheme != "http" && scheme != "https") return null
        // A backslash ends the authority as a slash does (WHATWG URL, special schemes).
        val authority = url.substring(end + 3).takeWhile { it != '/' && it != '\\' && it != '?' && it != '#' }
        val at = authority.lastIndexOf('@')
        val userInfo = if (at >= 0) authority.substring(0, at) else null
        val hostPort = authority.substring(at + 1)
        val host = if (hostPort.startsWith("[")) hostPort.substringBefore(']') + "]" else hostPort.substringBefore(':')
        if (host.isEmpty()) return null
        return Parsed(scheme, userInfo, host.trimEnd('.'))
    }

    /** The host in its readable letters; as written when it cannot be converted. */
    private fun decode(host: String): String =
        runCatching { IDN.toUnicode(host, IDN.ALLOW_UNASSIGNED) }.getOrDefault(host).lowercase()

    /** An IPv6 address, a dotted or whole number, or a hexadecimal one: no site name (RFC 3986 3.2.2). */
    private fun isNumber(host: String): Boolean =
        host.startsWith("[") || host.all { it.isDigit() || it == '.' } || HEX_NUMBER.matches(host)

    /** A name that puts Latin letters with Cyrillic or Greek ones in one label, or the last two together. */
    private fun mixesLetters(decoded: String): Boolean = decoded.split('.').any { label ->
        val scripts = HashSet<Character.UnicodeScript>()
        label.codePoints().filter { Character.isLetter(it) }.forEach { scripts += Character.UnicodeScript.of(it) }
        val look = scripts.count {
            it == Character.UnicodeScript.LATIN || it == Character.UnicodeScript.CYRILLIC || it == Character.UnicodeScript.GREEK
        }
        look > 1
    }

    private val RICKROLL = Regex("youtu\\.?be.*dQw4w9WgXcQ")
    private val HEX_NUMBER = Regex("0x[0-9a-fA-F.x]+")
}
