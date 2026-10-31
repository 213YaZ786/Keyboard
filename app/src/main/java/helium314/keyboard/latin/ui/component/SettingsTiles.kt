package helium314.keyboard.latin.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.TextUnit

// Settings as tiles, the one look of every app (SMS's, 2026-10-28; Dialer's iconTint). Each app lists its
// SettingsSection values, its tiles, and puts them in FloatingFrame with SettingsListDetail on a wide window
// and SettingsFoldedSections on a phone. Ported from SMS's and Dialer's SettingsScreen.kt (our own apps), which
// follow Android Quick Settings' tile, AOSP SystemUI QSTileViewImpl:
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/packages/SystemUI/src/com/android/systemui/qs/tileimpl/QSTileViewImpl.kt

/**
 * A group of settings: folded one under another on a phone, a list and the open one's page on a wide window.
 * [id] is what an app saves across a turn of the screen.
 */
class SettingsSection(val id: String, val title: String, val icon: ImageVector)

/** What a tile of Settings shows: an icon, a short name, and under it its state or value (Quick Settings' tile). */
sealed interface SettingsTile {
    val id: String
    val name: String
    val title: String
    val icon: ImageVector
    val summary: String
}

/**
 * A setting that is only a switch, shown as a tile. [name] is the short name on the tile, [title] the full
 * one (TalkBack, the dialog), [summary] the explanation the long press shows. A tile that [enabled] is false
 * depends on another setting: it shows, dimmed, and does not toggle.
 */
class SettingsSwitchTile(
    override val id: String,
    override val name: String,
    override val title: String,
    override val icon: ImageVector,
    override val summary: String,
    val checked: Boolean,
    val enabled: Boolean = true,
    val onChange: (Boolean) -> Unit
) : SettingsTile

/**
 * A setting that opens a choice, a page or a link, shown as a tile: [value] is its current value, the second
 * line (Quick Settings' secondary label, AOSP SystemUI QSTileViewImpl); [summary] the full text a long press
 * shows. Without [onClick] (the version) a tap shows the summary too. [iconTint] colours the icon (Dialer's
 * SIM protection level).
 */
class SettingsActionTile(
    override val id: String,
    override val name: String,
    override val title: String,
    override val icon: ImageVector,
    val value: String,
    override val summary: String,
    val iconTint: Color? = null,
    val onClick: (() -> Unit)? = null
) : SettingsTile

/**
 * The room the floating header takes. It shrinks as the large title collapses, so it is read when the
 * spacer is laid out: reading it in composition would redo the whole page on every frame of the scroll.
 */
@Composable
fun SettingsHeaderSpacer(padding: PaddingValues) {
    Spacer(Modifier.layout { _, constraints -> layout(constraints.minWidth, padding.calculateTopPadding().roundToPx()) {} })
}

/**
 * List-detail on a wide window: the sections as a list on the left, the chosen one's page on the right,
 * each pane scrolling on its own. The pattern of Android's large-screen canonical layout "list-detail" as the
 * Reply sample draws it (ReplyInboxScreen: a TwoPane of the list and the opened item when the content type is
 * DUAL_PANE, one page otherwise),
 * https://github.com/android/compose-samples/blob/main/Reply/app/src/main/java/com/example/reply/ui/ReplyListContent.kt
 * and androidx's ListDetailPaneScaffold
 * (https://github.com/androidx/androidx/blob/androidx-main/compose/material3/adaptive/adaptive-layout/src/commonMain/kotlin/androidx/compose/material3/adaptive/layout/ListDetailPaneScaffold.kt)
 * for the roles, a library this build does not carry. The list takes a third (Reply splits in halves; a list of
 * four names needs less).
 */
