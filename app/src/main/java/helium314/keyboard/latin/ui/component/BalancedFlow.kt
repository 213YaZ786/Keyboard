package helium314.keyboard.latin.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp

/*
 * Tiles of words in balanced rows, and the label of a tile. Ported unchanged from SMS's long press menu
 * (https://github.com/213YaZ786/SMS/blob/main/app/src/main/java/com/yaz/sms/feature/thread/MessageMenu.kt),
 * only made public so that HoldMenu and the apps' own tiles call them. Its upstreams are cited per function below.
 *
 * Shared across the apps of this base: edit Modules/shared, then run sync.sh.
 */

/**
 * Tiles of words in a flow that adapts, taken from PersonActions in this app (ui/component/PersonActions.kt, the
 * suite's model): the least width of a tile is measured, not guessed, as the widest label on one line in the current
 * style, font scale and language (TextMeasurer, https://developer.android.com/develop/ui/compose/text/measure-text-layout),
 * plus [side] each side, never under [floor]; how many fit a line follows, then the rows are balanced (5 tiles that do
 * not fit one row make 3 + 2, never 4 + 1), and when one line labels leave a lone tile the least width becomes the
 * widest word, so labels wrap between words (FlowRow with maxItemsInEachRow, weight and fillMaxRowHeight,
 * https://developer.android.com/develop/ui/compose/layouts/flow). [maxPerRow] keeps the design's count on a wide
 * window. [fill] spreads the flow over the whole width, else it is as wide as its tiles. [tile] draws one tile with
 * the modifier it must take; its label is a [TileLabel].
 * Official: "Check that your app's layouts adapt correctly without breaking, overlapping, or hiding content"
 * (https://developer.android.com/develop/ui/compose/accessibility/scalable-content).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BalancedFlow(
    labels: List<String>,
    style: TextStyle,
    floor: Dp,
    gap: Dp,
    modifier: Modifier = Modifier,
    rowGap: Dp = gap,
    side: Dp = 0.dp,
    maxPerRow: Int = 4,
    fill: Boolean = false,
    tile: @Composable FlowRowScope.(index: Int, tileModifier: Modifier) -> Unit
) {
    if (labels.isEmpty()) return
    BoxWithConstraints(if (fill) modifier.fillMaxWidth() else modifier) {
        val measurer = rememberTextMeasurer()
        val density = LocalDensity.current
        val available = maxWidth
        // The widest label on one line and the widest single word, in px.
        val widths = remember(labels, style, density) {
            val oneLine = labels.maxOf { measurer.measure(it, style, softWrap = false, maxLines = 1).size.width }
            val word = labels.maxOf { label ->
                label.split(' ').filter { it.isNotEmpty() }.maxOfOrNull { measurer.measure(it, style, softWrap = false, maxLines = 1).size.width } ?: 0
            }
            oneLine to word
        }
        val oneLineWidth = maxOf(floor, with(density) { widths.first.toDp() } + side * 2)
        val wordWidth = maxOf(floor, with(density) { widths.second.toDp() } + side * 2)
        fun perRowFor(width: Dp): Int {
            val fit = ((available + gap) / (width + gap)).toInt().coerceIn(1, maxPerRow)
            val lines = (labels.size + fit - 1) / fit
            return (labels.size + lines - 1) / lines
        }
        fun lone(perRow: Int) = labels.size > perRow && labels.size % perRow == 1
        // One line labels first; the widest word instead (labels wrapping between words, as Google Messages' and iOS's
        // menu labels do) when it gives more tiles to a line, or when the one line width leaves a lone last tile and
        // the word width does not. A lone tile stays only when neither width avoids it.
        val perOneLine = perRowFor(oneLineWidth)
        val perWord = perRowFor(wordWidth)
        val wrapped = !lone(perWord) && (perWord > perOneLine || lone(perOneLine))
        val perRow = if (wrapped) perWord else perOneLine
        val minWidth = minOf(if (wrapped) wordWidth else oneLineWidth, available)
        val inRow = minOf(perRow, labels.size)
        // The row is as wide as its tiles' least widths and gaps added in whole pixels, as FlowRow rounds each of them
        // (Dp.roundToPx): added in dp it came one pixel short of three tiles (3 x 78 dp + 2 x 6 dp = 645.75 px, three
        // tiles of 205 px and two gaps of 16 px need 647), and the third tile of a line dropped to the next.
        val rowWidth = minOf(with(density) { (minWidth.roundToPx() * inRow + gap.roundToPx() * (inRow - 1)).toDp() }, available)
        // One size for every label of the group, computed once from the width a tile gets in a full row
        // (rememberGroupLabelSize, ui/component/GroupLabelSize.kt: the ladder of TextAutoSize.StepBased asked of the
        // whole group); the TileLabels below read it, none sizes itself.
        val tileWidth = ((if (fill) available else rowWidth) - gap * (inRow - 1)) / inRow
        val labelSize = rememberGroupLabelSize(labels, style, tileWidth - side * 2)
        CompositionLocalProvider(LocalGroupLabelSize provides labelSize) {
            FlowRow(
                if (fill) Modifier.fillMaxWidth() else Modifier.width(rowWidth),
                horizontalArrangement = Arrangement.spacedBy(gap),
                verticalArrangement = Arrangement.spacedBy(rowGap),
                maxItemsInEachRow = perRow
            ) {
                labels.indices.forEach { i -> tile(i, Modifier.weight(1f).fillMaxRowHeight().widthIn(min = minWidth)) }
            }
        }
    }
}

/** The size a [BalancedFlow] computed for its group; unspecified outside one, where a label sizes itself. */
val LocalGroupLabelSize = compositionLocalOf { TextUnit.Unspecified }

