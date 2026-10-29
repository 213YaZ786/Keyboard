package helium314.keyboard.latin.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * A choice among a few options, each a glass capsule that reads as a button (the user, 2026-10-29:
 * "on n'a pas l'impression que les options sont des boutons"). A tap picks and closes, as
 * AlertDialog.Builder.setItems does, and the [selected] one is marked as setSingleChoiceItems'
 * checkedItem marks the current one, here as a chosen zone, ZoneSurface(accent = true), with a
 * check before its label. Cancel is the confirm slot.
 *
 * Compared: AOSP's AlertDialog list dialogs
 * (https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/app/AlertDialog.java);
 * Material 3's dialog tokens, headlineSmall title, bodyMedium onSurfaceVariant supporting text,
 * labelLarge actions (https://github.com/androidx/androidx/blob/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/tokens/DialogTokens.kt),
 * all given by [ZoneAlertDialog]; iOS 26's alerts and action sheets, each choice its own full
 * width capsule, stacked; and Frontr's ChoiceDialog (radio rows, a tap picks and closes). The
 * check is the three-point path of Material 3's Checkbox
 * (https://github.com/androidx/androidx/blob/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/Checkbox.kt, CheckboxKt.drawCheck),
 * as GlassCheck draws it, here still.
 *
 * [danger] options are drawn in the error colour. The list scrolls inside the dialog past 420 dp.
 *
 * Shared across the apps of this base: edit Modules/shared, then run sync.
 */
@Composable
fun <T> ChoiceDialog(
    title: String,
    options: List<Pair<T, String>>,
    onPick: (T) -> Unit,
    onDismiss: () -> Unit,
    selected: T? = null,
    icon: ImageVector? = null,
    text: String? = null,
    danger: Set<T> = emptySet()
) {
    val haptics = rememberHaptics()
    ZoneAlertDialog(
        onDismissRequest = onDismiss,
        icon = icon?.let { vector ->
            { Icon(vector, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp)) }
        },
        title = { Text(title) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (text != null) {
                    Text(text, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(16.dp))
                }
                Column(
                    Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    options.forEach { (value, label) ->
                        val chosen = selected != null && value == selected
                        val tint = if (value in danger) MaterialTheme.colorScheme.error else Color.Unspecified
                        ZoneSurface(
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).semantics { this.selected = chosen },
                            shape = CircleShape,
                            accent = chosen,
                            onClick = {
                                haptics.tick()
                                onPick(value)
                                onDismiss()
                            }
                        ) {
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 20.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (chosen) {
                                    Tick(tint)
                                    Spacer(Modifier.size(8.dp))
                                }
                                Text(
                                    label,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = tint,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            QuietButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/** The check of a chosen option, in the label's colour ([Color.Unspecified]: the zone's text colour). */
@Composable
private fun Tick(color: Color) {
    val ink = if (color == Color.Unspecified) androidx.compose.material3.LocalContentColor.current else color
    Canvas(Modifier.size(18.dp)) {
        val path = Path().apply {
            moveTo(size.width * 0.2f, size.height * 0.5f)
            lineTo(size.width * 0.4f, size.height * 0.7f)
            lineTo(size.width * 0.8f, size.height * 0.3f)
        }
        drawPath(path, ink, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}
