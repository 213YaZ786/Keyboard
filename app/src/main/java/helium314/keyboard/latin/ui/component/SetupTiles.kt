package helium314.keyboard.latin.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp

/*
 * The welcome guide as tiles, the one guide of every app (the user, 2026-11-02). The state logic is FlorisBoard's
 * setup: each step is read from the system, not remembered (FlorisStepState's automatic step, SetupScreen's `when`
 * of what is enabled, selected, allowed), so coming back from Android's page shows the step done.
 * FlorisBoard's steps are numbered one under another; here they are the tiles of Settings (SettingsTiles' TileFace
 * and grid), with iOS's count of what is left ("Finish Setting Up Your iPhone").
 *
 * Copyright (C) 2021-2025 The FlorisBoard Contributors, Apache License 2.0
 * (http://www.apache.org/licenses/LICENSE-2.0), translated from
 * https://github.com/florisboard/florisboard/blob/main/lib/compose/src/main/kotlin/org/florisboard/lib/compose/FlorisStepLayout.kt
 * and https://github.com/florisboard/florisboard/blob/main/app/src/main/kotlin/dev/patrickgold/florisboard/app/setup/SetupScreen.kt
 */

/**
 * One thing to settle: an icon, a short [name], its [state] on the second line, and in [summary] what a long press
 * explains. [done] is read from the system by the screen; [required] steps hold the guide's button back, the others
 * are offered. [error] draws the state in the error colour, [progress] (0..1) a wavy progress under it (a download).
 * A tap runs [onClick].
 * Ported from FlorisStep (id, title, content) of
 * https://github.com/florisboard/florisboard/blob/main/lib/compose/src/main/kotlin/org/florisboard/lib/compose/FlorisStepLayout.kt#FlorisStep
 */
class SetupTile(
    val id: String,
    val name: String,
    val title: String,
    val icon: ImageVector,
    val state: String,
    val summary: String,
    val done: Boolean,
    val required: Boolean,
    val error: Boolean = false,
    val progress: Float? = null,
    val onClick: () -> Unit
)

/**
 * The step the guide stands at: the first one not done, as FlorisStepState's currentAuto, which SetupScreen sets to
 * the first of EnableIme, SelectIme, SelectNotification that the system does not show done, then FinishUp. The
 * required ones come first, so the next step is never an optional one while a required one waits.
 * Ported from
 * https://github.com/florisboard/florisboard/blob/main/lib/compose/src/main/kotlin/org/florisboard/lib/compose/FlorisStepLayout.kt#FlorisStepState
 */
fun List<SetupTile>.getCurrentAuto(): SetupTile? = firstOrNull { it.required && !it.done } ?: firstOrNull { !it.done }

/** How many tiles are not done yet, the count iOS shows on "Finish Setting Up Your iPhone". */
fun List<SetupTile>.left(): Int = count { !it.done }

/** The required tiles not done yet: what the guide's button waits for. */
fun List<SetupTile>.requiredLeft(): List<SetupTile> = filter { it.required && !it.done }

/** Whether every required tile is done: FlorisStepState's FinishUp, reached once the steps before it are. */
fun List<SetupTile>.requiredDone(): Boolean = requiredLeft().isEmpty()

/** "N left", or "All set" when nothing is: the line under the guide's title and the value of Settings' tile. */
fun List<SetupTile>.leftText(): String = if (left() == 0) "All set" else "${left()} left"

/**
 * The tiles of the guide, in equal cells laid out exactly as SettingsTileGrid (the same columns rule: at least
 * 160 dp and the widest word of the names, from 1 to 4; one size for all the names, rememberGroupLabelSize; the
 * tiles of a row as tall as the tallest; 12 dp between them; a last row not full is centred). A tap runs the tile's
 * own action, a long press calls [onExplain]. The grid is SettingsTileGrid of
 * https://github.com/213YaZ786/SMS/blob/main/app/src/main/java/com/yaz/sms/ui/component/SettingsTiles.kt#SettingsTileGrid
 * (our shared copy, itself AOSP SystemUI's TileLayout), the steps listed in order as FlorisStepLayout does.
 */
