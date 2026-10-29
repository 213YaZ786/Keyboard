package helium314.keyboard.latin.ui.component

import helium314.keyboard.latin.ui.glass.LocalGlass
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Dp
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
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
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
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
    val blur = rememberCrossWindowBlur()
    // Material's BasicAlertDialog gives the window, the 280..560 dp width and the pane title
    // TalkBack reads (AlertDialog.kt, BasicAlertDialog:
    // https://github.com/androidx/androidx/blob/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/AlertDialog.kt).
    BasicAlertDialog(
        onDismissRequest = onDismissRequest,
        // In its own window the pane cannot see the app: Android blurs what lies
        // behind it instead, and the pane is a clear sheet of glass over that.
        modifier = if (glass == null) modifier else modifier.dialogGlass(shape, glass, blur),
        properties = properties
    ) {
        if (glass != null) BlurBehind(blur = blur)
        ZoneAlertContent(
            confirmButton = confirmButton,
            dismissButton = dismissButton,
            icon = icon,
            title = title,
            text = text,
            shape = shape,
            containerColor = if (glass == null) AlertDialogDefaults.containerColor else Color.Transparent,
            tonalElevation = if (glass == null) AlertDialogDefaults.TonalElevation else 0.dp,
            iconContentColor = AlertDialogDefaults.iconContentColor,
            titleContentColor = if (glass == null) AlertDialogDefaults.titleContentColor else MaterialTheme.colorScheme.onSurface,
            textContentColor = if (glass == null) AlertDialogDefaults.textContentColor else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Material 3's AlertDialogContent (AlertDialog.kt of
 * https://github.com/androidx/androidx/blob/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/AlertDialog.kt):
 * the same 24 dp padding, 16 dp under the icon and the title, 24 dp under the text, the same
 * styles (headlineSmall, bodyMedium, labelLarge) and content colours; the text slot keeps
 * weight(1f, fill = false) so a long list scrolls. Changed, as iOS's alerts (the user,
 * 2026-10-28, "text must be centered"): icon, title and text centred, and the buttons in one
 * row sharing the whole width equally (a confirm alone takes it all), where Material packs
 * them at the end.
 */
@Composable
private fun ZoneAlertContent(
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)?,
    icon: (@Composable () -> Unit)?,
    title: (@Composable () -> Unit)?,
    text: (@Composable () -> Unit)?,
    shape: Shape,
    containerColor: Color,
    tonalElevation: Dp,
    iconContentColor: Color,
    titleContentColor: Color,
    textContentColor: Color
) {
    val centred = TextAlign.Center
    Surface(shape = shape, color = containerColor, tonalElevation = tonalElevation) {
        Column(Modifier.padding(24.dp)) {
            icon?.let { slot ->
                CompositionLocalProvider(LocalContentColor provides iconContentColor) {
                    Box(Modifier.padding(bottom = 16.dp).align(Alignment.CenterHorizontally)) { slot() }
                }
            }
            title?.let { slot ->
                CompositionLocalProvider(LocalContentColor provides titleContentColor) {
                    ProvideTextStyle(LocalTextStyle.current.merge(MaterialTheme.typography.headlineSmall).copy(textAlign = centred)) {
                        Box(Modifier.padding(bottom = 16.dp).fillMaxWidth(), contentAlignment = Alignment.TopCenter) { slot() }
                    }
                }
            }
            text?.let { slot ->
                CompositionLocalProvider(LocalContentColor provides textContentColor) {
                    ProvideTextStyle(LocalTextStyle.current.merge(MaterialTheme.typography.bodyMedium).copy(textAlign = centred)) {
                        Box(
                            Modifier.weight(weight = 1f, fill = false).padding(bottom = 24.dp).fillMaxWidth(),
                            contentAlignment = Alignment.TopCenter
                        ) { slot() }
                    }
                }
            }
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.primary) {
                ProvideTextStyle(LocalTextStyle.current.merge(MaterialTheme.typography.labelLarge)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ButtonGap)) {
                        dismissButton?.let { slot -> Box(Modifier.weight(1f), propagateMinConstraints = true) { slot() } }
                        Box(Modifier.weight(1f), propagateMinConstraints = true) { confirmButton() }
                    }
                }
            }
        }
    }
}

/**
 * Whether Android blurs across windows right now, as AOSP's window-blur recipe has an app do
 * (https://source.android.com/docs/core/display/window-blurs): read
 * WindowManager.isCrossWindowBlurEnabled at start, follow
 * WindowManager.addCrossWindowBlurEnabledListener while shown, remove it on dispose. Blur is off
 * with battery saver, a developer override or on a device that cannot (GrapheneOS included);
 * below Android 12 it is always off. When off, a dialog must not count on the blur.
 */
