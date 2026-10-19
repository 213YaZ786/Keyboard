package helium314.keyboard.latin.core.link

import android.content.Context
import android.content.res.AssetManager
import java.net.URLDecoder
import java.util.regex.Pattern
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Removes tracking from an address and unwraps the redirects of known sites,
 * with the ClearURLs rules catalog shipped in the app (assets/links/clearurls.json).
 * Offline: nothing is sent anywhere and the catalog is never downloaded.
 *
 * Port of URLCheck's ClearUrlModule, its cleaning only (providers, urlPattern,
 * exceptions, redirections, rawRules, rules, referralMarketing, then the
 * clean-up of what the removals leave behind), by TrianguloY, licence CC BY 4.0:
 * https://github.com/TrianguloY/URLCheck/blob/master/app/src/main/java/com/trianguloy/urlchecker/modules/list/ClearUrlModule.java
 * and the catalog loading of
 * https://github.com/TrianguloY/URLCheck/blob/master/app/src/main/java/com/trianguloy/urlchecker/modules/companions/ClearUrlCatalog.java
 * The same rules are applied by the ClearURLs extension itself
 * (https://github.com/ClearURLs/Addon/blob/master/core_js/pureCleaning.js).
 *
 * The rules are the ClearURLs project's, https://github.com/ClearURLs/Rules
 * (LGPL-3.0), as bundled by URLCheck.
 *
 * Changes from URLCheck: referral marketing parameters are removed unless
 * [inspect] is told otherwise (URLCheck's setting is off by default, which
 * removes them too); a redirection is followed only to an http or https
 * address, and a provider whose removals leave no http or https address
 * changes nothing (URLCheck then writes "http://" in front); an address over
 * [MAX_LENGTH] characters is left alone, since a pattern of the catalog could
 * take long on it; no editor, no downloaded updates; the catalog is parsed
 * with kotlinx json and a broken rule skips its provider.
 *
 * Shared across the apps of this base: edit Modules/shared, then run sync.sh.
 */
object ClearUrls {

    /**
     * What cleaning gave. [redirected]: a known site's detour was replaced by the address
     * it carried. [stripped]: tracking parameters were removed. [tracker]: the name of the
     * catalog's provider when the address is a domain made only to track or advertise
     * (the catalog's completeProvider), else null.
     */
    class Cleaned(val url: String, val redirected: Boolean, val stripped: Boolean, val tracker: String?)

    /** The parsed catalog: one entry per provider, in the catalog's order. */
    class Catalog internal constructor(internal val providers: List<Provider>)

    /** One provider of the catalog; only its urlPattern is compiled until it matches. */
    internal class Provider(val name: String, data: JsonObject) {
        val urlPattern: Pattern = insensitive(data.text("urlPattern") ?: error("no urlPattern"))
        val complete: Boolean = data["completeProvider"]?.jsonPrimitive?.booleanOrNull == true
        private val exceptionsText = data.texts("exceptions")
        private val redirectionsText = data.texts("redirections")
        private val rawRulesText = data.texts("rawRules")
        private val rulesText = data.texts("rules")
        private val referralText = data.texts("referralMarketing")

        val exceptions: List<Pattern> by lazy { exceptionsText.map(::insensitive) }
        // URLCheck compiles the redirections as written, case sensitive.
        val redirections: List<Pattern> by lazy { redirectionsText.map { Pattern.compile(it) } }
        val rawRules: List<Pattern> by lazy { rawRulesText.map(::insensitive) }
        // A parameter named by the rule, its value up to the next separator.
        val rules: List<Pattern> by lazy { rulesText.map { insensitive("([?&#])$it=[^&#]*") } }
        val referral: List<Pattern> by lazy { referralText.map { insensitive("([?&#])$it=[^&#]*") } }
    }

    /** The catalog of [json] (ClearURLs' data.minify.json); a provider that does not parse is left out. */
    fun parse(json: String): Catalog {
        val providers = ArrayList<Provider>()
        // URLCheck's getRules: every top-level object holds providers.
        for ((_, group) in Json.parseToJsonElement(json).jsonObject) {
            val groupObject = runCatching { group.jsonObject }.getOrNull() ?: continue
            for ((name, data) in groupObject) {
                runCatching { Provider(name, data.jsonObject) }.getOrNull()?.let(providers::add)
            }
        }
        return Catalog(providers)
    }

    /**
     * [url] cleaned by [catalog]: each provider whose urlPattern matches is applied in turn, as
     * URLCheck's ClearUrlModule.onModifyUrl. [allowReferral] keeps the referral marketing
     * parameters (URLCheck's "allow referral marketing", off by default).
     */
    fun inspect(url: String, catalog: Catalog?, allowReferral: Boolean = false): Cleaned {
        if (catalog == null || url.length > MAX_LENGTH || !isWeb(url)) return Cleaned(url, false, false, null)
        var cleared = url
        var redirected = false
        var stripped = false
        var tracker: String? = null
        providers@ for (provider in catalog.providers) {
            val before = cleared
            try {
                if (!provider.urlPattern.matcher(cleared).find()) continue
                // A domain made only to track: the catalog calls it a complete provider.
                if (provider.complete) {
                    if (tracker == null) tracker = provider.name
                    continue
                }
                if (provider.exceptions.any { it.matcher(cleared).find() }) continue

                for (redirection in provider.redirections) {
                    val m = redirection.matcher(cleared)
                    if (m.find() && m.groupCount() >= 1) {
                        val target = m.group(1)?.let(::decodeComponent)
                        // Only a web address is followed.
                        if (target != null && isWeb(target)) {
                            cleared = target
                            redirected = true
                            continue@providers
                        }
                    }
                }

                var changed = false
                for (raw in provider.rawRules) {
                    val m = raw.matcher(cleared)
                    if (m.find()) {
                        cleared = m.replaceAll("")
                        changed = true
                    }
                }
                val parameters = if (allowReferral) provider.rules else provider.rules + provider.referral
                for (rule in parameters) {
                    while (true) {
                        val m = rule.matcher(cleared)
                        if (!m.find()) break
                        cleared = m.replaceFirst("\$1")
                        changed = true
                    }
                }

                if (changed) {
                    cleared = mend(cleared)
                    // What the removals left is no web address: this provider changes nothing.
                    if (!isWeb(cleared)) cleared = before else stripped = true
                }
            } catch (_: RuntimeException) {
                // A rule that does not compile or run: the provider is skipped.
                cleared = before
            }
        }
        return Cleaned(cleared, redirected, stripped, tracker)
    }

    /** [url] cleaned with the app's catalog ([attach]), else unchanged. */
    fun clean(url: String, allowReferral: Boolean = false): String = inspect(url, current(), allowReferral).url

    /** [url] cleaned with the app's catalog, and what the cleaning found. */
    fun inspect(url: String, allowReferral: Boolean = false): Cleaned = inspect(url, current(), allowReferral)

    /**
     * Gives the catalog its place: the app's assets. Called once from Application.onCreate; the
     * catalog is read at once on a short thread of its own, so the first link is not waited for.
     * Not called, the catalog is empty and [clean] changes nothing (unit tests, other processes).
     * It gives PublicSuffix its list too: one line in the application, for the whole links kit.
     */
    fun attach(context: Context) {
        PublicSuffix.attach(context)
        assets = context.applicationContext.assets
        Thread { current() }.apply { isDaemon = true }.start()
    }

    /**
     * Puts [catalog] in place of the app's, for tests of what uses it (LinkCleanerTest); null empties it.
     * The catalog URLCheck's ClearUrlCatalog.getCatalog returns, set from outside
     * (https://github.com/TrianguloY/URLCheck/blob/master/app/src/main/java/com/trianguloy/urlchecker/modules/companions/ClearUrlCatalog.java).
     */
    internal fun install(catalog: Catalog?) {
        loaded = catalog
    }

    // The artefacts of removals (URLCheck: "remove empty elements").
    private val MEND = listOf(
        Regex("\\?&+") to "?", Regex("\\?#") to "#", Regex("\\?$") to "",
        Regex("&&+") to "&", Regex("&#") to "#", Regex("&$") to "",
        Regex("#&+") to "#", Regex("#$") to ""
    )

    private fun mend(url: String): String = MEND.fold(url) { text, (pattern, with) -> pattern.replace(text, with) }

    private fun isWeb(text: String) = text.startsWith("http://", ignoreCase = true) || text.startsWith("https://", ignoreCase = true)

    private fun insensitive(regex: String): Pattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE)

    private fun JsonObject.text(key: String): String? = this[key]?.jsonPrimitive?.content

    private fun JsonObject.texts(key: String): List<String> = this[key]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()

    /**
     * Hopefully the same as javascript's decodeURIComponent (URLCheck's ClearUrlModule): a plus
     * stays a plus, which URLDecoder alone would turn into a space. A broken escape gives null.
     */
    private fun decodeComponent(text: String): String? = runCatching {
        text.split('+').joinToString("+") { URLDecoder.decode(it, "UTF-8") }
    }.getOrNull()

    @Volatile private var assets: AssetManager? = null
    @Volatile private var loaded: Catalog? = null

    private fun current(): Catalog? = loaded ?: synchronized(this) {
        loaded ?: assets?.let { manager ->
            // URLCheck's ClearUrlCatalog.getBuiltIn: the file shipped in the assets.
            runCatching { parse(manager.open(ASSET).bufferedReader().use { it.readText() }) }.getOrNull()
        }?.also { loaded = it }
    }

    private const val ASSET = "links/clearurls.json"

    /** Longest address cleaned. */
    const val MAX_LENGTH = 8192
}
