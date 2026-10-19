// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.keyboard.internal

import android.animation.ValueAnimator
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The light that runs over the keys for easter eggs 4 and 5: how much each key glows, from 0 (at rest) to 1 (as pressed),
 * at each frame of one ValueAnimator (https://developer.android.com/reference/android/animation/ValueAnimator) that
 * lasts only as long as the effect. The key itself is GlassKeyDrawable, which draws its pressed tint by [lit].
 *
 * WAVE: key by key, left to right. Each key waits for the front, in proportion to its place across the keyboard, then
 * glows and fades over a fixed part of the whole time: the staggered delay of Material's choreography
 * (https://m3.material.io/styles/motion/transitions/transition-patterns, "Stagger" of list items), a sine for the
 * rise and the fall.
 * TOGETHER: every key at once, a quick rise then a slow fade.
 * The animator follows the system's animation scale, a scale of 0 ends it at once: EggEffects does not start it then.
 */
class KeyLight(private val onFrame: () -> Unit) {
    enum class Mode { WAVE, TOGETHER }

    private var animator: ValueAnimator? = null
    private var progress = 0f
    private var mode = Mode.WAVE
    private var width = 1f

    val running get() = animator != null

    /** Starts the light over a keyboard [keyboardWidth] pixels wide; a light already running is replaced. */
    fun start(mode: Mode, keyboardWidth: Float) {
        cancel()
        this.mode = mode
        width = keyboardWidth.coerceAtLeast(1f)
        progress = 0f
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (mode == Mode.WAVE) WAVE_MS else TOGETHER_MS
            interpolator = LinearInterpolator()
            addUpdateListener {
                progress = it.animatedValue as Float
                onFrame()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) {
                    if (animator === a) animator = null
                    progress = 0f
                    onFrame()
                }
            })
            start()
        }
    }

    /** Ends the light at once; every key is at rest. */
    fun cancel() {
        val a = animator ?: return
        animator = null
        a.cancel()
        progress = 0f
    }

    /** How much the key whose centre is [centerX] pixels from the keyboard's left edge glows now, 0 to 1. */
    fun litAt(centerX: Float): Float = if (animator == null) 0f else lit(mode, progress, centerX, width)

    companion object {
        /**
         * The light of a key at [progress] (0 to 1) of the effect, as described above; pure, so it can be tried without
         * an animator. Sine easing as Material's motion curves are smooth at both ends
         * (https://m3.material.io/styles/motion/easing-and-duration/tokens-specs).
         */
        fun lit(mode: Mode, progress: Float, centerX: Float, width: Float): Float = rawLit(mode, progress, centerX, width).coerceIn(0f, 1f)

        // The curve before the clamp (a sine at its end is a hair under 0): Float.coerceIn, https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.ranges/coerce-in.html
        private fun rawLit(mode: Mode, progress: Float, centerX: Float, width: Float): Float = when (mode) {
            Mode.WAVE -> {
                val delay = (centerX / width).coerceIn(0f, 1f) * (1f - GLOW)
                val t = ((progress - delay) / GLOW).coerceIn(0f, 1f)
                sin(PI.toFloat() * t)
            }
            Mode.TOGETHER ->
                if (progress < RISE) sin(PI.toFloat() / 2f * progress / RISE)
                else cos(PI.toFloat() / 2f * (progress - RISE) / (1f - RISE))
        }

        /** The whole wave, in ms: the front crosses the keyboard in (1 - GLOW) of it. */
        const val WAVE_MS = 2200L
        const val TOGETHER_MS = 1800L

        /** The part of the wave's time one key glows for. */
        private const val GLOW = 0.38f

        /** The part of the time the keys take to reach their full light together. */
        private const val RISE = 0.2f
    }
}
