package helium314.keyboard.latin.core.link

/**
 * What goes into the search field when the reader taps paste. Pure, no Android,
 * so it can be run outside a build.
 *
 * The clipboard rarely holds a bare address. A browser's address bar does, but
 * a network's own share sheet copies a sentence with the link inside it, and a
 * long press on a link in a page can bring back the surrounding line. So the
 * address is pulled out of whatever came, rather than assumed to be all of it.
 *
 * Nothing is rejected here. A text with no address in it is handed on as it
 * stands, so the field can say what is wrong with it in its own words.
 *
 * Shared across the apps of this base: edit Modules/shared, then run sync.sh.
 * Each app says which schemeless words are links to its network.
 */
object PastedText {

    /** Leading wrappers a copied line drags along. */
    private const val OPENERS = "(<[{\"'«‹"

    /** Trailing wrappers and sentence punctuation. */
    private const val CLOSERS = ")>]},.;:!?\"'»›"

    private val URL = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)

    fun query(raw: String, isNetworkLink: (String) -> Boolean): String {
        val text = raw.trim()
        if (text.isEmpty()) return ""

        // A detour through a search engine or another site is unwrapped to
        // the page it stands for, and tracking is dropped, see LinkCleaner.
        URL.find(text)?.value?.let { return LinkCleaner.clean(trimWrappers(it)) }

        // No scheme. The address bar on some browsers hides it, and people
        // type the address without it too.
        text.split(' ', '\t', '\n', '\r')
            .firstOrNull { isNetworkLink(trimWrappers(it).lowercase()) }
            ?.let { return trimWrappers(it) }

        // Not an address at all. Keep the first line with something on it: a
        // name pasted alone is a valid query, a paragraph is not, and the
        // field explains that better than a silent refusal would.
        return text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    }

    private fun trimWrappers(token: String): String =
        token.trimStart(*OPENERS.toCharArray()).trimEnd(*CLOSERS.toCharArray())
}
