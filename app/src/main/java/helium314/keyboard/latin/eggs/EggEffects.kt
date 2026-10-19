// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.eggs

import android.content.Context
import android.os.SystemClock
import android.provider.Settings as AndroidSettings
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import helium314.keyboard.latin.R
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.graphics.toArgb
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.internal.KeyLight
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.isDarkColor
import nl.dionsegijn.konfetti.core.Angle
import nl.dionsegijn.konfetti.core.Party
import nl.dionsegijn.konfetti.core.Position
import nl.dionsegijn.konfetti.core.Rotation
import nl.dionsegijn.konfetti.core.emitter.Emitter
import nl.dionsegijn.konfetti.core.models.Shape
import nl.dionsegijn.konfetti.core.models.Size
import nl.dionsegijn.konfetti.xml.KonfettiView
import nl.dionsegijn.konfetti.xml.image.ImageUtil
import java.util.concurrent.TimeUnit

/**
 * Plays the easter eggs (point 21) over the keyboard. Nothing runs between two effects: no timer, no thread; an effect
 * is Konfetti's own draw loop, which stops with its last piece, or the ValueAnimator of KeyLight.
 *
 * Konfetti (ISC, nl.dionsegijn:konfetti-xml 2.0.5, https://github.com/DanielMartinus/Konfetti): the KonfettiView overlaid
 * on the keyboard (InputView, id easter_egg_confetti, never clickable so every touch goes through to the keys) and the
 * Party, Emitter and Position.Relative of its samples
 * (https://github.com/DanielMartinus/Konfetti/blob/v2.0.5/samples/shared/src/main/java/nl/dionsegijn/samples/shared/Presets.kt:
 * "festive" for the burst from the bottom, "rain" for the fall from the top), the pieces being
 * Shape.DrawableShape of GlassPiece and GlassEmoji (ImageUtil.loadDrawable, as the samples' MainActivity does).
 */
object EggEffects {
    /** The day the rules see: the clock, which the debug build can set (EggClockReceiver) to try January or Eid. */
    @Volatile
    var clockOverride: Long? = null

    private fun now() = clockOverride ?: System.currentTimeMillis()

    // The egg the last text matched: an effect plays when a text begins to match, not at each key while it still does.
    private var matched: Egg? = null
    // A new field is only read once before it can play, so a text already there does not set an egg off.
    private var firstRead = true

    // The wish to light all the keys (egg 5) from the About screen, which opens the keyboard: kept until the keyboard shows.
    private var allKeysAskedAt = 0L

