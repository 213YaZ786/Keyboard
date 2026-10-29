package helium314.keyboard.latin.core.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Local JVM tests of the pure parts, as Android documents them
// (https://developer.android.com/training/testing/local-tests), in the form of
// MediaCheckTest: the cases are the edges of each rule (the clamp of
// Subsampling Scale Image View's viewToFileRect, https://github.com/davemorrissey/subsampling-scale-image-view,
// SubsamplingScaleImageView.java; BitmapFactory.Options.inSampleSize,
// https://developer.android.com/reference/android/graphics/BitmapFactory.Options#inSampleSize;
// Gainmap's fields, https://developer.android.com/reference/android/graphics/Gainmap)
// and the hostile values (empty, reversed, huge, NaN) the OWASP MASVS pass asks for.
class DecoderLimitsTest {

    private fun one(v: Float) = floatArrayOf(v, v, v)

    private fun sane(
        ratioMin: FloatArray = one(1f), ratioMax: FloatArray = one(4f), gamma: FloatArray = one(1f),
        epsilonSdr: FloatArray = one(0.015625f), epsilonHdr: FloatArray = one(0.015625f),
        full: Float = 4f, min: Float = 1f,
    ) = DecoderLimits.gainmapSane(ratioMin, ratioMax, gamma, epsilonSdr, epsilonHdr, full, min)

    @Test
    fun `a rect is cut to the picture`() {
        assertArrayEquals(intArrayOf(0, 0, 100, 50), DecoderLimits.clamp(0, 0, 100, 50, 100, 50))
        assertArrayEquals(intArrayOf(0, 0, 100, 50), DecoderLimits.clamp(-20, -5, 500, 900, 100, 50))
        assertArrayEquals(intArrayOf(10, 20, 30, 40), DecoderLimits.clamp(10, 20, 30, 40, 100, 50))
        assertArrayEquals(intArrayOf(90, 40, 100, 50), DecoderLimits.clamp(90, 40, 110, 60, 100, 50))
    }

    @Test
    fun `a rect with nothing inside is refused`() {
        assertNull(DecoderLimits.clamp(100, 0, 200, 50, 100, 50))
        assertNull(DecoderLimits.clamp(-50, 0, 0, 50, 100, 50))
        assertNull(DecoderLimits.clamp(10, 10, 10, 20, 100, 50))
        assertNull(DecoderLimits.clamp(30, 10, 20, 40, 100, 50))
        assertNull(DecoderLimits.clamp(0, 0, 10, 10, 0, 0))
    }

    @Test
    fun `extreme numbers do not overflow the clamp`() {
        assertArrayEquals(intArrayOf(0, 0, 100, 50), DecoderLimits.clamp(Int.MIN_VALUE, Int.MIN_VALUE, Int.MAX_VALUE, Int.MAX_VALUE, 100, 50))
        assertNull(DecoderLimits.clamp(Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE, 100, 50))
        assertNull(DecoderLimits.clamp(Int.MIN_VALUE, 0, Int.MIN_VALUE, 10, 100, 50))
    }

    @Test
    fun `a sample size is a power of two from 1 to 64`() {
        listOf(1, 2, 4, 8, 16, 32, 64).forEach { assertTrue("$it", DecoderLimits.isSampleSize(it)) }
        listOf(0, -1, -2, 3, 5, 6, 7, 12, 48, 63, 65, 100, 128, 1 shl 30, Int.MIN_VALUE, Int.MAX_VALUE)
            .forEach { assertFalse("$it", DecoderLimits.isSampleSize(it)) }
    }

    @Test
    fun `a side at a sample size is rounded up and never zero`() {
        assertEquals(100, DecoderLimits.sampledSide(100, 1))
        assertEquals(50, DecoderLimits.sampledSide(100, 2))
        assertEquals(26, DecoderLimits.sampledSide(101, 4))
        assertEquals(1, DecoderLimits.sampledSide(1, 64))
        assertEquals(1, DecoderLimits.sampledSide(0, 8))
        assertEquals(Int.MAX_VALUE / 2 + 1, DecoderLimits.sampledSide(Int.MAX_VALUE, 2))
    }

    @Test
    fun `a tile within the caps fits`() {
        assertTrue(DecoderLimits.tileFits(1024, 1024, 1))
        assertTrue(DecoderLimits.tileFits(4096, 2048, 1))
        assertTrue(DecoderLimits.tileFits(30_000, 30_000, 16))
        assertFalse(DecoderLimits.tileFits(30_000, 30_000, 8))
        assertTrue(DecoderLimits.tileFits(1, 1, 64))
    }

