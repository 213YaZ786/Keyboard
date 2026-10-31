package helium314.keyboard.latin.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * One text size for all the labels of a group (tiles, buttons of one row or one menu), computed once:
 * the largest size from the style's down to [min], in [step]s, at which the longest word of every label
 * fits [textWidth]; every label of the group is then drawn at that size, wrapping between words, so
 * two tiles side by side never read in two sizes (Material's type scale and Apple's Dynamic Type give a
 * group one size). The ladder is Compose's TextAutoSize.StepBased, which takes the largest size of
 * minFontSize..maxFontSize by stepSize at which one text fits
 * (https://github.com/androidx/androidx/blob/androidx-main/compose/foundation/foundation/src/commonMain/kotlin/androidx/compose/foundation/text/AutoSize.kt,
 * https://developer.android.com/develop/ui/compose/text/autosize), here asked of the whole group at once;
 * the fit is measured with TextMeasurer, as in PersonActions
 * (https://developer.android.com/develop/ui/compose/text/measure-text-layout).
 * A single label that is not in a group keeps TextAutoSize.
 */
@Composable
fun rememberGroupLabelSize(
    labels: List<String>,
    style: TextStyle,
    textWidth: Dp,
    min: TextUnit = 9.sp,
    step: TextUnit = 0.5.sp
): TextUnit {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(labels, style, textWidth, min, step, density) {
        groupLabelSize(measurer, density, labels, style, textWidth, min, step)
    }
}

/** [rememberGroupLabelSize] without Compose state, for a caller that already holds a measurer. */
fun groupLabelSize(
    measurer: TextMeasurer,
    density: Density,
    labels: List<String>,
    style: TextStyle,
    textWidth: Dp,
    min: TextUnit = 9.sp,
    step: TextUnit = 0.5.sp
): TextUnit {
    val max = style.fontSize
    if (!max.isSp || !min.isSp || !step.isSp || max.value <= min.value || step.value <= 0f) return max
    if (textWidth.value.isNaN() || textWidth.value.isInfinite() || textWidth.value <= 0f) return max
    val words = labels.flatMap { it.split(' ', '\n') }.filter { it.isNotEmpty() }.distinct()
    if (words.isEmpty()) return max
    val limit = with(density) { textWidth.toPx() }
    var size = max.value
    while (size > min.value) {
        val sized = style.copy(fontSize = size.sp)
        if (words.all { measurer.measure(it, sized, softWrap = false, maxLines = 1).size.width <= limit }) return size.sp
        size -= step.value
    }
    return min
}