    /**
     * Source: the animation scale of the system's "Remove animations", Settings.Global.ANIMATOR_DURATION_SCALE
     * (https://developer.android.com/reference/android/provider/Settings.Global#ANIMATOR_DURATION_SCALE): at 0 no effect plays.
     */
    fun animationsOn(context: Context): Boolean =
        AndroidSettings.Global.getFloat(context.contentResolver, AndroidSettings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f

    /** A new text field: what it holds at the first key is read, not played. */
    fun newField() {
        matched = null
        firstRead = true
    }

    /**
     * The text before the cursor after a key. Plays the egg it begins to match. Never in incognito mode nor in a password
     * field (the caller checks SettingsValues.mIncognitoModeEnabled); the text is only compared, never kept nor logged.
     */
    fun onTextChanged(context: Context, before: CharSequence?) {
        val egg = EggRules.detect(before, now())
        val first = firstRead
        firstRead = false
        if (egg == null) {
            matched = null
            return
        }
        if (egg == matched) return
        matched = egg
        if (!first) play(context, egg)
    }

    /** Plays [egg] once, about 2.5 seconds, unless the animations are off or an effect is already on. */
    fun play(context: Context, egg: Egg) {
        if (!animationsOn(context)) return
        val switcher = KeyboardSwitcher.getInstance()
        when (egg) {
            Egg.HELLO_WAVE -> switcher.mainKeyboardView?.takeIf { it.isShown }?.playKeyLight(KeyLight.Mode.WAVE)
            Egg.ALL_KEYS -> switcher.mainKeyboardView?.takeIf { it.isShown }?.playKeyLight(KeyLight.Mode.TOGETHER)
            else -> {
                val view = switcher.eggConfettiView ?: return
                if (!view.isShown || view.isActive()) return
                fitToKeyboard(view)
                val dark = isDarkColor(Settings.getValues().mColors.get(ColorType.MAIN_BACKGROUND))
                when (egg) {
                    Egg.YAZ_RAIN -> view.start(yazRain(context, dark))
                    Egg.YENNAYER -> view.start(amazighConfetti(dark))
                    else -> view.start(risingEid(context, dark))
                }
            }
        }
    }

    /**
     * The input view fills the window, the keyboard sits at its bottom: the confetti view takes the keyboard's place, so
     * the pieces fall and rise over the keys and not over the app. FrameLayout.LayoutParams gravity and height,
     * https://developer.android.com/reference/android/widget/FrameLayout.LayoutParams
     */
    private fun fitToKeyboard(view: KonfettiView) {
        val frame = (view.parent as? View)?.findViewById<View>(R.id.main_keyboard_frame) ?: return
        val params = view.layoutParams as? FrameLayout.LayoutParams ?: return
        if (frame.height <= 0 || (params.height == frame.height && params.gravity == Gravity.BOTTOM)) return
        params.gravity = Gravity.BOTTOM
        params.height = frame.height
        view.layoutParams = params
    }

    /** Egg 5: the About screen asks for the keyboard, then every key lights up as soon as it shows (onKeyboardShown). */
    fun lightAllKeysWhenShown(context: Context) {
        val view = KeyboardSwitcher.getInstance().mainKeyboardView
        if (view != null && view.isShown && view.isLaidOut) {
            play(context, Egg.ALL_KEYS)
            return
        }
        allKeysAskedAt = SystemClock.elapsedRealtime()
    }

    /** The keyboard window is shown: lights the keys if About asked for it in the last few seconds. */
    fun onKeyboardShown(context: Context) {
        if (allKeysAskedAt == 0L) return
        val fresh = SystemClock.elapsedRealtime() - allKeysAskedAt < ASK_VALID_MS
        allKeysAskedAt = 0L
        if (!fresh) return
        val view = KeyboardSwitcher.getInstance().mainKeyboardView ?: return
        // once the keyboard is laid out: View.doOnLayout of Android KTX would wait for a next layout it may never get,
        // so the light starts at the view's first draw (View.post, https://developer.android.com/reference/android/view/View#post(java.lang.Runnable))
        view.post { play(context, Egg.ALL_KEYS) }
    }

    /** The keyboard goes away: whatever is playing stops, nothing is left behind. */
    fun stop() {
        matched = null
        allKeysAskedAt = 0L
        val switcher = KeyboardSwitcher.getInstance()
        switcher.eggConfettiView?.reset()
        switcher.mainKeyboardView?.stopKeyLight()
    }

    private const val ASK_VALID_MS = 4000L

    // Colours: the pieces take the phone's Material You colours as the glass of the keys does (GlassKeyDrawable.lookFor):
    // dynamicLightColorScheme / dynamicDarkColorScheme, https://developer.android.com/reference/kotlin/androidx/compose/material3/package-summary#dynamicLightColorScheme(android.content.Context)
    private fun primary(context: Context, dark: Boolean) =
        (if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)).primary.toArgb()

