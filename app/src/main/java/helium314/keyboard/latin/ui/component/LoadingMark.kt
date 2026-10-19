// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.ui.component

import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Keyboard's loading mark, the shape the update kit and the downloads expect. A placeholder until the user
 * validates the app's icon: Material 3's CircularProgressIndicator, indeterminate while [running] with no
 * [progress], determinate once [progress] is above 0, nothing drawn when not [running].
 * https://cs.android.com/androidx/platform/frameworks/support/+/androidx-main:compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/ProgressIndicator.kt
 */
@Composable
fun LoadingMark(
    modifier: Modifier = Modifier,
    size: Dp = 32.dp,
    running: Boolean = true,
    progress: Float = 0f
) {
    if (!running) return
    val box = modifier.size(size)
    if (progress > 0f) CircularProgressIndicator(progress = { progress.coerceAtMost(1f) }, modifier = box, strokeWidth = size / 10)
    else CircularProgressIndicator(modifier = box, strokeWidth = size / 10)
}