@Composable
fun SettingsListDetail(
    padding: PaddingValues,
    sections: List<SettingsSection>,
    section: SettingsSection,
    onSection: (SettingsSection) -> Unit,
    detailScroll: ScrollState,
    detail: @Composable ColumnScope.() -> Unit
) {
    Row(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 8.dp)
        ) {
            SettingsHeaderSpacer(padding)
            Spacer(Modifier.height(24.dp))
            sections.forEach { SectionListRow(it, selected = it == section) { onSection(it) } }
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
        // The page starts at the top again for each section.
        key(section.id) {
            Column(
                Modifier
                    .weight(2f)
                    .fillMaxHeight()
                    .verticalScroll(detailScroll)
            ) {
                SettingsHeaderSpacer(padding)
                detail()
                Spacer(Modifier.height(24.dp))
                Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
            }
        }
    }
}

/**
 * A section in the list of a wide window: its icon and name, the chosen one washed with the accent.
 * On a phone the same row heads a folded section: [expanded] says whether its tiles are open under it,
 * and TalkBack reads it as a button that is expanded or collapsed (a state description, as Compose's
 * accessibility guide gives a toggling control:
 * https://developer.android.com/develop/ui/compose/accessibility/semantics).
 */
@Composable
private fun SectionListRow(section: SettingsSection, selected: Boolean, expanded: Boolean? = null, onClick: () -> Unit) {
    val haptics = rememberHaptics()
    ZoneSurface(
        // A zone on the page: Material 3's extra large corner, as the tiles (ShapeDefaults,
        // https://developer.android.com/reference/kotlin/androidx/compose/material3/ShapeDefaults).
        shape = androidx.compose.material3.ShapeDefaults.ExtraLarge,
        accent = selected,
        onClick = {
            haptics.tick()
            onClick()
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .semantics {
                if (expanded == null) this.selected = selected
                else {
                    role = Role.Button
                    stateDescription = if (expanded) "Expanded" else "Collapsed"
                }
            }
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(section.icon, contentDescription = null, modifier = Modifier.size(24.dp))
            Text(section.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 16.dp))
        }
    }
}

/**
 * A phone's Settings: the sections as the wide window's list shows them, all folded; a tap unfolds that
 * section's tiles right under its row (the others fold, so one is open at a time; a second tap folds it).
 * The tiles slide open with AnimatedVisibility's expandVertically + fadeIn, Compose's recipe for content that
 * appears under a header
 * (https://github.com/androidx/androidx/blob/androidx-main/compose/animation/animation/src/commonMain/kotlin/androidx/compose/animation/AnimatedVisibility.kt,
 * https://developer.android.com/develop/ui/compose/animation/composables-modifiers#animatedvisibility).
 */
@Composable
fun SettingsFoldedSections(
    sections: List<SettingsSection>,
    open: SettingsSection?,
    onOpen: (SettingsSection?) -> Unit,
    tilesOf: @Composable (SettingsSection) -> Unit
) {
    Column {
        sections.forEach { which ->
            val expanded = which == open
            Box(Modifier.padding(horizontal = 16.dp)) {
                SectionListRow(which, selected = expanded, expanded = expanded) { onOpen(if (expanded) null else which) }
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                // Room around the grid so the tiles' soft shadows are not cut while it slides.
                Column(Modifier.padding(top = 8.dp, bottom = 12.dp)) { tilesOf(which) }
            }
        }
    }
}

/** A section's name, centred, on the page: it does nothing, so it has no zone. */
@Composable
fun SettingsSectionTitle(title: String) {
    // Centred and at title size: a heading names what lies below.
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 28.dp, end = 28.dp, top = 24.dp, bottom = 10.dp)
    )
}

/**
 * The tiles of a section, in equal cells, 2 columns on a phone upright, 3 then 4 as the
 * room grows (a cell is at least 160 dp, and at least the widest word of the names plus the tile's
 * padding, measured at the current font scale and language, so a 200 % font or a long German word
 * makes fewer columns, down to one, instead of a cut name). The cells of Android's Quick Settings: AOSP
 * SystemUI's TileLayout (equal cells, the column count from the window's size class),
 * https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/packages/SystemUI/src/com/android/systemui/qs/TileLayout.java
 * The word is measured with TextMeasurer
 * (https://developer.android.com/develop/ui/compose/text/measure-text-layout).
 * A last row that is not full is centred, so no side is left empty.
 */
