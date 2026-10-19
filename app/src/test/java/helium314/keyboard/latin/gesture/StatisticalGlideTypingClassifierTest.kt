// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.gesture

import helium314.keyboard.latin.NgramContext
import helium314.keyboard.latin.SuggestedWords.SuggestedWordInfo
import helium314.keyboard.latin.common.ComposedData
import helium314.keyboard.latin.dictionary.Dictionary
import helium314.keyboard.latin.settings.SettingsValuesForSuggestion
import java.util.ArrayList
import java.util.Locale
import java.util.function.BiConsumer
import kotlin.math.hypot
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * FlorisBoard has no test for its classifier (searched app/src/test of
 * https://github.com/florisboard/florisboard): the ideal path of a known word on a QWERTY grid is
 * given to the classifier, which must return that word first.
 */
class StatisticalGlideTypingClassifierTest {
    private val keyWidth = 100f
    private val keyHeight = 150f

    // QWERTY, 100 x 150 keys, the rows shifted as on a phone
    private val keys: List<GlideKey> = listOf("qwertyuiop" to 0f, "asdfghjkl" to 50f, "zxcvbnm" to 150f)
        .withIndex().flatMap { (row, rowAndOffset) ->
            val (letters, offset) = rowAndOffset
            letters.mapIndexed { i, c ->
                GlideKey(c.code, offset + i * keyWidth + keyWidth / 2, row * keyHeight + keyHeight / 2, keyWidth, keyHeight)
            }
        }

    private fun center(c: Char): Pair<Float, Float> = keys.first { it.code == c.lowercaseChar().code }.let { it.centerX to it.centerY }

    /** The points of a finger going through the keys of [letters] in a straight line each, a point every [step] px. */
    private fun path(letters: String, step: Float = 30f, noise: Float = 0f, random: Random = Random(7)): List<Pair<Float, Float>> {
        val points = ArrayList<Pair<Float, Float>>()
        val centers = letters.map { center(it) }
        points.add(centers[0])
        for (i in 1 until centers.size) {
            val (x0, y0) = centers[i - 1]
            val (x1, y1) = centers[i]
            val length = hypot(x1 - x0, y1 - y0)
            val steps = (length / step).toInt().coerceAtLeast(1)
            for (s in 1..steps) {
                val t = s.toFloat() / steps
                points.add((x0 + (x1 - x0) * t) to (y0 + (y1 - y0) * t))
            }
        }
        return points.map { (x, y) ->
            if (noise == 0f) x to y else (x + (random.nextFloat() * 2 - 1) * noise) to (y + (random.nextFloat() * 2 - 1) * noise)
        }
    }

    private fun classifier(words: Map<String, Int>): StatisticalGlideTypingClassifier =
        StatisticalGlideTypingClassifier().apply {
            setLayout(keys)
            setWordData(words.keys.toList()) { words[it] ?: 0 }
        }

    private fun StatisticalGlideTypingClassifier.glide(points: List<Pair<Float, Float>>, count: Int = 8): List<String> {
        clear()
        points.forEach { (x, y) -> addGesturePoint(x, y) }
        return getSuggestions(count)
    }

    @Test fun idealPathOfAWordReturnsThatWordFirst() {
        val words = listOf("hello", "hella", "hells", "jello", "help", "hold", "held", "hero", "hollow", "halo", "he")
            .associateWith { 100 }
        val result = classifier(words).glide(path("helo")) // the finger crosses l once, hello has the loop on l
        assertEquals("hello", result.first(), result.toString())
    }

    @Test fun otherWordsOfTheSameStartAndEndAreFarther() {
        val words = listOf("world", "would", "word", "wold", "sword", "wild", "wood", "wool").associateWith { 100 }
        val result = classifier(words).glide(path("world"))
        assertEquals("world", result.first(), result.toString())
    }

    @Test fun aSlightlyOffGestureStillFindsTheWord() {
        val words = listOf("world", "would", "word", "wold", "wild", "wood", "wool", "hello", "great", "greet", "grab")
            .associateWith { 100 }
        val c = classifier(words)
        assertEquals("great", c.glide(path("great", noise = 14f)).first())
        assertEquals("world", c.glide(path("world", noise = 14f)).first())
    }

    @Test fun frequencyDecidesBetweenWordsOfTheSamePath() {
        // no key for the accent: both words have the same ideal gesture (the base letter's key)
        assertEquals("café", classifier(mapOf("café" to 200, "cafe" to 10)).glide(path("cafe")).first())
        assertEquals("cafe", classifier(mapOf("café" to 10, "cafe" to 200)).glide(path("cafe")).first())
    }

    @Test fun aWordWithACapitalIsFound() {
        assertEquals("Paris", classifier(mapOf("Paris" to 100, "parts" to 5)).glide(path("paris")).first())
    }

    @Test fun nothingBeforeLayoutAndWordsAreSet() {
        val c = StatisticalGlideTypingClassifier()
        assertFalse(c.ready)
        assertTrue(c.glide(path("hello")).isEmpty())
        c.setLayout(keys)
        assertFalse(c.ready)
        c.setWordData(listOf("hello")) { 1 }
        assertTrue(c.ready)
        assertEquals(listOf("hello"), c.glide(path("hello")))
    }

    @Test fun emptyAndSinglePointGesturesDoNotCrash() {
        val c = classifier(mapOf("a" to 100, "hello" to 100))
        assertTrue(c.glide(emptyList()).isEmpty())
        c.glide(listOf(center('a')))
        c.glide(listOf(center('a'), center('a')))
        c.glide(path("qwertyuiopasdfghjklzxcvbnmqwertyuiopasdfghjklzxcvbnm", step = 5f)) // far over 500 points
    }

    @Test fun theSameGestureGivesTheSameSuggestionsFromTheCache() {
        val c = classifier(mapOf("hello" to 100, "hero" to 100, "help" to 100))
        val points = path("hello")
        assertEquals(c.glide(points), c.glide(points))
    }

    @Test fun maxSuggestionCountIsKept() {
        val words = listOf("hello", "hells", "hallo", "hullo", "hillo", "hollo", "helio", "hellp", "heklo", "hrllo", "hwllo", "hello")
            .associateWith { 100 }
        assertTrue(classifier(words).glide(path("hello"), count = 3).size <= 3)
    }

    @Test fun glideWordsKeepTheMostFrequentAndTheBestProbability() {
        val dictionary = object : Dictionary(TYPE_MAIN, Locale.ENGLISH) {
            override fun getSuggestions(
                composedData: ComposedData, ngramContext: NgramContext, proximityInfoHandle: Long,
                settingsValuesForSuggestion: SettingsValuesForSuggestion, sessionId: Int, weightForLocale: Float,
                inOutWeightOfLangModelVsSpatialModel: FloatArray?
            ): ArrayList<SuggestedWordInfo>? = null

            override fun isInDictionary(word: String): Boolean = false

            override fun forEachWord(skipOffensive: Boolean, consumer: BiConsumer<String, Int>) {
                consumer.accept("rare", 5)
                consumer.accept("common", 250)
                consumer.accept("middle", 100)
                consumer.accept("rare", 9) // a second dictionary of the collection
                consumer.accept("bad", -1)
                consumer.accept("", 100)
            }
        }
        val words = GlideWords.build(dictionary, skipOffensive = false, maxWords = 2)
        assertEquals(listOf("common", "middle"), words.words)
        assertEquals(250, words.frequencyOf("common"))
        assertEquals(0, words.frequencyOf("rare"))
        val all = GlideWords.build(dictionary, skipOffensive = false)
        assertEquals(listOf("common", "middle", "rare"), all.words)
        assertEquals(9, all.frequencyOf("rare"))
    }
}
