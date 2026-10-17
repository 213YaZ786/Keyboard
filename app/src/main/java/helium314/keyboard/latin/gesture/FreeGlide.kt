// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.gesture

import helium314.keyboard.keyboard.Keyboard
import helium314.keyboard.latin.common.InputPointers
import helium314.keyboard.latin.dictionary.Dictionary
import helium314.keyboard.latin.utils.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.WeakHashMap

/**
 * Glide typing without Google's closed library, linking the keyboard and the dictionaries with
 * [StatisticalGlideTypingClassifier]. Port of FlorisBoard's GlideTypingManager (MAX_SUGGESTION_COUNT,
 * classifier fed with the gesture, suggestions asked while gliding and at the end, the first one
 * committed by the caller as HeliBoard does for the native engine):
 * https://github.com/florisboard/florisboard/blob/main/app/src/main/kotlin/dev/patrickgold/florisboard/ime/text/gestures/GlideTypingManager.kt
 * (Apache-2.0).
 *
 * Differences from Floris: HeliBoard already calls it from its own non UI thread for each update of
 * the gesture (InputLogicHandler), so there is no coroutine per update; the word lists are built
 * once per dictionary, off the main thread (Floris reads its NlpManager); several languages are
 * merged by confidence.
 */
object FreeGlide {
    /** The engine is built in: glide typing is available without Google's library. */
    const val AVAILABLE = true

    // Source: Floris' GlideTypingManager.MAX_SUGGESTION_COUNT
    const val MAX_SUGGESTION_COUNT = 8

    /** A main dictionary to look words up in, and the words the user removed from it. */
    class Source(val dictionary: Dictionary, val isBlacklisted: (String) -> Boolean)

    class Suggestion(val word: String, val source: Dictionary)

    /** The word list and the classifier of one dictionary. Every access is under its lock. */
    private class Slot {
        var started = false
        var skipOffensive = false
        var generation = 0
        var words: GlideWords? = null
        var classifier: StatisticalGlideTypingClassifier? = null
    }

    // a reloaded dictionary is a new object: its old slot disappears with it
    private val slots = WeakHashMap<Dictionary, Slot>()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private fun slotFor(dictionary: Dictionary): Slot = synchronized(slots) { slots.getOrPut(dictionary) { Slot() } }

    /**
     * Starts reading the words of [dictionary] in the background, unless done or started already
     * (Floris' setWordData, called ahead so the first gesture finds the list ready).
     */
    fun prepare(dictionary: Dictionary, skipOffensive: Boolean) {
        val slot = slotFor(dictionary)
        val generation: Int
        synchronized(slot) {
            if (slot.started && slot.skipOffensive == skipOffensive) return
            slot.started = true
            slot.skipOffensive = skipOffensive
            slot.words = null
            slot.classifier = null
            generation = ++slot.generation
        }
        scope.launch {
            val words = try {
                val start = System.currentTimeMillis()
                GlideWords.build(dictionary, skipOffensive).also {
                    Log.i(TAG, "glide word list: ${it.words.size} words in ${System.currentTimeMillis() - start} ms")
                }
            } catch (e: Throwable) {
                Log.e(TAG, "could not read the words of $dictionary", e)
                null
            }
            synchronized(slot) {
                if (slot.generation == generation) slot.words = words
            }
        }
    }

    /**
     * The best words for the gesture in [pointers] (keyboard coordinates), best first, at most
     * [MAX_SUGGESTION_COUNT]. Empty while the word lists are being built.
     * Source: Floris' GlideTypingManager.updateSuggestionsAsync (getSuggestions of the classifier fed
     * with the gesture's points).
     */
    fun suggest(sources: List<Source>, keyboard: Keyboard, pointers: InputPointers, skipOffensive: Boolean): List<Suggestion> {
        val size = pointers.pointerSize
        val keys = keysOf(keyboard)
        if (size == 0 || keys.isEmpty()) return emptyList()
        val xs = pointers.xCoordinates
        val ys = pointers.yCoordinates

        val found = ArrayList<Pair<Float, Suggestion>>()
        for (source in sources) {
            prepare(source.dictionary, skipOffensive)
            val slot = slotFor(source.dictionary)
            val candidates = synchronized(slot) {
                val words = slot.words
                if (words == null) emptyList()
                else {
                    val classifier = slot.classifier
                        ?: StatisticalGlideTypingClassifier().also {
                            it.setWordData(words.words, words::frequencyOf)
                            slot.classifier = it
                        }
                    classifier.setLayout(keys)
                    classifier.clear()
                    classifier.initGestureFromPoints(xs, ys, size)
                    classifier.getCandidates(MAX_SUGGESTION_COUNT)
                }
            }
            for (candidate in candidates) {
                if (!source.isBlacklisted(candidate.word))
                    found.add(candidate.confidence to Suggestion(candidate.word, source.dictionary))
            }
        }
        // the smaller the confidence, the better; the same word from two languages once
        return found.sortedBy { it.first }.map { it.second }.distinctBy { it.word }.take(MAX_SUGGESTION_COUNT)
    }

    /**
     * The letter keys of the keyboard, as the classifier wants them. Floris uses its TextKeys with
     * their visible bounds: here a key's x, y, width and height (the gaps excluded).
     */
    fun keysOf(keyboard: Keyboard): List<GlideKey> {
        val keys = ArrayList<GlideKey>()
        val seen = HashSet<Int>()
        for (key in keyboard.sortedKeys) {
            val code = key.code
            if (code <= 0 || !Character.isLetter(code)) continue
            val lower = Character.toLowerCase(code)
            if (!seen.add(lower)) continue
            keys.add(GlideKey(lower, key.x + key.width / 2f, key.y + key.height / 2f, key.width.toFloat(), key.height.toFloat()))
        }
        return keys
    }

    private const val TAG = "FreeGlide"
}
