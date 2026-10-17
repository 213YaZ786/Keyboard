package helium314.keyboard.latin.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * What a screen shows when it has nothing yet: a zone in the middle of the
 * space it is given, with an optional icon, a title, one sentence and an
 * optional action.
 *
 * Centred both ways. Text left at the top of an empty screen read as a
 * screen that had not finished loading. The zone carries the wallpaper's
 * colour like every other surface, so an empty screen still looks like the
 * app. The caller decides the height: fillMaxSize in a plain layout,
 * fillParentMaxHeight inside a lazy list, where fillMaxSize has no bound.
 *
 * Shared across the apps of this base: edit Modules/shared, then run sync.sh.
 */
@Composable
fun EmptyZone(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    iconShape: Shape? = SunnyShape,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Box(modifier.fillMaxWidth().padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
        ZoneSurface(
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                icon?.let {
                    // [iconShape]: the icon on a container of that shape (Sunny unless said otherwise, null for
                    // the bare icon), as Material 3 Expressive's shapes (androidx compose/material3 MaterialShapes.kt) hold an icon.
                    if (iconShape != null) Box(
                        Modifier.size(72.dp).background(MaterialTheme.colorScheme.primaryContainer, iconShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(it, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(36.dp))
                    } else Icon(it, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
                }
                Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                if (actionLabel != null && onAction != null) {
                    BoldButton(onClick = onAction, modifier = Modifier.padding(top = 6.dp)) { Text(actionLabel) }
                }
            }
        }
    }
}