@Composable
fun rememberCrossWindowBlur(): Boolean {
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) return false
    return TrackCrossWindowBlur()
}

@androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.S)
@Composable
private fun TrackCrossWindowBlur(): Boolean {
    val context = androidx.compose.ui.platform.LocalContext.current
    val manager = androidx.compose.runtime.remember(context) { context.getSystemService(android.view.WindowManager::class.java) }
    var on by androidx.compose.runtime.remember(manager) { androidx.compose.runtime.mutableStateOf(manager.isCrossWindowBlurEnabled) }
    androidx.compose.runtime.DisposableEffect(manager) {
        val listener = java.util.function.Consumer<Boolean> { on = it }
        manager.addCrossWindowBlurEnabledListener(context.mainExecutor, listener)
        on = manager.isCrossWindowBlurEnabled
        onDispose { manager.removeCrossWindowBlurEnabledListener(listener) }
    }
    return on
}

/**
 * The window of the dialog blurs the screen behind it (Android 12 and later,
 * where the phone allows it), with a lighter dim, so the pane over it reads
 * as glass. Where blur is off (battery saver, GrapheneOS, an old GPU) the dim is stronger,
 * 0.4 by day and 0.6 by night, and the pane is opaque ([dialogGlass]): AOSP's recipe for a window
 * whose blur is unavailable raises the dim 0.1 to 0.4 and the background alpha to 255
 * (https://source.android.com/docs/core/display/window-blurs).
 * Blur and dim from Kyant0/AndroidLiquidGlass's dialog (Apache-2.0,
 * https://github.com/Kyant0/AndroidLiquidGlass app/src/commonMain/kotlin/com/kyant/backdrop/catalog/destinations/DialogContent.kt):
 * blur 16 dp by day, 8 by night; dim 0.23 by day, 0.56 by night.
 */
@Composable
fun BlurBehind(radiusDp: Int? = null, blur: Boolean = rememberCrossWindowBlur()) {
    val dark = LocalGlass.current?.dark ?: false
    val radius = radiusDp ?: if (dark) 8 else 16
    val view = androidx.compose.ui.platform.LocalView.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    androidx.compose.runtime.DisposableEffect(view, blur) {
        val window = (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
        if (window != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            if (blur) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window.attributes = window.attributes.apply {
                blurBehindRadius = if (blur) with(density) { radius.dp.roundToPx() } else 0
                dimAmount = if (blur) (if (dark) 0.56f else 0.23f) else (if (dark) 0.6f else 0.4f)
            }
        }
        onDispose { }
    }
}

/**
 * A dialog's pane. Over the blurred screen ([blur] on): a tint of alpha 0.72 by day, 0.62 by night
 * (was 0.6 and 0.4, still too clear over a busy list), its rim soft; tint from Kyant0/AndroidLiquidGlass's
 * DialogContent.kt containerColor. Blur off: the pane fully opaque in the theme's surfaceContainerHigh
 * (the recipe's background alpha 255, source.android.com/docs/core/display/window-blurs), so text never
 * sits on a sharp busy screen.
 */
@Composable
fun Modifier.dialogGlass(shape: Shape, look: helium314.keyboard.latin.ui.glass.GlassLook, blur: Boolean = true): Modifier {
    val solid = MaterialTheme.colorScheme.surfaceContainerHigh
    return this.drawBehind {
        val outline = shape.createOutline(size, layoutDirection, this)
        val fill = when {
            !blur -> solid
            look.dark -> Color(0xFF121212).copy(alpha = 0.62f)
            else -> Color(0xFFFAFAFA).copy(alpha = 0.72f)
        }
        drawOutline(outline, fill)
        drawOutline(outline, helium314.keyboard.latin.ui.glass.rimBrush(look, size.height), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.4f * density))
    }
}

/**
 * The pane of a dialog of its own (a sheet of choices in a Dialog): the
 * same glass over the blurred screen as [ZoneAlertDialog], opaque where Android does not blur.
 */
@Composable
fun DialogPane(shape: Shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp), content: @Composable () -> Unit) {
    val look = LocalGlass.current
    val blur = rememberCrossWindowBlur()
    if (look != null) BlurBehind(blur = blur)
    androidx.compose.foundation.layout.Box(
        if (look != null) Modifier.dialogGlass(shape, look, blur)
        else Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh, shape)
    ) {
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides MaterialTheme.colorScheme.onSurface) { content() }
    }
}

