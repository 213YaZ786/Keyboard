package helium314.keyboard.latin.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * A round pane of glass with its icon and its name under it, sinking a
 * little under the finger: the actions of a contact or a number.
 */
@Composable
fun RoundAction(icon: ImageVector, label: String, tint: Color? = null, onClick: () -> Unit) {
    val haptics = rememberHaptics()
    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val sink by animateFloatAsState(if (pressed) 0.88f else 1f, spring(dampingRatio = 0.45f, stiffness = 700f), label = "sink")
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        ZoneSurface(
            shape = CircleShape,
            modifier = Modifier
                .size(56.dp)
                .graphicsLayer {
                    scaleX = sink
                    scaleY = sink
                }
                .clip(CircleShape)
                .clickable(interactionSource = press, indication = null, role = Role.Button, onClickLabel = label) {
                    haptics.tick()
                    onClick()
                }
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = label, tint = tint ?: MaterialTheme.colorScheme.primary)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}
