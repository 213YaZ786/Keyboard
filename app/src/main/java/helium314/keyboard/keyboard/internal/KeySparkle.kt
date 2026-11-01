// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.keyboard.internal

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.Build
import android.os.SystemClock
import android.view.animation.LinearInterpolator
import androidx.annotation.RequiresApi
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import helium314.keyboard.keyboard.Key
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.common.Colors
import helium314.keyboard.latin.ui.component.SparkleRipple
import helium314.keyboard.latin.utils.isDarkColor
import java.util.function.Consumer
import kotlin.math.hypot

/**
 * The press of a key as Android 12 draws it, the sparkle ripple, for the keys of the main keyboard: the press of
 * SparklePress (Modules' panes kit, latin/ui/component/SparklePress.kt in the app) taken from a Compose
 * Modifier.Node to ValueAnimators, the shader and its uniforms being SparkleRipple's as they are (AOSP's RippleShader,
 * https://github.com/aosp-mirror/platform_frameworks_base/blob/main/graphics/java/android/graphics/drawable/RippleShader.java).
 *
 * The timing is RippleAnimationSession's, as SparklePressNode keeps it
 * (https://github.com/aosp-mirror/platform_frameworks_base/blob/main/graphics/java/android/graphics/drawable/RippleAnimationSession.java:
 * ENTER_ANIM_DURATION 450 with ofFloat(0, .5), EXIT_ANIM_DURATION 375 with ofFloat(.5, 1)): progress runs 0 to 0.5 from
 * the touch and stays there while the finger is down; on the release the exit waits for the end of the enter, then
 * runs 0.5 to 1. One ValueAnimator per running ripple (as KeyLight); each frame calls [onFrame] with the key, nothing
 * animates once the ripples end. [color] is the wave and [sparkle] the glitter (SparklePress's onSurface at 12 % and
 * 50 %, rememberSparklePress). One RuntimeShader is shared by all the ripples, its uniforms set before each draw.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class KeySparkle(private val color: Color, private val sparkle: Color, private val onFrame: (Key) -> Unit) {

    private class Ripple(val touchX: Float, val touchY: Float) {
        var progress = 0f
        var released = false
        var animator: ValueAnimator? = null
    }

    private val ripples = HashMap<Key, Ripple>()
    private val ripple = SparkleRipple()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** True while [key] has a ripple, spreading, held or fading: the key is drawn over the rest image. */
    fun isRunning(key: Key) = ripples.containsKey(key)

    /** A finger lands on [key] at ([x], [y]) in the key's own pixels; a ripple already on the key restarts. */
    fun press(key: Key, x: Float, y: Float) {
        stop(key)
        val r = Ripple(x, y)
        ripples[key] = r
        run(key, r, 0f, 0.5f, SparkleRipple.ENTER_MS) {
            if (r.released) exit(key, r)
        }
    }

    /** The finger leaves [key] (or a panel opens over it): the ripple finishes spreading, then fades. */
    fun release(key: Key) {
        val r = ripples[key] ?: return
        if (r.released) return
        r.released = true
        val a = r.animator
        if (a == null || !a.isRunning) exit(key, r) // else the end of the enter starts the exit (press)
    }

    /** Ends every ripple at once (a new keyboard, the view leaves the window). */
    fun clear() {
        for (r in ripples.values) r.animator?.let { it.removeAllListeners(); it.cancel() }
        ripples.clear()
    }

    private fun stop(key: Key) {
        ripples.remove(key)?.animator?.let { it.removeAllListeners(); it.cancel() }
    }

    private fun exit(key: Key, r: Ripple) {
        run(key, r, r.progress.coerceAtLeast(0.5f), 1f, SparkleRipple.EXIT_MS) {
            if (ripples[key] === r) ripples.remove(key)
            onFrame(key)
        }
    }

    // ValueAnimator.ofFloat with a linear interpolator and an end listener: https://developer.android.com/reference/android/animation/ValueAnimator
    private fun run(key: Key, r: Ripple, from: Float, to: Float, ms: Int, onEnd: () -> Unit) {
        val animator = ValueAnimator.ofFloat(from, to).apply {
            duration = ms.toLong()
            interpolator = LinearInterpolator()
            addUpdateListener {
                r.progress = it.animatedValue as Float
                onFrame(key)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (r.animator === animation) onEnd()
                }
            })
        }
        r.animator = animator
        animator.start()
    }

    /**
     * Draws [key]'s ripple clipped to [body] (the key's glass body, in the drawable's pixels of [width] x [height];
     * the touch lies [padLeft], [padTop] further in), under the label. As SparklePressNode.draw: the centre of the area,
     * the radius half its diagonal (RippleDrawable's bounded one), the noise phase from the clock.
     */
    fun draw(canvas: Canvas, key: Key, body: Path, width: Float, height: Float, padLeft: Float, padTop: Float) {
        val r = ripples[key] ?: return
        if (r.progress >= 0.9999f) return
        ripple.update(
            centre = Offset(width / 2f, height / 2f),
            area = Size(width, height),
            maxRadius = hypot(width, height) / 2f,
            progress = r.progress,
            noisePhase = (SystemClock.uptimeMillis() % 100_000) * SparkleRipple.PHASE_PER_MS,
            color = color,
            sparkle = sparkle
        )
        ripple.shader.setFloatUniform("in_touch", r.touchX + padLeft, r.touchY + padTop)
        paint.shader = ripple.shader
        canvas.drawPath(body, paint)
        paint.shader = null
    }

    companion object {
        /**
         * The sparkle in the theme's colours, as rememberSparklePress: the wave is onSurface at 12 %, the glitter onSurface
         * at 50 %; the scheme is the one of GlassKeyDrawable.lookFor (dynamicLightColorScheme / dynamicDarkColorScheme,
         * https://developer.android.com/reference/kotlin/androidx/compose/material3/package-summary#dynamicLightColorScheme(android.content.Context)).
         */
        @JvmStatic
        fun forColors(context: Context, colors: Colors, onFrame: Consumer<Key>): KeySparkle {
            val dark = isDarkColor(colors.get(ColorType.MAIN_BACKGROUND))
            val ink = (if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)).onSurface
            return KeySparkle(ink.copy(alpha = 0.12f), ink.copy(alpha = 0.5f)) { onFrame.accept(it) }
        }
    }
}
