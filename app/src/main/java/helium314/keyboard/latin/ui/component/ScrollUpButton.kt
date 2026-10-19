package helium314.keyboard.latin.ui.component

import androidx.compose.ui.graphics.Color
import helium314.keyboard.latin.ui.glass.glassFloating
import helium314.keyboard.latin.ui.glass.LocalGlassBackdrop
import helium314.keyboard.latin.ui.glass.LocalGlass
import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp

/**
 * Back to the top of the list.
 *
 * On a stream that is hundreds of posts long that is a lot of thumb, and this
 * is the way back in one tap. It sits at the bottom right above the folder
 * switch, both above the dock, where a thumb already is.
 *
 * It fades and scales rather than appearing, because something that pops into
 * a corner while you read pulls the eye away from the text.
 *
 * Shared across the apps of this base: edit Modules/shared, then run sync.sh.
 */
@Composable
fun ScrollUpButton(
    visible: Boolean,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + scaleIn(initialScale = 0.8f),
        exit = fadeOut() + scaleOut(targetScale = 0.8f),
        modifier = modifier
    ) {
        FloatingRoundButton(icon = icon, label = "Back to the top", onClick = onClick)
    }
}

/**
 * The round floating control both bottom buttons are cut from, so the folder
 * switch and the way back to the top read as one stack rather than as two
 * unrelated things that happen to sit near each other.
 */
@Composable
fun FloatingRoundButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val view = LocalView.current
    // In glass, it bends what scrolls under it, the icon in the accent, and
    // no edge nor shadow; otherwise filled, with the edge every action carries.
    val look = LocalGlass.current
    val backdrop = LocalGlassBackdrop.current
    val glass = look != null && backdrop != null
    Surface(
        onClick = {
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            onClick()
        },
        shape = CircleShape,
        color = if (glass) Color.Transparent else MaterialTheme.colorScheme.secondaryContainer,
        contentColor = if (glass) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSecondaryContainer,
        border = if (glass) null else boldBorder(),
        shadowElevation = if (glass) 0.dp else 3.dp,
        modifier = if (glass) modifier.glassFloating(backdrop!!, CircleShape, look!!) else modifier
    ) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(24.dp))
        }
    }
}