/**
 * A halo in the page's own colour for plain text drawn over messages: a TextStyle shadow, offset 0, blur 12 dp,
 * the surface colour at high alpha (https://developer.android.com/develop/ui/compose/text/style-text#add-shadow),
 * so it reads in light and dark over any bubble.
 */
@Composable
fun haloShadow(): Shadow =
    Shadow(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f), Offset.Zero, with(LocalDensity.current) { 12.dp.toPx() })

/** The text drawn twice at the same place, so its halo is dense enough; the second copy is silent to TalkBack. */
@Composable
fun HaloStack(modifier: Modifier = Modifier, draw: @Composable (Modifier) -> Unit) {
    Box(modifier) {
        draw(Modifier)
        draw(Modifier.clearAndSetSemantics {})
    }
}

/**
 * The name of a tile: lines break between words only. In a [BalancedFlow] it is drawn at the one size the group
 * computed (no autosize of its own, so two tiles never read in two sizes); alone, a single word still too wide
 * shrinks as the last resort, Compose's TextAutoSize.StepBased
 * (https://developer.android.com/develop/ui/compose/text/autosize), as the label of PersonActionTile in this app.
 * [halo] puts a halo in the page's colour behind it, for plain text over messages ([haloShadow]).
 */
@Composable
fun TileLabel(text: String, style: TextStyle, color: Color, modifier: Modifier = Modifier, halo: Boolean = false) {
    val group = LocalGroupLabelSize.current
    val grouped = group.isSpecified
    val size = if (grouped && group.value < style.fontSize.value) group else style.fontSize
    val shadow = if (halo) haloShadow() else null
    val look = style.copy(color = color, textAlign = TextAlign.Center, fontSize = size, shadow = shadow ?: style.shadow)
    val draw: @Composable (Modifier) -> Unit = { m ->
        if (grouped) {
            BasicText(text, m, style = look)
        } else {
            BasicText(text, m, style = look, autoSize = TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = style.fontSize, stepSize = 0.5.sp))
        }
    }
    if (halo) HaloStack(modifier, draw) else draw(modifier)
}
