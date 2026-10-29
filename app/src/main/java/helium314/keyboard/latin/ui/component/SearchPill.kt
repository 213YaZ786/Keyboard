package helium314.keyboard.latin.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.ui.icon.AppIcons

/**
 * A pill of glass to type a name or a number in; the cross clears it. Opened from a
 * search icon, it takes the keyboard at once and its cross, once empty, folds it away
 * ([autoFocus], [onClose]), as Material 3's search opened from an app bar icon does
 * (https://m3.material.io/components/search/guidelines); the focus taken by Compose's
 * FocusRequester recipe (https://developer.android.com/develop/ui/compose/touch-input/focus/change-focus-behavior).
 */
@Composable
fun SearchPill(
    value: String,
    onChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
    floating: Boolean = false,
    autoFocus: Boolean = false,
    onClose: (() -> Unit)? = null
) {
    val haptics = rememberHaptics()
    val focus = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }
    if (autoFocus) androidx.compose.runtime.LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val pane: @Composable (@Composable () -> Unit) -> Unit = { inner ->
        if (floating) FloatingPane(shape = CircleShape, modifier = modifier) { inner() } else ZoneSurface(shape = CircleShape, modifier = modifier) { inner() }
    }
    pane {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 18.dp, end = 4.dp)) {
            Icon(AppIcons.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Box(Modifier.weight(1f).padding(vertical = 14.dp)) {
                BasicTextField(
                    value = value,
                    onValueChange = onChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = androidx.compose.ui.Modifier.focusRequester(focus),
                    decorationBox = { field ->
                        // The hint stays on one line as the field does (singleLine), cut at the largest font.
                        if (value.isEmpty()) Text(hint, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        field()
                    }
                )
            }
            AnimatedVisibility(visible = value.isNotEmpty() || onClose != null, enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
                IconButton(onClick = {
                    haptics.tick()
                    if (value.isNotEmpty()) onChange("") else onClose?.invoke()
                }) {
                    Icon(AppIcons.Close, contentDescription = if (value.isNotEmpty()) "Clear" else "Close search", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (value.isEmpty() && onClose == null) Spacer(Modifier.size(48.dp))
        }
    }
}
