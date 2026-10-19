package helium314.keyboard.latin.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The mark of a post that arrived since the last visit and has not been
 * scrolled past yet: a small dot in the accent, in a corner of the post. It
 * shrinks away once the post is passed. A border said the same, but glass
 * draws no borders.
 */
@Composable
fun NewDot(visible: Boolean, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = scaleIn(),
        exit = scaleOut() + fadeOut()
    ) {
        Box(Modifier.size(10.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
    }
}