@Composable
fun SettingsTileGrid(tiles: List<SettingsTile>, onExplain: (SettingsTile) -> Unit) {
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
        // Two columns whenever the widest word fits in half the width, as before; fewer only when it does not.
        val columns = (if (fit < 2 && (maxWidth - 12.dp) / 2 >= wordNeed) 2 else fit).coerceIn(1, 4)
        // The cell is a column's share of the width, whatever the row holds.
        val cell = (maxWidth - 12.dp * (columns - 1)) / columns
        // One size for the names of the whole section, from the cell's text width (the tile's padding is 12 dp
        // each side): rememberGroupLabelSize, the ladder of TextAutoSize.StepBased asked of the section at once.
        val nameSize = rememberGroupLabelSize(tiles.map { it.name }, style, cell - 24.dp)
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            tiles.chunked(columns).forEach { row ->
                // The tiles of a row are as tall as the tallest (a value on two lines).
                Row(
                    Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)
                ) {
                    row.forEach { tile ->
                        val tileModifier = Modifier.width(cell).fillMaxHeight()
                        when (tile) {
                            is SettingsSwitchTile -> SwitchTileView(tile, nameSize, tileModifier) { onExplain(tile) }
                            is SettingsActionTile -> ActionTileView(tile, nameSize, tileModifier) { onExplain(tile) }
                        }
                    }
                }
            }
        }
    }
}

/**
 * One switch as a tile of glass, as a Quick Settings tile: the icon over the name, the state under it,
 * a tap toggles, a long press opens the explanation. Android's QSTileViewImpl (icon, label, secondary
 * label; the long press for details; TalkBack reads a Switch, checked or not, with its state description),
 * https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/packages/SystemUI/src/com/android/systemui/qs/tileimpl/QSTileViewImpl.kt
 * Active tiles are washed with the accent (ZoneSurface accent). The tap target is the transparent
 * inner area, as Modules' glass note asks; TalkBack gets a switch with its full name and its state.
 */
@Composable
private fun SwitchTileView(tile: SettingsSwitchTile, nameSize: TextUnit, modifier: Modifier = Modifier, onExplain: () -> Unit) {
    val haptics = rememberHaptics()
    val on = tile.checked
    val state = if (on) "On" else "Off"
    TileFace(
        nameSize = nameSize,
        icon = tile.icon,
        name = tile.name,
        state = state,
        on = on,
        modifier = modifier,
        dimmed = !tile.enabled,
        onClick = {
            if (tile.enabled) {
                haptics.toggle(!on)
                tile.onChange(!on)
            }
        },
        onLongClick = {
            haptics.firm()
            onExplain()
        },
        semantics = {
            contentDescription = tile.title
            role = Role.Switch
            toggleableState = ToggleableState(on)
            stateDescription = if (tile.enabled) state else "$state, unavailable"
        }
    )
}

/**
 * One setting that opens something, as a tile of the same look: the icon, the name, its current value as the
 * second line (QSTileViewImpl's secondary label), never "On" and never in the accent: only a switch that is on
 * is. A tap does what the row's tap did, a long press shows the full text. TalkBack gets a button with the full
 * name and the value as its state.
 */
@Composable
private fun ActionTileView(tile: SettingsActionTile, nameSize: TextUnit, modifier: Modifier = Modifier, onExplain: () -> Unit) {
    val haptics = rememberHaptics()
    TileFace(
        nameSize = nameSize,
        icon = tile.icon,
        iconTint = tile.iconTint,
        name = tile.name,
        state = tile.value,
        on = false,
        modifier = modifier,
        dimmed = false,
        onClick = {
            haptics.tick()
            val open = tile.onClick
            if (open != null) open() else onExplain()
        },
        onLongClick = {
            haptics.firm()
            onExplain()
        },
        semantics = {
            contentDescription = tile.title
            role = Role.Button
            stateDescription = tile.value
        }
    )
}

