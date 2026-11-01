package helium314.keyboard.latin.ui.component

// Segmented lists: each row its own tile, a 2 dp gap between tiles, the outer corners rounded on the first and the last. Ported from
// androidx material3 (Apache-2.0), which our material3 1.4.0 lacks:
// - ListItemDefaults.segmentedShapes(index, count, defaultShapes)
//   https://github.com/androidx/androidx/blob/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/ListItemDefaults.kt
//   count == 1: the base shape with the container's four corners; index == 0: the container's topStart and topEnd; index == count - 1:
//   its bottomStart and bottomEnd; else the base shape;
// - SegmentedGap = ListTokens.SegmentedGap (2 dp)
//   https://github.com/androidx/androidx/blob/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/tokens/ListTokens.kt
// One change: the outer corners are ShapeDefaults.ExtraLarge (28 dp), not Large, because every pane in our apps is ExtraLarge (the
// user's rule, 2026-10-29); the inner ones are ShapeDefaults.ExtraSmall, the token's ItemContainerExpressiveShape (CornerExtraSmall).
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.material3.ShapeDefaults
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/** The gap between two tiles of a segmented list. */
val SegmentedGap = 2.dp

/** The shape of tile [index] of [count] in a segmented list. */
fun segmentedShape(index: Int, count: Int): Shape {
    val base: CornerBasedShape = ShapeDefaults.ExtraSmall
    val container: CornerBasedShape = ShapeDefaults.ExtraLarge
    return when {
        count == 1 -> base.copy(topStart = container.topStart, topEnd = container.topEnd, bottomStart = container.bottomStart, bottomEnd = container.bottomEnd)
        index == 0 -> base.copy(topStart = container.topStart, topEnd = container.topEnd)
        index == count - 1 -> base.copy(bottomStart = container.bottomStart, bottomEnd = container.bottomEnd)
        else -> base
    }
}