@Composable
fun SetupTileGrid(tiles: List<SetupTile>, onExplain: (SetupTile) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        val style = MaterialTheme.typography.labelLarge
        val measurer = rememberTextMeasurer()
        val density = LocalDensity.current
        val wordPx = remember(tiles.map { it.name }, style, density) {
            tiles.maxOfOrNull { tile ->
                tile.name.split(' ').filter { it.isNotEmpty() }
                    .maxOfOrNull { measurer.measure(it, style, softWrap = false, maxLines = 1).size.width } ?: 0
            } ?: 0
        }
        // The tile's own padding is 12 dp each side.
        val wordNeed = with(density) { wordPx.toDp() } + 24.dp
        val cellMin = maxOf(160.dp, wordNeed)
        val fit = (maxWidth / cellMin).toInt()
        val columns = (if (fit < 2 && (maxWidth - 12.dp) / 2 >= wordNeed) 2 else fit).coerceIn(1, 4)
        val cell = (maxWidth - 12.dp * (columns - 1)) / columns
        val nameSize = rememberGroupLabelSize(tiles.map { it.name }, style, cell - 24.dp)
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            tiles.chunked(columns).forEach { row ->
                Row(
                    Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)
                ) {
                    row.forEach { tile ->
                        val tileModifier = Modifier.width(cell).fillMaxHeight()
                        SetupTileView(tile, nameSize, tileModifier) { onExplain(tile) }
                    }
                }
            }
        }
    }
}

/**
 * One step as a tile of Settings' look (TileFace): the icon over the name, the state under it; once done, the
 * accent wash and a check under the state. A state in error is drawn in the error colour, a download shows
 * Modules' wavy progress under the state. TalkBack reads a button with the full name and, as its state, the state
 * line and "Done", "Required" or "Optional". Ported from ActionTileView of
 * https://github.com/213YaZ786/SMS/blob/main/app/src/main/java/com/yaz/sms/ui/component/SettingsTiles.kt#ActionTileView
 */
@Composable
private fun SetupTileView(tile: SetupTile, nameSize: TextUnit, modifier: Modifier = Modifier, onExplain: () -> Unit) {
    val haptics = rememberHaptics()
    val status = if (tile.done) "Done" else if (tile.required) "Required" else "Optional"
    val progress = tile.progress
    TileFace(
        nameSize = nameSize,
        icon = tile.icon,
        name = tile.name,
        state = tile.state,
        on = tile.done,
        modifier = modifier,
        dimmed = false,
        onClick = {
            haptics.tick()
            tile.onClick()
        },
        onLongClick = {
            haptics.firm()
            onExplain()
        },
        semantics = {
            contentDescription = tile.title
            role = Role.Button
            stateDescription = "${tile.state.replace('\n', ' ')}, $status"
        },
        stateColor = if (tile.error) MaterialTheme.colorScheme.error else null,
        below = when {
            progress != null -> ({ SetupProgress(progress) })
            tile.done -> ({
                Icon(SetupCheck, contentDescription = null, tint = LocalContentColor.current, modifier = Modifier.padding(top = 4.dp).size(18.dp))
            })
            else -> null
        }
    )
}

/**
 * The download's progress under the state: Modules' LinearWavyProgress (ported from androidx material3's
 * LinearWavyProgressIndicator), the full width of the tile, 6 dp under the state; the value eases between two
 * readings (WavyProgressDefaults.ProgressAnimationSpec, the spec Material 3 recommends).
 * https://github.com/androidx/androidx/blob/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/WavyProgressIndicator.kt#LinearWavyProgressIndicator
 */
@Composable
private fun SetupProgress(progress: Float) {
    val shown by animateFloatAsState(progress, WavyProgressDefaults.ProgressAnimationSpec, label = "setupProgress")
    LinearWavyProgress(progress = { shown }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
}

/**
 * A tile's long press: the step's full name and what it does, Done to close. Ported from InfoDialog of
 * https://github.com/213YaZ786/SMS/blob/main/app/src/main/java/com/yaz/sms/ui/component/SettingsTiles.kt#InfoDialog
 */
@Composable
fun SetupTileDialog(tile: SetupTile, onDismiss: () -> Unit) {
    ZoneAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tile.title) },
        text = { Text(tile.summary, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        confirmButton = { BoldButton(onClick = onDismiss) { Text("Done") } }
    )
}

/** The check under a finished step: Material Symbols' check (Apache-2.0), drawn here so no app needs its own icon. */
private val SetupCheck: ImageVector by lazy {
    ImageVector.Builder(
        name = "SetupCheck",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        addPath(
            pathData = PathParser().parsePathString("M9 16.2L4.8 12l-1.4 1.4L9 19 21 7l-1.4-1.4L9 16.2z").toNodes(),
            fill = SolidColor(Color.Black)
        )
    }.build()
}
