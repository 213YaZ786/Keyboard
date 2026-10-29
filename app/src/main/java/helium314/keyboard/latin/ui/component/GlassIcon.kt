package helium314.keyboard.latin.ui.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp

/**
 * A picture inside a round pane of glass, as the phone kit's ContactAvatar draws a person's photo (Modules shared/phone,
 * ui/component/ContactAvatar.kt.tpl; Dialer's copy https://github.com/213YaZ786/Dialer, app/src/main/java/com/yaz/dialer/ui/component/ContactAvatar.kt):
 * a ZoneSurface in a CircleShape of [size], the picture cropped to fill it and clipped to the same circle (Compose's Image and ContentScale.Crop,
 * https://developer.android.com/develop/ui/compose/graphics/images/customize). With no picture yet, [placeholder] is drawn in the middle of the
 * same pane. Used for the icon of an app listed from outside (Store).
 */
@Composable
fun GlassIcon(
    picture: ImageBitmap?,
    size: Dp,
    modifier: Modifier = Modifier,
    placeholder: @Composable () -> Unit = {}
) {
    ZoneSurface(shape = CircleShape, modifier = modifier.size(size)) {
        Box(contentAlignment = Alignment.Center) {
            if (picture != null) {
                Image(
                    picture,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(size).clip(CircleShape)
                )
            } else {
                placeholder()
            }
        }
    }
}
