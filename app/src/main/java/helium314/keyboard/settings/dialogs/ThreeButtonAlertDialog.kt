/*
 * Copyright (C) 2021 The Android Open Source Project
 * parts taken from Material3 AlertDialog.kt
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */
package helium314.keyboard.settings.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.ui.component.BoldButton
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.window.DialogProperties
import helium314.keyboard.latin.ui.component.ZoneAlertDialog
import helium314.keyboard.latin.utils.Theme
import helium314.keyboard.latin.utils.previewDark

// Keyboard: the dialog is Material's AlertDialog made of glass, ZoneAlertDialog (latin/ui/component/ZoneDialog.kt, the
// panes kit as in Calculator's SettingsScreen, https://github.com/213YaZ786/Calculator/blob/main/app/src/main/java/com/yaz/calculator/feature/settings/SettingsScreen.kt),
// with the same title, content and buttons. Its paddings are Material's, so [reducePadding] has no effect; the neutral
// button sits beside Cancel, in the dismiss slot of AlertDialog.
@Composable
fun ThreeButtonAlertDialog(
    onDismissRequest: () -> Unit,
    onConfirmed: () -> Unit,
    modifier: Modifier = Modifier,
    title: @Composable (() -> Unit)? = null,
    content: @Composable (() -> Unit)? = null,
    scrollContent: Boolean = false,
    onNeutral: () -> Unit = { },
    checkOk: () -> Boolean = { true },
    confirmButtonText: String? = stringResource(android.R.string.ok),
    cancelButtonText: String = stringResource(android.R.string.cancel),
    neutralButtonText: String? = null,
    @Suppress("UNUSED_PARAMETER") reducePadding: Boolean = false,
    properties: DialogProperties = DialogProperties()
) {
    ZoneAlertDialog(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        title = title,
        text = content?.let {
            {
                if (scrollContent) Box(Modifier.verticalScroll(rememberScrollState())) { content() }
                else content()
            }
        },
        confirmButton = {
            if (confirmButtonText != null)
                // Keyboard: the dialog buttons are BoldButton (latin/ui/component/BoldButtons.kt, the panes kit, used as in
                // https://github.com/213YaZ786/Calculator/blob/main/app/src/main/java/com/yaz/calculator/feature/main/WelcomeScreen.kt),
                // the confirming one the prominent kind (filled = true).
                BoldButton(
                    enabled = checkOk(),
                    filled = true,
                    onClick = { onConfirmed(); onDismissRequest() },
                ) { Text(confirmButtonText) }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (neutralButtonText != null)
                    BoldButton(
                        onClick = onNeutral
                    ) { Text(neutralButtonText) }
                BoldButton(onClick = onDismissRequest) { Text(cancelButtonText) }
            }
        },
        properties = properties
    )
}

@Preview
@Composable
private fun Preview() {
    Theme(previewDark) {
        ThreeButtonAlertDialog(
            onDismissRequest = {},
            onConfirmed = { },
            content = { Text("hello") },
            title = { Text("title") },
            neutralButtonText = "Default"
        )
    }
}
