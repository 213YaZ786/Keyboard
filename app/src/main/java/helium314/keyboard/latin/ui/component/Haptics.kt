package helium314.keyboard.latin.ui.component

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView

/**
 * Every action answers.
 *
 * A few words and no more, so the whole app speaks the same way: a light tick
 * for an ordinary tap, a firm one when something is taken or begun, a short
 * confirmation when something is finished or undone, a refusal when it did
 * not work. Around them, the pull to refresh threshold and the switches have
 * a feel of their own.
 *
 * Through the view rather than through Compose's own haptic type, because the
 * constants below are the platform's own while Compose's list has grown one
 * name at a time, and a name that does not exist in the exact version the
 * bill of materials resolves is a failed build. Those newer than the minimum
 * version fall back to the nearest older one.
 *
 * The system setting is respected on its own: a phone with haptics turned off
 * feels nothing, and nothing here overrides that.
 */
class Haptics(private val view: View) {

    /** An ordinary tap: a button, a card, a row, a choice in a dialog. */
    fun tick() {
        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    /** Something begins or is taken: a tab moved to, a post filed away. */
    fun firm() {
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    /** Something is finished: a follow, a deletion, a file saved, a refresh done. */
    fun done() {
        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
    }

    /** It did not work: a page that did not load, a paste that is not usable. */
    fun reject() {
        view.performHapticFeedback(HapticFeedbackConstants.REJECT)
    }

    /** The pull to refresh gesture reached its threshold, or was brought back short of it. */
    fun threshold(reached: Boolean) {
        view.performHapticFeedback(
            when {
                Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> HapticFeedbackConstants.CLOCK_TICK
                reached -> HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE
                else -> HapticFeedbackConstants.GESTURE_THRESHOLD_DEACTIVATE
            }
        )
    }

    /** A switch turned on or off, each with its own feel. */
    fun toggle(on: Boolean) {
        view.performHapticFeedback(
            when {
                Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> HapticFeedbackConstants.VIRTUAL_KEY
                on -> HapticFeedbackConstants.TOGGLE_ON
                else -> HapticFeedbackConstants.TOGGLE_OFF
            }
        )
    }
}

@Composable
fun rememberHaptics(): Haptics {
    val view = LocalView.current
    return remember(view) { Haptics(view) }
}

/**
 * A refusal, once, when [failure] turns from nothing to something: the page
 * this screen was loading did not come. A screen opened on a failure it
 * already had stays silent, it is not news.
 */
@Composable
fun RejectOnFailure(failure: Any?) {
    val haptics = rememberHaptics()
    var last by remember { mutableStateOf(failure) }
    LaunchedEffect(failure) {
        if (failure != null && last == null) haptics.reject()
        last = failure
    }
}

/**
 * The end of a refresh the reader asked for: done when it came, a refusal
 * when [failed]. Call the returned function when the reader asks, from the
 * pull gesture; refreshes the app starts on its own stay silent.
 */
@Composable
fun rememberRefreshHaptics(refreshing: Boolean, failed: Boolean): () -> Unit {
    val haptics = rememberHaptics()
    val didFail by rememberUpdatedState(failed)
    var asked by remember { mutableStateOf(false) }
    var was by remember { mutableStateOf(refreshing) }
    LaunchedEffect(refreshing) {
        if (was && !refreshing && asked) {
            if (didFail) haptics.reject() else haptics.done()
            asked = false
        }
        was = refreshing
    }
    return remember { { asked = true } }
}
