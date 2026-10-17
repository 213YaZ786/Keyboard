// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import helium314.keyboard.keyboard.internal.GlassKeyDrawable
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.ResourceUtils
import helium314.keyboard.latin.utils.ToolbarKey
import helium314.keyboard.latin.utils.ToolbarKey.*
import helium314.keyboard.latin.utils.createToolbarKey
import helium314.keyboard.latin.utils.isDarkColor
import helium314.keyboard.latin.utils.onClickToolbarKey
import helium314.keyboard.latin.utils.onLongClickToolbarKey
import kotlin.math.max

/**
 * Keyboard: the panel of every tool, shown in place of the keys when the bar's grid key is tapped (Glass only).
 *
 * The idea is FlorisBoard's quick actions overflow panel (Apache-2.0, QuickActionsOverflowPanel.kt: a grid of tiles over
 * the keyboard's height, adaptive columns, https://github.com/florisboard/florisboard/blob/main/app/src/main/kotlin/dev/patrickgold/florisboard/ime/smartbar/quickaction/QuickActionsOverflowPanel.kt,
 * and QuickActionButton.kt, a tile = the icon over the action's name), here with HeliBoard's own pieces: the tool's
 * button (createToolbarKey, icon, name and state), its code (onClickToolbarKey, onLongClickToolbarKey, the bar's path)
 * and the way ClipboardHistoryView takes the keyboard's place (size from ResourceUtils, KeyboardSwitcher shows and hides it).
 * Gboard's panel works so: every tool with its name, a tap uses it, the grid key again or Back closes it.
 * The grid of tiles is android.widget.GridLayout in a ScrollView (vertical scroll only),
 * https://developer.android.com/reference/android/widget/GridLayout
 */
@SuppressLint("ViewConstructor")
class ToolsPanelView(context: Context, private val listener: KeyboardActionListener) : LinearLayout(context) {
    private val scroll = ScrollView(context)
    private val grid = GridLayout(context)
    private val abcKey = TextView(context)