    @Test
    fun `a tile past a side or the pixel count does not`() {
        assertFalse(DecoderLimits.tileFits(4097, 10, 1))
        assertFalse(DecoderLimits.tileFits(4096, 2049, 1))
        assertFalse(DecoderLimits.tileFits(4096, 4096, 1))
        assertFalse(DecoderLimits.tileFits(30_000, 30_000, 4))
        assertTrue(DecoderLimits.tileFits(4097, 10, 2))
    }

    @Test
    fun `an empty tile or a wrong sample size does not fit`() {
        assertFalse(DecoderLimits.tileFits(0, 100, 1))
        assertFalse(DecoderLimits.tileFits(100, -1, 1))
        assertFalse(DecoderLimits.tileFits(100, 100, 3))
        assertFalse(DecoderLimits.tileFits(100, 100, 0))
        assertFalse(DecoderLimits.tileFits(100, 100, 128))
    }

    @Test
    fun `a gain map is capped like the picture`() {
        assertTrue(DecoderLimits.gainmapFits(1, 1))
        assertTrue(DecoderLimits.gainmapFits(4096, 4096))
        assertFalse(DecoderLimits.gainmapFits(4097, 100))
        assertFalse(DecoderLimits.gainmapFits(100, 4097))
        assertFalse(DecoderLimits.gainmapFits(0, 100))
        assertFalse(DecoderLimits.gainmapFits(100, -3))
    }

    @Test
    fun `a gain map's numbers are taken when they are real ones`() {
        assertTrue(sane())
        assertTrue(sane(ratioMin = floatArrayOf(0.5f, 1f, 2f), ratioMax = floatArrayOf(8f, 6f, 4f), epsilonSdr = one(0f), epsilonHdr = one(0f)))
    }

    @Test
    fun `a gain map with a crafted number is refused`() {
        assertFalse(sane(ratioMax = floatArrayOf(4f, Float.NaN, 4f)))
        assertFalse(sane(ratioMax = one(Float.POSITIVE_INFINITY)))
        assertFalse(sane(ratioMin = one(0f)))
        assertFalse(sane(ratioMin = one(-1f)))
        assertFalse(sane(gamma = one(0f)))
        assertFalse(sane(gamma = one(1e9f)))
        assertFalse(sane(epsilonSdr = one(-0.1f)))
        assertFalse(sane(epsilonHdr = one(2f)))
        assertFalse(sane(full = Float.NaN))
        assertFalse(sane(min = 0f))
        assertFalse(sane(full = 1e9f))
        assertFalse(sane(ratioMax = floatArrayOf(4f, 4f)))
    }

    @Test
    fun `two pictures stay open and a third frees the oldest`() {
        val sessions = RegionSessions<String>(DecoderLimits.MAX_SESSIONS, 100)
        val (a, none1) = sessions.add("a")
        val (b, none2) = sessions.add("b")
        assertTrue(none1.isEmpty() && none2.isEmpty())
        assertEquals(2, sessions.size)
        val (c, evicted) = sessions.add("c")
        assertEquals(listOf("a"), evicted)
        assertEquals(2, sessions.size)
        assertNull(sessions[a])
        assertEquals("b", sessions[b])
        assertEquals("c", sessions[c])
        val (_, evicted2) = sessions.add("d")
        assertEquals(listOf("b"), evicted2)
    }

    @Test
    fun `a handle is never given twice`() {
        val sessions = RegionSessions<String>(2, 1)
        val handles = List(50) { sessions.add("p$it").first }
        assertEquals(handles.size, handles.toSet().size)
        assertTrue(handles.all { it > 0 })
    }

    @Test
    fun `a handle counter at the end of the range wraps to a positive one`() {
        val sessions = RegionSessions<String>(2, Int.MAX_VALUE)
        val first = sessions.add("a").first
        val second = sessions.add("b").first
        assertEquals(Int.MAX_VALUE, first)
        assertEquals(1, second)
    }

    @Test
    fun `a closed picture is gone and closing it twice frees nothing`() {
        val sessions = RegionSessions<String>(2, 7)
        val (h, _) = sessions.add("a")
        assertEquals("a", sessions.remove(h))
        assertNull(sessions[h])
        assertNull(sessions.remove(h))
        assertNull(sessions.remove(-1))
        assertEquals(0, sessions.size)
    }

    @Test
    fun `clearing hands back every open picture once`() {
        val sessions = RegionSessions<String>(2, 7)
        sessions.add("a")
        sessions.add("b")
        assertEquals(listOf("a", "b"), sessions.clear())
        assertTrue(sessions.clear().isEmpty())
        assertEquals(0, sessions.size)
    }

    @Test
    fun `a first handle of zero or less starts at one`() {
        val sessions = RegionSessions<String>(2, -5)
        assertNotEquals(0, sessions.add("a").first)
        assertEquals(1, RegionSessions<String>(2, 0).add("a").first)
    }
}
