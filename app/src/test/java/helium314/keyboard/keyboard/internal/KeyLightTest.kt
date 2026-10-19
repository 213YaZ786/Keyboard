// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard.internal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Source: plain JUnit 4 on the JVM for a pure function, as this app's ClipboardPrivacyTest
// (https://junit.org/junit4/); the values are KeyLight.lit's own curve (sine easing, staggered delay).
class KeyLightTest {
    private val width = 1000f

    @Test fun `nothing glows before the wave and after it`() {
        for (x in listOf(0f, 500f, 1000f)) {
            assertEquals(0f, KeyLight.lit(KeyLight.Mode.WAVE, 0f, x, width), 0.0001f)
            assertEquals(0f, KeyLight.lit(KeyLight.Mode.WAVE, 1f, x, width), 0.001f)
        }
    }

    @Test fun `the wave reaches the left keys before the right ones`() {
        val left = (0..100).first { KeyLight.lit(KeyLight.Mode.WAVE, it / 100f, 50f, width) > 0.5f }
        val middle = (0..100).first { KeyLight.lit(KeyLight.Mode.WAVE, it / 100f, 500f, width) > 0.5f }
        val right = (0..100).first { KeyLight.lit(KeyLight.Mode.WAVE, it / 100f, 950f, width) > 0.5f }
        assertTrue(left < middle && middle < right, "$left $middle $right")
    }

    @Test fun `every key reaches full light once in the wave`() {
        for (x in listOf(0f, 250f, 500f, 750f, 1000f)) {
            val peak = (0..1000).maxOf { KeyLight.lit(KeyLight.Mode.WAVE, it / 1000f, x, width) }
            assertEquals(1f, peak, 0.01f)
        }
    }

    @Test fun `a key outside the keyboard is clamped, never out of range`() {
        for (p in 0..100) for (x in listOf(-500f, 5000f)) {
            val v = KeyLight.lit(KeyLight.Mode.WAVE, p / 100f, x, width)
            assertTrue(v in 0f..1f)
        }
    }

    @Test fun `together, all keys glow at the same time, quick rise and slow fade`() {
        for (x in listOf(0f, 400f, 1000f)) {
            assertEquals(KeyLight.lit(KeyLight.Mode.TOGETHER, 0.3f, 0f, width), KeyLight.lit(KeyLight.Mode.TOGETHER, 0.3f, x, width), 0f)
        }
        assertEquals(0f, KeyLight.lit(KeyLight.Mode.TOGETHER, 0f, 10f, width), 0.001f)
        assertEquals(1f, KeyLight.lit(KeyLight.Mode.TOGETHER, 0.2f, 10f, width), 0.001f)
        assertEquals(0f, KeyLight.lit(KeyLight.Mode.TOGETHER, 1f, 10f, width), 0.001f)
        // the fade is slower than the rise: still bright at the middle of the time
        assertTrue(KeyLight.lit(KeyLight.Mode.TOGETHER, 0.6f, 10f, width) > 0.3f)
    }
}
