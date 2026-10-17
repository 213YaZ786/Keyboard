package helium314.keyboard.latin.core.link

import java.net.URLDecoder
import java.util.Base64

/**
 * Turns the address a reader copied into the address it stands for. Pure, no
 * Android, no network.
 *
 * Links copied from a search engine or from another site are often not the
 * page itself but a detour through the site that showed them: Google's
 * /url?q=, Bing's click tracker, DuckDuckGo's, Facebook's and others. The real
 * address is written inside, so it is read out here, and the tracking
 * parameters that ride along are dropped. Google's newer /goto and /url links
 * with a coded address and share.google short links hide it; [needsResolving] says so, and
 * RedirectResolver asks Google where they lead.
 *
 * Shared across the apps of this base: edit Modules/shared, then run sync.sh.
 */
object LinkCleaner {

    /** The address behind [url], unwrapped and without tracking; [url] itself when it is not a link. */
    fun clean(url: String): String {
        var current = url.trim()
        repeat(MAX_WRAPPERS) {
            val inner = unwrap(current) ?: return stripTracking(current)
            current = inner
        }
        return stripTracking(current)
    }

    /** Whether [url] is a detour whose target cannot be read without asking its site. */
    fun needsResolving(url: String): Boolean {
        val link = parse(url) ?: return false
        return (isGoogle(link.host) && link.path == "/goto" && "url" in link.query) ||
            // Google's results now write the address coded in /url too.
            (isGoogle(link.host) && link.path == "/url" && (link.query["q"] ?: link.query["url"])?.let { !it.startsWith("http") } == true) ||
            link.host == "share.google"
    }

    private fun unwrap(url: String): String? {
        val link = parse(url) ?: return null
        val q = link.query
        val inner = when {
            isGoogle(link.host) && link.path == "/url" -> q["q"] ?: q["url"]
            link.host.endsWith("bing.com") && link.path.startsWith("/ck/") ->
                q["u"]?.takeIf { it.startsWith("a1") }?.let { decodeBase64(it.substring(2)) }
            link.host.endsWith("duckduckgo.com") && link.path.startsWith("/l/") -> q["uddg"]
            link.host in FACEBOOK_REDIRECTS -> q["u"]
            link.host.endsWith("youtube.com") && link.path == "/redirect" -> q["q"]
            link.host.endsWith("linkedin.com") && link.path.startsWith("/redir/") -> q["url"]
            link.host == "out.reddit.com" -> q["url"]
            else -> null
        }
        return inner?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }

    private fun stripTracking(url: String): String {
        val link = parse(url) ?: return url
        val cut = url.indexOf('?')
        if (cut < 0) return url
        val fragment = url.substringAfter('#', "").let { if (it.isEmpty()) "" else "#$it" }
        val kept = url.substring(cut + 1).substringBefore('#').split('&').filter { pair ->
            val name = pair.substringBefore('=').lowercase()
            name.isNotEmpty() && name !in TRACKING && !name.startsWith("utm_") &&
                !(isX(link.host) && name in X_SHARE)
        }
        return url.substring(0, cut) + (if (kept.isEmpty()) "" else "?" + kept.joinToString("&")) + fragment
    }

    private class Link(val host: String, val path: String, val query: Map<String, String>)

    private fun parse(url: String): Link? {
        val scheme = url.substringBefore("://", "").lowercase()
        if (scheme != "http" && scheme != "https") return null
        val rest = url.substringAfter("://")
        val host = rest.substringBefore('/').substringBefore('?').substringBefore('#').lowercase()
        val path = rest.substringAfter('/', "").substringBefore('?').substringBefore('#').let { "/$it" }
        val query = rest.substringAfter('?', "").substringBefore('#').split('&').mapNotNull { pair ->
            val name = pair.substringBefore('=')
            if (name.isEmpty()) null else name to decode(pair.substringAfter('=', ""))
        }.toMap()
        return Link(host, path, query)
    }

    private fun decode(value: String): String = runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

    private fun decodeBase64(value: String): String? =
        runCatching { String(Base64.getUrlDecoder().decode(value.trimEnd('=')), Charsets.UTF_8) }.getOrNull()

    private fun isGoogle(host: String) = host.removePrefix("www.").let { it.startsWith("google.") }

    private fun isX(host: String) = host.removePrefix("www.").removePrefix("mobile.").let { it == "x.com" || it == "twitter.com" }

    private const val MAX_WRAPPERS = 3
    private val FACEBOOK_REDIRECTS = setOf("l.facebook.com", "lm.facebook.com", "l.instagram.com", "l.threads.net")
    private val TRACKING = setOf(
        "fbclid", "gclid", "dclid", "gbraid", "wbraid", "msclkid", "igshid", "igsh", "mc_cid", "mc_eid",
        "ref_src", "ref_url", "trk", "trkinfo", "trackingid", "lipi", "si", "_hsenc", "_hsmi", "yclid", "rcm"
    )
    /** X's share tokens: they say who shared, not what is shared. */
    private val X_SHARE = setOf("s", "t")
}