/**
 * The look of a tile, a Quick Settings tile: the icon over the name, the state or value under it on one or
 * two lines. Android's QSTileViewImpl (icon, label, secondary label; the long press for details),
 * https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/packages/SystemUI/src/com/android/systemui/qs/tileimpl/QSTileViewImpl.kt
 * Active tiles ([on]) are washed with the accent (ZoneSurface accent). The tap target is the transparent
 * inner area, as Modules' glass note asks. [iconTint] colours the icon (Dialer's SIM protection level).
 */
@Composable
private fun TileFace(
    icon: ImageVector,
    name: String,
    state: String,
    on: Boolean,
    modifier: Modifier,
    dimmed: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    semantics: SemanticsPropertyReceiver.() -> Unit,
    nameSize: TextUnit,
    iconTint: Color? = null
) {
    // SystemUI's own sizes (res/values/dimens.xml: qs_tile_height 80dp, qs_corner_radius 28dp): a tile is
    // as tall as its content needs, not square, so a section fits on one screen; our icon stands over the
    // name instead of beside it, hence 96 dp at least.
    val shape = RoundedCornerShape(28.dp)
    val quiet = MaterialTheme.colorScheme.onSurfaceVariant
    ZoneSurface(shape = shape, accent = on, modifier = modifier.heightIn(min = 96.dp)) {
        Column(
            Modifier
                .fillMaxSize()
                .clip(shape)
                .combinedClickable(
                    onLongClickLabel = "Explain",
                    onLongClick = onLongClick,
                    onClick = onClick
                )
                .semantics(mergeDescendants = true, properties = semantics)
                .graphicsLayer { alpha = if (dimmed) 0.38f else 1f }
                .padding(12.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = iconTint ?: if (on) LocalContentColor.current else quiet
            )
            // The name wraps between words, whatever the language or font, at the one size the section's
            // tiles share ([nameSize], rememberGroupLabelSize: the ladder of TextAutoSize.StepBased,
            // https://developer.android.com/develop/ui/compose/text/autosize, asked of the whole section).
            val nameStyle = MaterialTheme.typography.labelLarge
            BasicText(
                name,
                style = nameStyle.copy(color = LocalContentColor.current, textAlign = TextAlign.Center, fontSize = nameSize),
                modifier = Modifier.padding(top = 6.dp)
            )
            Text(
                state,
                style = MaterialTheme.typography.labelSmall,
                color = if (on) LocalContentColor.current else quiet,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp).clearAndSetSemantics {}
            )
        }
    }
}

/** A tile's long press (an action tile's, or a tap with nothing to open): the setting's full name and text; a switch tile adds its switch. */
@Composable
fun SettingsTileDialogs(tile: SettingsTile, onDismiss: () -> Unit) {
    when (tile) {
        is SettingsSwitchTile -> SwitchDialog(tile, onDismiss)
        is SettingsActionTile -> InfoDialog(tile, onDismiss)
    }
}

@Composable
private fun InfoDialog(tile: SettingsActionTile, onDismiss: () -> Unit) {
    ZoneAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tile.title) },
        text = { Text(tile.summary, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        confirmButton = { BoldButton(onClick = onDismiss) { Text("Done") } }
    )
}

@Composable
private fun SwitchDialog(tile: SettingsSwitchTile, onDismiss: () -> Unit) {
    val haptics = rememberHaptics()
    val change = { on: Boolean ->
        haptics.toggle(on)
        tile.onChange(on)
    }
    ZoneAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tile.title) },
        text = {
            Column {
                Text(tile.summary, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp)
                        .clip(androidx.compose.material3.ShapeDefaults.Large) // inside a tile: Material 3's large, 16 dp
                        .clickable(enabled = tile.enabled) { change(!tile.checked) }
                        .padding(vertical = 8.dp, horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(if (tile.checked) "On" else "Off")
                    GlassSwitch(checked = tile.checked, onCheckedChange = change, enabled = tile.enabled)
                }
            }
        },
        confirmButton = { BoldButton(onClick = onDismiss) { Text("Done") } }
    )
}
