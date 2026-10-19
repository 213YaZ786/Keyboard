package helium314.keyboard.latin.ui.component

import helium314.keyboard.latin.ui.glass.LocalGlass
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

/**
 * A dialog of the app: Material's AlertDialog, made of glass when glass is on,
 * like every zone. The fill and the tonal elevation give way to the glass,
 * whose rim draws the edge. Every dialog goes through here.
 *
 * Shared across the apps of this base: edit Modules/shared, then run sync.sh.
 */
@Composable
fun ZoneAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    shape: Shape = AlertDialogDefaults.shape,
    properties: DialogProperties = DialogProperties()
) {
    val glass = LocalGlass.current
    AlertDialog(
        onDismissRequest = onDismissRequest,
        // In its own window the pane cannot see the app: Android blurs what lies
        // behind it instead, and the pane is a clear sheet of glass over that.
        confirmButton = { if (glass != null) BlurBehind(); confirmButton() },
        modifier = if (glass == null) modifier else modifier.dialogGlass(shape, glass),
        dismissButton = dismissButton,
        icon = icon,
        title = title,
        text = text,
        shape = shape,
        containerColor = if (glass == null) AlertDialogDefaults.containerColor else Color.Transparent,
        iconContentColor = AlertDialogDefaults.iconContentColor,
        titleContentColor = if (glass == null) AlertDialogDefaults.titleContentColor else MaterialTheme.colorScheme.onSurface,
        textContentColor = if (glass == null) AlertDialogDefaults.textContentColor else MaterialTheme.colorScheme.onSurfaceVariant,
        tonalElevation = if (glass == null) AlertDialogDefaults.TonalElevation else 0.dp,
        properties = properties
    )
}

/**
 * The window of the dialog blurs the screen behind it (Android 12 and later,
 * where the phone allows it), with a lighter dim, so the pane over it reads
 * as glass. Where blur is off (battery saver, an old GPU), the plain dim stays.
 * Blur and dim from Kyant0/AndroidLiquidGlass's dialog (Apache-2.0,
 * https://github.com/Kyant0/AndroidLiquidGlass app/src/commonMain/kotlin/com/kyant/backdrop/catalog/destinations/DialogContent.kt):
 * blur 16 dp by day, 8 by night; dim 0.23 by day, 0.56 by night.
 */
@Composable
fun BlurBehind(radiusDp: Int? = null) {
    val dark = LocalGlass.current?.dark ?: false
    val radius = radiusDp ?: if (dark) 8 else 16
    val view = androidx.compose.ui.platform.LocalView.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    androidx.compose.runtime.DisposableEffect(view) {
        val window = (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
        if (window != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            window.attributes = window.attributes.apply {
                blurBehindRadius = with(density) { radius.dp.roundToPx() }
                dimAmount = if (dark) 0.56f else 0.23f
            }
        }
        onDispose { }
    }
}

/** A dialog's pane: a clear tint over the blurred screen, its rim soft. Tint from Kyant0/AndroidLiquidGlass's DialogContent.kt containerColor. */
fun Modifier.dialogGlass(shape: Shape, look: helium314.keyboard.latin.ui.glass.GlassLook): Modifier = this.drawBehind {
    val outline = shape.createOutline(size, layoutDirection, this)
    drawOutline(outline, if (look.dark) Color(0xFF121212).copy(alpha = 0.4f) else Color(0xFFFAFAFA).copy(alpha = 0.6f))
    drawOutline(outline, helium314.keyboard.latin.ui.glass.rimBrush(look, size.height), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.4f * density))
}

/**
 * The pane of a dialog of its own (a sheet of choices in a Dialog): the
 * same clear glass over the blurred screen as [ZoneAlertDialog].
 */
@Composable
fun DialogPane(shape: Shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp), content: @Composable () -> Unit) {
    val look = LocalGlass.current
    if (look != null) BlurBehind()
    androidx.compose.foundation.layout.Box(
        if (look != null) Modifier.dialogGlass(shape, look)
        else Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh, shape)
    ) {
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides MaterialTheme.colorScheme.onSurface) { content() }
    }
}

