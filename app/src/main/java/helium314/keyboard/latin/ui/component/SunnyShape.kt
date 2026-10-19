package helium314.keyboard.latin.ui.component

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.star
import androidx.graphics.shapes.toPath
import kotlin.math.max

/**
 * Material 3 Expressive's "Sunny": an eight pointed star with round points. Its polygon from
 * androidx (Apache-2.0), compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/MaterialShapes.kt
 * (sunny(): RoundedPolygon.star(8, innerRadius .8, CornerRounding(.15))); material3 1.4.0 has no MaterialShapes yet.
 *
 * Shared across the apps of this base: edit Modules/shared, then run sync.sh.
 */
val SunnyShape: Shape by lazy {
    RoundedPolygonShape(RoundedPolygon.star(numVerticesPerRadius = 8, innerRadius = .8f, rounding = CornerRounding(radius = .15f)))
}

/**
 * A polygon of graphics-shapes as a Compose shape, scaled to the size it is given. The recipe of
 * https://developer.android.com/develop/ui/compose/graphics/draw/shapes ("Use polygon as clip").
 */
private class RoundedPolygonShape(private val polygon: RoundedPolygon) : Shape {
    private val matrix = Matrix()
    private val bounds = polygon.calculateBounds().let { Rect(it[0], it[1], it[2], it[3]) }

    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val path: Path = polygon.toPath().asComposePath()
        matrix.reset()
        val maxDimension = max(bounds.width, bounds.height)
        matrix.scale(size.width / maxDimension, size.height / maxDimension)
        matrix.translate(-bounds.left, -bounds.top)
        path.transform(matrix)
        return Outline.Generic(path)
    }
}