    init {
        orientation = VERTICAL
        visibility = GONE
        // as ClipboardHistoryView: the system bars' insets become padding (onMeasure adds it to the height), so the ABC key
        // stays above the navigation bar's hide-keyboard chevron
        // https://developer.android.com/reference/android/view/View#setFitsSystemWindows(boolean)
        fitsSystemWindows = true
        scroll.isVerticalScrollBarEnabled = false
        scroll.overScrollMode = OVER_SCROLL_NEVER
        scroll.addView(grid, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        // the way back to the letters, in the place of the bottom row: a functional key's glass
        val colors = Settings.getValues().mColors
        val dp = resources.displayMetrics.density
        abcKey.text = "ABC"
        abcKey.gravity = Gravity.CENTER
        abcKey.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        abcKey.setTextColor(colors.get(ColorType.KEY_TEXT))
        abcKey.setOnClickListener { close() }
        abcKey.background = GlassKeyDrawable.forColorType(context, isDarkColor(colors.get(ColorType.MAIN_BACKGROUND)), ColorType.FUNCTIONAL_KEY_BACKGROUND)
            .also { it.host = abcKey }
        addView(abcKey, LayoutParams((ABC_WIDTH_DP * dp).toInt(), (ABC_HEIGHT_DP * dp).toInt()).apply {
            gravity = Gravity.START
            setMargins((TILE_GAP_DP * dp).toInt() * 2, (TILE_GAP_DP * dp).toInt(), 0, (TILE_GAP_DP * dp).toInt())
        })
    }

    fun close() = KeyboardSwitcher.getInstance().hideToolsPanel()

    /** The tools the panel shows: every ToolbarKey but the voice key (it has the bar's mic) and the two that make no sense as a tile. */
    private fun tools(): List<ToolbarKey> = ToolbarKey.entries.filterNot { it == VOICE || it == CLOSE_HISTORY || it == BACKGROUND_GATHERING }

    /** Fills the grid for a panel [width] px wide (tiles rebuilt each time, so the toggles show their state). */
    fun fill(width: Int) {
        val colors = Settings.getValues().mColors
        val dp = resources.displayMetrics.density
        val gap = (TILE_GAP_DP * dp).toInt()
        // as FlorisBoard's GridCells.Adaptive: as many columns as fit the tile's width, at least 2
        val columns = max(2, (width / (TILE_WIDTH_DP * dp)).toInt())
        val tileWidth = (width - 2 * gap) / columns - 2 * gap
        grid.removeAllViews()
        grid.columnCount = columns
        grid.setPadding(gap, gap, gap, gap)
        tools().forEachIndexed { index, key ->
            val tile = LinearLayout(context)
            tile.orientation = VERTICAL
            tile.gravity = Gravity.CENTER
            tile.tag = key
            tile.isClickable = true
            tile.background = GlassKeyDrawable.forTile(context, colors, tile)
            // the tool's own button gives the icon in its state (toggles), not clickable itself: the tile takes the touch
            val icon: ImageButton = createToolbarKey(context, key)
            icon.background = null
            icon.isClickable = false
            icon.isFocusable = false
            icon.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            colors.setColor(icon, ColorType.TOOL_BAR_KEY)
            icon.isEnabled = false; icon.isEnabled = true // as ClipboardHistoryView: Android takes the colour of the "activated" state
            tile.addView(icon, LayoutParams((ICON_DP * dp).toInt(), (ICON_DP * dp).toInt()))
            val label = TextView(context)
            label.text = icon.contentDescription
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            label.setTextColor(colors.get(ColorType.KEY_TEXT))
            label.gravity = Gravity.CENTER
            label.maxLines = 2
            label.ellipsize = android.text.TextUtils.TruncateAt.END
            KeyboardTypeface.applyToTextView(label)
            label.setPadding(gap, gap / 2, gap, 0)
            tile.addView(label, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            tile.setOnClickListener { onTileClick(it, key) }
            tile.setOnLongClickListener {
                onLongClickToolbarKey(it) { code, isRepeat ->
                    listener.onCodeInput(code, Constants.SUGGESTION_STRIP_COORDINATE, Constants.SUGGESTION_STRIP_COORDINATE, isRepeat)
                }
                true
            }
            grid.addView(tile, GridLayout.LayoutParams(GridLayout.spec(index / columns), GridLayout.spec(index % columns)).apply {
                this.width = tileWidth
                this.height = (TILE_HEIGHT_DP * dp).toInt()
                setMargins(gap, gap, gap, gap)
            })
        }
        scroll.scrollTo(0, 0)
    }

    /** A tap runs the tool as the bar does; the panel closes first, but for the tools that work on the text in place. */
    private fun onTileClick(tile: View, key: ToolbarKey) {
        if (key !in STAYS_OPEN) close()
        onClickToolbarKey(tile) {
            listener.onCodeInput(it, Constants.SUGGESTION_STRIP_COORDINATE, Constants.SUGGESTION_STRIP_COORDINATE, false)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // the size of the clipboard and emoji views, which take the keyboard's place (ClipboardHistoryView.onMeasure)
        val settings = Settings.getValues()
        val width = ResourceUtils.getKeyboardWidth(context, settings) + paddingLeft + paddingRight
        val height = ResourceUtils.getSecondaryKeyboardHeight(resources, settings) + paddingTop + paddingBottom
        super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
    }

    companion object {
        private const val TILE_WIDTH_DP = 96f // the column width the user asked for
        private const val TILE_HEIGHT_DP = 76f
        private const val TILE_GAP_DP = 4f // KeyboardKit's toolbar padding, as the bar's
        private const val ICON_DP = 26f
        private const val ABC_WIDTH_DP = 96f
        private const val ABC_HEIGHT_DP = 40f

        /** The tools that act on the text in place keep the panel open, as Gboard's cursor and editing tools do. */
        private val STAYS_OPEN = setOf(
            LEFT, RIGHT, UP, DOWN, WORD_LEFT, WORD_RIGHT, PAGE_UP, PAGE_DOWN, FULL_LEFT, FULL_RIGHT, PAGE_START, PAGE_END,
            SELECT_ALL, SELECT_WORD, UNDO, REDO, COPY, CUT, PASTE,
        )
    }
}