    private fun tertiary(context: Context, dark: Boolean) =
        (if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)).tertiary.toArgb()

    private fun shape(piece: android.graphics.drawable.Drawable) = ImageUtil.loadDrawable(piece, tint = false, applyAlpha = true)

    // Pieces keep their own colours (tint = false), so the party's colour list only has to exist.
    private val ANY_COLOR = listOf(0xFFFFFF)

    /**
     * Egg 1, a rain of glass yaz: the "rain" preset of Konfetti's samples (angle BOTTOM, the top edge as the emitter's
     * line with Position.Relative(0.0, 0.0).between(Relative(1.0, 0.0))), the pieces the yaz, in the phone's accent.
     */
    private fun yazRain(context: Context, dark: Boolean): Party {
        val glyph = GlassPiece.glyph("ⵣ", primary(context, dark), dark) ?: GlassPiece.disc(primary(context, dark), dark)
        return Party(
            speed = 0f, maxSpeed = 6f, damping = 0.9f,
            angle = Angle.BOTTOM, spread = 30,
            size = listOf(Size(22, mass = 4f), Size(30, mass = 5f), Size(38, mass = 6f)),
            colors = ANY_COLOR, shapes = listOf(shape(glyph)),
            timeToLive = 1200L, fadeOutEnabled = true,
            rotation = Rotation(multiplier2D = 3f, multiplier3D = 0.6f),
            emitter = Emitter(duration = 900, TimeUnit.MILLISECONDS).max(36),
            position = Position.Relative(0.0, 0.0).between(Position.Relative(1.0, 0.0)),
        )
    }

    /**
     * Egg 2, confetti in the colours of the Amazigh flag, a burst from the bottom: the "festive" preset of the samples (the
     * burst of the middle and two from the corners). The flag's blue, green, yellow and red are those of Wikimedia
     * Commons' Berber_flag.svg (https://commons.wikimedia.org/wiki/File:Berber_flag.svg: #0090DA, #78BE20, #FEDD00,
     * #CC0033); red is one piece in ten.
     */
    private fun amazighConfetti(dark: Boolean): List<Party> {
        val colours = listOf(0xFF0090DA.toInt(), 0xFF78BE20.toInt(), 0xFFFEDD00.toInt())
        val pieces = ArrayList<Shape>()
        for (c in colours) {
            pieces += shape(GlassPiece.disc(c, dark))
            pieces += shape(GlassPiece.tile(c, dark))
            pieces += shape(GlassPiece.bar(c, dark))
        }
        pieces += shape(GlassPiece.disc(0xFFCC0033.toInt(), dark))
        val party = Party(
            speed = 18f, maxSpeed = 32f, damping = 0.9f,
            angle = Angle.TOP, spread = 45,
            size = listOf(Size(10, mass = 4f), Size(14), Size(18, mass = 6f)),
            colors = ANY_COLOR, shapes = pieces,
            timeToLive = 1500L, fadeOutEnabled = true,
            rotation = Rotation(),
            emitter = Emitter(duration = 120, TimeUnit.MILLISECONDS).max(26),
            position = Position.Relative(0.5, 1.0),
        )
        return listOf(
            party,
            party.copy(angle = 315, spread = 40, position = Position.Relative(0.0, 1.0)),
            party.copy(angle = 225, spread = 40, position = Position.Relative(1.0, 1.0)),
        )
    }

    /**
     * Egg 3, crescents and lanterns rising from the bottom (🌙 and 🏮 of the phone's emoji font, GlassEmoji): the
     * "festive" preset again, with the bottom edge as the line they start from and no turning. Where the font has no such
     * glyph a glass bead stands in.
     */
    private fun risingEid(context: Context, dark: Boolean): Party {
        val pieces = listOfNotNull(GlassEmoji.of("🌙"), GlassEmoji.of("🏮")).ifEmpty {
            listOf(GlassPiece.disc(tertiary(context, dark), dark), GlassPiece.disc(primary(context, dark), dark))
        }
        return Party(
            speed = 20f, maxSpeed = 34f, damping = 0.9f,
            angle = Angle.TOP, spread = 50,
            size = listOf(Size(26, mass = 5f), Size(34, mass = 6f), Size(42, mass = 6f)),
            colors = ANY_COLOR, shapes = pieces.map { shape(it) },
            timeToLive = 1400L, fadeOutEnabled = true,
            rotation = Rotation.disabled(),
            emitter = Emitter(duration = 1200, TimeUnit.MILLISECONDS).max(20),
            position = Position.Relative(0.0, 1.0).between(Position.Relative(1.0, 1.0)),
        )
    }
}
