package helium314.keyboard.latin.core.link

import android.content.Context
import android.content.res.AssetManager

/**
 * The registrable domain of a host: the part a person or a company registered, in
 * `mail.example.co.uk` it is `example.co.uk`, in `evil.github.io` it is `evil.github.io`.
 * Offline, from the Public Suffix List (https://publicsuffix.org, MPL-2.0) shipped in the app
 * (assets/links/public_suffix_list.txt, the ICANN and private sections, one-label rules left
 * out since the default rule "*" gives them).
 *
 * The matching is the list's own algorithm (https://github.com/publicsuffix/list/wiki/Format#algorithm:
 * the exception rule wins, else the rule with the most labels, else "*"), as
 * https://github.com/square/okhttp/blob/master/okhttp/src/main/kotlin/okhttp3/internal/publicsuffix/PublicSuffixDatabase.kt
 * (Apache-2.0) does it, here over sets of rules instead of its binary file.
 *
 * Shared across the apps of this base: edit Modules/shared, then run sync.sh.
 */
object PublicSuffix {

    /** The rules of the list, as three sets of dotted names. */
    class Rules internal constructor(
        internal val plain: Set<String>,
        /** The parents of the `*.parent` rules. */
        internal val wildcardParents: Set<String>,
        /** The `!name` rules, without the `!`. */
        internal val exceptions: Set<String>
    )

    /** The rules written in [text], one per line, `#` for a comment line. */
    fun parse(text: String): Rules {
        val plain = HashSet<String>()
        val wildcard = HashSet<String>()
        val exceptions = HashSet<String>()
        text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.forEach { rule ->
            when {
                rule.startsWith("!") -> exceptions += rule.substring(1)
                rule.startsWith("*.") -> wildcard += rule.substring(2)
                else -> plain += rule
            }
        }
        return Rules(plain, wildcard, exceptions)
    }

    /**
     * The registrable domain of [host] (lower case, dots, an internationalised name in its
     * punycode form), or null when [host] is itself a public suffix, has one label, or the
     * rules are not there.
     */
    fun registrable(host: String, rules: Rules? = current()): String? {
        if (rules == null) return null
        val labels = host.trimEnd('.').lowercase().split('.')
        if (labels.size < 2 || labels.any { it.isEmpty() }) return null
        var suffixLabels = 1 // the default rule "*"
        for (from in labels.indices) {
            val candidate = labels.drop(from).joinToString(".")
            // The exception rule prevails: the suffix is the rule less its first label.
            if (candidate in rules.exceptions) {
                suffixLabels = labels.size - from - 1
                break
            }
            // The rule with the most labels is the one starting furthest left.
            val matches = candidate in rules.plain ||
                (labels.size - from >= 2 && candidate.substringAfter('.') in rules.wildcardParents)
            if (matches) {
                suffixLabels = labels.size - from
                break
            }
        }
        if (suffixLabels >= labels.size) return null
        return labels.takeLast(suffixLabels + 1).joinToString(".")
    }

    /**
     * Gives the list its place: the app's assets, read at once on a short thread of its own so the
     * first link sheet is not waited for. Called from ClearUrls.attach, in Application.onCreate.
     */
    fun attach(context: Context) {
        assets = context.applicationContext.assets
        Thread { current() }.apply { isDaemon = true }.start()
    }

    /**
     * Puts [rules] in place of the app's, for tests of what uses them (LinkWarningsTest); null empties it.
     * The list OkHttp's PublicSuffixDatabase.get() hands out, set from outside
     * (https://github.com/square/okhttp/blob/master/okhttp/src/main/kotlin/okhttp3/internal/publicsuffix/PublicSuffixDatabase.kt).
     */
    internal fun install(rules: Rules?) {
        loaded = rules
    }

    @Volatile private var assets: AssetManager? = null
    @Volatile private var loaded: Rules? = null

    private fun current(): Rules? = loaded ?: synchronized(this) {
        loaded ?: assets?.let { manager ->
            runCatching { parse(manager.open(ASSET).bufferedReader().use { it.readText() }) }.getOrNull()
        }?.also { loaded = it }
    }

    private const val ASSET = "links/public_suffix_list.txt"
}
